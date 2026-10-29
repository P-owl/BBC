package bus.muhyeon.duel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.DefaultedList;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 등가교환 가치표.
 * - config/duel_alchemy.json 의 values 를 기본값으로 쓰고,
 * - 나머지는 서버에 실제로 로드된 레시피(조합·화로·석재절단기 등)로 원가를 계산한다.
 * - 지정값보다 레시피 원가가 싸면 원가로 내린다 → "싸게 사서 조합해 비싸게 분해" 하는 차익이 생기지 않음.
 */
public final class AlchemyValues {
    private static final int CONFIG_VERSION = 2;

    public static double tax = 0.25;
    public static double stoneTax = 0.10;
    public static long dailyCap = 65536;

    // 현자의 돌 연성 조건
    public static double stoneCost = 4194304;
    public static int stoneSacrifices = 6;
    public static int stoneMinLevel = 100;
    public static int stoneCooldownDays = 30;
    public static int stoneServerCooldownDays = 3;
    /** 현자의 돌 연성 재료 (소모) */
    public static final Map<Item, Integer> STONE_ITEMS = new java.util.LinkedHashMap<>();

    // ── 계열: 하나만 분해해도 같은 계열 전체를 이해 ──
    private static final Map<String, List<String>> FAMILY_RULES = new java.util.LinkedHashMap<>();
    private static Map<Item, List<String>> itemFamilies = Map.of();
    private static Map<String, List<Item>> familyItems = Map.of();

    private static List<String> namespaces = List.of("minecraft", "cobblemon");
    private static final List<Pattern> BLOCKED = new ArrayList<>();
    private static final List<Pattern> NO_OUTPUT = new ArrayList<>();
    private static final Map<String, Double> EXPLICIT = new HashMap<>();

    private static Map<Item, Double> values = Map.of();

    private AlchemyValues() {}

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(AlchemyValues::reload);
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, rm, ok) -> { if (ok) reload(server); });
    }

    // ───────── 조회 ─────────

    public static double valueOf(Item item) {
        Double v = values.get(item);
        return v == null ? 0 : v;
    }

    /** 분해(에너지화) 가능? 불가하면 이유, 가능하면 null */
    public static String depositProblem(ItemStack stack) {
        if (stack.isEmpty()) return "빈 칸";
        if (!stack.getComponentChanges().isEmpty()) return "인챈트·손상·이름·내용물이 있는 아이템은 분해할 수 없습니다";
        if (valueOf(stack.getItem()) <= 0) return "가치가 정해지지 않은 아이템입니다";
        return null;
    }

    public static boolean canOutput(Item item) {
        return valueOf(item) > 0 && !matches(NO_OUTPUT, id(item));
    }

    /** 연성 비용 배율 (현자의 돌을 지니고 있으면 손실이 줄어듦) */
    public static double costMultiplier(boolean hasStone) {
        return 1.0 + (hasStone ? stoneTax : tax);
    }

    public static List<Item> allValued() {
        List<Item> l = new ArrayList<>(values.keySet());
        l.sort(Comparator.comparingDouble(AlchemyValues::valueOf).thenComparing(i -> id(i)));
        return l;
    }

    public static String id(Item item) { return Registries.ITEM.getId(item).toString(); }

    // ───────── 계열 ─────────

    public static List<String> familiesOf(Item item) {
        List<String> l = itemFamilies.get(item);
        return l == null ? List.of() : l;
    }

    public static List<Item> itemsOf(String family) {
        List<Item> l = familyItems.get(family);
        return l == null ? List.of() : l;
    }

    /** 이 아이템을 연성할 수 있을 만큼 이해했는가 (직접 분해했거나, 같은 계열을 분해했거나, 진리를 봤거나) */
    public static boolean isKnown(AlchemyData.Entry e, Item item) {
        if (e.truth || e.known.contains(id(item))) return true;
        for (String f : familiesOf(item)) if (e.known.contains("#" + f)) return true;
        return false;
    }

    /** 연성 목록에 보일 아이템 전부 */
    public static java.util.Set<Item> knownItems(AlchemyData.Entry e) {
        java.util.Set<Item> out = new java.util.LinkedHashSet<>();
        if (e.truth) { out.addAll(values.keySet()); return out; }
        for (String k : e.known) {
            if (k.startsWith("#")) { out.addAll(itemsOf(k.substring(1))); continue; }
            Identifier ident = Identifier.tryParse(k);
            if (ident != null && Registries.ITEM.containsId(ident)) {
                Item it = Registries.ITEM.get(ident);
                if (values.containsKey(it)) out.add(it);
            }
        }
        return out;
    }

    /** 직접 분해해 본 아이템 종류 수 (계열로 얻은 건 제외) — 직업 입문 조건용 */
    public static int depositedKinds(AlchemyData.Entry e) {
        int n = 0;
        for (String k : e.known) if (!k.startsWith("#")) n++;
        return n;
    }

    private static void buildFamilies() {
        Map<Item, List<String>> byItem = new HashMap<>();
        Map<String, List<Item>> byFamily = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, List<String>> fam : FAMILY_RULES.entrySet()) {
            List<Pattern> globs = new ArrayList<>();
            List<net.minecraft.registry.tag.TagKey<Item>> tags = new ArrayList<>();
            for (String r : fam.getValue()) {
                if (r.startsWith("#")) {
                    Identifier t = Identifier.tryParse(r.substring(1));
                    if (t != null) tags.add(net.minecraft.registry.tag.TagKey.of(net.minecraft.registry.RegistryKeys.ITEM, t));
                } else {
                    List<String> parts = new ArrayList<>();
                    for (String part : r.split("\\*", -1)) parts.add(Pattern.quote(part));
                    globs.add(Pattern.compile(String.join(".*", parts)));
                }
            }
            List<Item> members = new ArrayList<>();
            for (Item item : values.keySet()) {
                String id = id(item);
                boolean in = false;
                for (Pattern g : globs) if (g.matcher(id).matches()) { in = true; break; }
                if (!in) for (var t : tags) if (item.getRegistryEntry().isIn(t)) { in = true; break; }
                if (!in) continue;
                members.add(item);
                byItem.computeIfAbsent(item, k -> new ArrayList<>()).add(fam.getKey());
            }
            members.sort(Comparator.comparingDouble(AlchemyValues::valueOf).thenComparing(AlchemyValues::id));
            if (!members.isEmpty()) byFamily.put(fam.getKey(), members);
        }
        itemFamilies = byItem;
        familyItems = byFamily;
    }

    // ───────── 불러오기 ─────────

    public static synchronized String reload(MinecraftServer server) {
        try {
            loadConfig();
        } catch (Exception e) {
            DuelMod.LOG.error("[alchemy] 설정을 읽지 못했습니다", e);
            return "설정 파일 오류: " + e.getMessage();
        }
        int recipes = compute(server);
        String msg = "연성 가치표: " + values.size() + "종 (레시피 " + recipes + "개 분석)";
        DuelMod.LOG.info("[alchemy] " + msg);
        return msg;
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("duel_alchemy.json");
    }

    private static void loadConfig() throws Exception {
        Path p = configPath();
        String defaults;
        try (InputStream in = AlchemyValues.class.getResourceAsStream("/duel_alchemy_default.json")) {
            defaults = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (!Files.exists(p)) {
            Files.createDirectories(p.getParent());
            Files.writeString(p, defaults, StandardCharsets.UTF_8);
        }
        JsonObject o = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
        int ver = o.has("version") ? o.get("version").getAsInt() : 0;
        if (ver < CONFIG_VERSION) {
            // 모드 업데이트로 기본표가 바뀌었으면 옛 설정은 백업하고 새 기본표로 교체
            Files.move(p, p.resolveSibling("duel_alchemy.json.bak"), StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(p, defaults, StandardCharsets.UTF_8);
            o = JsonParser.parseString(defaults).getAsJsonObject();
        }

        tax = o.has("tax") ? o.get("tax").getAsDouble() : 0.25;
        stoneTax = o.has("stoneTax") ? o.get("stoneTax").getAsDouble() : 0.10;
        dailyCap = o.has("dailyCap") ? o.get("dailyCap").getAsLong() : 65536;
        stoneCost = o.has("stoneCost") ? o.get("stoneCost").getAsDouble() : 4194304;
        stoneSacrifices = o.has("stoneSacrifices") ? o.get("stoneSacrifices").getAsInt() : 6;
        stoneMinLevel = o.has("stoneMinLevel") ? o.get("stoneMinLevel").getAsInt() : 100;
        stoneCooldownDays = o.has("stoneCooldownDays") ? o.get("stoneCooldownDays").getAsInt() : 30;
        stoneServerCooldownDays = o.has("stoneServerCooldownDays") ? o.get("stoneServerCooldownDays").getAsInt() : 3;
        STONE_ITEMS.clear();
        if (o.has("stoneItems")) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("stoneItems").entrySet()) {
                Identifier id = Identifier.tryParse(e.getKey());
                if (id != null && Registries.ITEM.containsId(id)) STONE_ITEMS.put(Registries.ITEM.get(id), e.getValue().getAsInt());
            }
        }
        FAMILY_RULES.clear();
        if (o.has("families")) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("families").entrySet()) {
                List<String> rules = new ArrayList<>();
                for (JsonElement r : e.getValue().getAsJsonArray()) rules.add(r.getAsString());
                FAMILY_RULES.put(e.getKey(), rules);
            }
        }

        List<String> ns = new ArrayList<>();
        if (o.has("namespaces")) for (JsonElement e : o.getAsJsonArray("namespaces")) ns.add(e.getAsString());
        namespaces = ns.isEmpty() ? List.of("minecraft", "cobblemon") : ns;

        BLOCKED.clear();
        NO_OUTPUT.clear();
        readPatterns(o.getAsJsonArray("blocked"), BLOCKED);
        readPatterns(o.getAsJsonArray("noOutput"), NO_OUTPUT);

        EXPLICIT.clear();
        if (o.has("values")) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("values").entrySet()) {
                double v = e.getValue().getAsDouble();
                if (v > 0) EXPLICIT.put(e.getKey(), v);
            }
        }
    }

    private static void readPatterns(JsonArray arr, List<Pattern> out) {
        if (arr == null) return;
        for (JsonElement e : arr) {
            String glob = e.getAsString();
            List<String> parts = new ArrayList<>();
            for (String part : glob.split("\\*", -1)) parts.add(Pattern.quote(part));
            out.add(Pattern.compile(String.join(".*", parts)));
        }
    }

    private static boolean matches(List<Pattern> ps, String id) {
        for (Pattern p : ps) if (p.matcher(id).matches()) return true;
        return false;
    }

    // ───────── 가치 계산 ─────────

    private record Rec(Item out, int count, List<Item[]> ings) {}

    private static int compute(MinecraftServer server) {
        Map<Item, Double> val = new HashMap<>();
        for (Map.Entry<String, Double> e : EXPLICIT.entrySet()) {
            Identifier id = Identifier.tryParse(e.getKey());
            if (id == null || !Registries.ITEM.containsId(id)) continue;
            Item item = Registries.ITEM.get(id);
            if (item != Items.AIR) val.put(item, e.getValue());
        }

        List<Rec> recs = new ArrayList<>();
        for (RecipeEntry<?> entry : server.getRecipeManager().values()) {
            try {
                Recipe<?> r = entry.value();
                ItemStack out = r.getResult(server.getRegistryManager());
                if (out == null || out.isEmpty() || out.getCount() <= 0) continue;
                if (!out.getComponentChanges().isEmpty()) continue; // 물약·화살 등 컴포넌트가 붙는 결과는 제외
                DefaultedList<Ingredient> ings = r.getIngredients();
                if (ings.isEmpty()) continue;
                List<Item[]> opts = new ArrayList<>();
                boolean bad = false;
                for (Ingredient ing : ings) {
                    if (ing.isEmpty()) continue;
                    ItemStack[] ms = ing.getMatchingStacks();
                    if (ms.length == 0) { bad = true; break; }
                    Item[] items = new Item[ms.length];
                    for (int i = 0; i < ms.length; i++) items[i] = ms[i].getItem();
                    opts.add(items);
                }
                if (bad || opts.isEmpty()) continue;
                recs.add(new Rec(out.getItem(), out.getCount(), opts));
            } catch (Exception ex) {
                // 특수 레시피(결과가 동적인 것)는 건너뜀
            }
        }

        // 더 싼 원가가 나오면 계속 내려가는 방식으로 수렴할 때까지 반복
        for (int pass = 0; pass < 64; pass++) {
            boolean changed = false;
            for (Rec r : recs) {
                double total = 0;
                boolean ok = true;
                for (Item[] opt : r.ings()) {
                    double best = Double.MAX_VALUE;
                    for (Item i : opt) {
                        Double v = val.get(i);
                        if (v != null && v < best) best = v;
                    }
                    if (best == Double.MAX_VALUE) { ok = false; break; }
                    total += best;
                }
                if (!ok) continue;
                double v = total / r.count();
                Double cur = val.get(r.out());
                if (cur == null || v < cur - 1e-9) {
                    val.put(r.out(), v);
                    changed = true;
                }
            }
            if (!changed) break;
        }

        Map<Item, Double> result = new HashMap<>();
        for (Map.Entry<Item, Double> e : val.entrySet()) {
            String id = id(e.getKey());
            String ns = id.substring(0, id.indexOf(':'));
            if (!namespaces.contains(ns)) continue;
            if (matches(BLOCKED, id)) continue;
            if (e.getValue() <= 0) continue;
            result.put(e.getKey(), e.getValue());
        }
        values = result;
        buildFamilies();
        return recs.size();
    }
}
