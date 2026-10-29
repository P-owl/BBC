package bus.muhyeon.duel;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.pokedex.CaughtCount;
import com.cobblemon.mod.common.api.pokemon.stats.Stat;
import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.api.types.ElementalTypes;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * 포켓몬 박사.
 *   연구 포인트(RP): 새 종 포획 +10 · 포획 +1 · 진화 +3 · 알 부화 +2 · 이로치 포획 +50 · 일일 연구 의뢰 +30 · 다른 사람 포켓몬 연구 +5
 *   1 연구 조수   입문            마스터볼 1개(계정당 1회) · 감정: 개체값 별점
 *   2 연구원      RP 500          감정: 개체값·성격·특성 정확히 · 모든 볼 포획률 +10%
 *   3 선임 연구원 RP 2,000 + 도감 100종   성격 연구(하루 3회) · 연구 의뢰 보상에 하이퍼볼
 *   4 교수        RP 6,000 + 도감 200종   특훈 연구: 개체값 1개 31 (하루 3회, 연구 키트 1) · 64칸 이로치 감지
 *   5 박사        RP 15,000 + 도감 300종  6V 연구 (하루 1회, 연구 키트 5) · 마스터볼 주 1개 · 전설 출현 힌트
 * 감정: 웅크린 채 빈손으로 포켓몬을 우클릭 (내 포켓몬은 /job 창에서도 가능)
 */
public final class PokemonDoctor implements JobType {
    public static final PokemonDoctor INSTANCE = new PokemonDoctor();
    private PokemonDoctor() {}

    public static final String P = "doc.";
    static final String[] RANKS = {"무직", "연구 조수", "연구원", "선임 연구원", "교수", "박사"};
    static final double[] NEED_RP = {0, 0, 500, 2_000, 6_000, 15_000};
    static final int[] NEED_DEX = {0, 0, 0, 100, 200, 300};

    public static final int NATURE_PER_DAY = 3, TRAIN_PER_DAY = 3, SIXV_PER_DAY = 1;
    public static final long NATURE_PRICE = 10_000, KIT_PRICE = 20_000;
    public static final int KIT_CANDY = 5, SIXV_KITS = 5;

    // ───────── JobType ─────────

    @Override public String id() { return Jobs.DOCTOR_ID; }
    @Override public String name() { return "포켓몬 박사"; }
    @Override public String prefix() { return P; }
    @Override public Item icon() { return item("cobblemon:poke_ball", Items.BOOK); }
    @Override public Formatting color() { return Formatting.AQUA; }
    @Override public int maxRank() { return 5; }
    @Override public String rankName(int r) { return RANKS[Math.max(0, Math.min(5, r))]; }
    @Override public String[] summary() {
        return new String[]{"도감을 채우며 연구 포인트를 쌓는 직업",
                "개체값·성격을 보고, 연구로 직접 바꿉니다",
                "정점은 6V 연구"};
    }
    @Override public void openDetail(ServerPlayerEntity p) { DoctorMenu.Detail.open(p); }
    @Override public void openStatus(ServerPlayerEntity p) { DoctorMenu.Status.open(p); }

    @Override
    public void onJoin(ServerPlayerEntity p, AlchemyData.Entry e) {
        e.num(P + "rank", 1);
        if (!e.flag("flag.doc_mb")) {
            e.flag("flag.doc_mb", true);
            p.getInventory().offerOrDrop(new ItemStack(item("cobblemon:master_ball", Items.ENDER_PEARL)));
            p.sendMessage(Text.literal("✦ 입문 선물: 마스터볼 1개").formatted(Formatting.LIGHT_PURPLE), false);
        }
    }

    @Override public void adminRank(ServerPlayerEntity p, AlchemyData.Entry e, int r) {
        e.num(P + "rank", r);
        e.num(P + "rp", Math.max(e.num(P + "rp"), NEED_RP[r]));
    }

    @Override public void adminPoints(ServerPlayerEntity p, AlchemyData.Entry e, double amount) { addRp(p, e, amount, null); }

    @Override public String adminInfo(AlchemyData.Entry e) {
        return "RP " + Alchemy.fmt(e.num(P + "rp")) + " · 연구 키트 " + (int) e.num(P + "kits");
    }

    // ───────── 등급 ─────────

    public static boolean is(AlchemyData.Entry e) { return Jobs.DOCTOR_ID.equals(e.job); }

    public static int rank(AlchemyData.Entry e) { return is(e) ? (int) e.num(P + "rank") : 0; }

    /** 코블몬 도감에 '잡음'으로 등록된 종 수 */
    public static int dex(ServerPlayerEntity p) {
        try {
            Integer n = Cobblemon.INSTANCE.getPlayerDataManager().getPokedexData(p).getGlobalCalculatedValue(CaughtCount.INSTANCE);
            return n == null ? 0 : n;
        } catch (Throwable t) {
            return 0;
        }
    }

    public static void addRp(ServerPlayerEntity p, AlchemyData.Entry e, double amount, String why) {
        if (!is(e) || amount == 0) return;
        e.addNum(P + "rp", amount);
        if (why != null && amount > 0)
            p.sendMessage(Text.literal("🔬 연구 포인트 +" + (int) amount + " (" + why + ")").formatted(Formatting.AQUA), true);
        checkRank(p, e);
        AlchemyData.get(p.getServer()).markDirty();
    }

    public static void checkRank(ServerPlayerEntity p, AlchemyData.Entry e) {
        int r = rank(e);
        int dex = -1;
        while (r < 5 && e.num(P + "rp") >= NEED_RP[r + 1]) {
            if (NEED_DEX[r + 1] > 0) {
                if (dex < 0) dex = dex(p);
                if (dex < NEED_DEX[r + 1]) break;
            }
            r++;
            e.num(P + "rank", r);
            Jobs.announceRank(p, "포켓몬 박사", RANKS[r], r >= 4);
        }
    }

    // ───────── 하루 횟수 ─────────

    static long today() { return LocalDate.now().toEpochDay(); }

    /** 날짜가 바뀌었으면 하루 횟수 초기화 */
    static void rollDay(AlchemyData.Entry e) {
        long d = today();
        if (e.time(P + "day") != d) {
            e.time(P + "day", d);
            e.num(P + "nat", 0);
            e.num(P + "train", 0);
            e.num(P + "sixv", 0);
            e.num(P + "q0", 0); e.num(P + "q1", 0); e.num(P + "q2", 0);
            e.num(P + "qdone", 0);
        }
    }

    // ───────── 일일 연구 의뢰 ─────────

    /** kind: 0 = 특정 타입 포획, 1 = 아무거나 포획, 2 = 진화, 3 = 레벨 30 이상 포획 */
    public record Quest(int kind, String type, int need, String text) {}

    public static Quest[] quests(UUID id) {
        Random r = new Random(today() * 31 + id.getMostSignificantBits());
        List<ElementalType> types = new ArrayList<>(ElementalTypes.all());
        types.removeIf(t -> t.getName().equalsIgnoreCase("stellar") || t.getName().equalsIgnoreCase("shadow"));
        ElementalType t = types.get(r.nextInt(types.size()));
        String tn = t.getDisplayName().getString();
        Quest a = new Quest(0, t.getName(), 3, tn + " 타입 포켓몬 3마리 포획");
        Quest b = new Quest(1, "", 10, "아무 포켓몬 10마리 포획");
        Quest c = r.nextBoolean() ? new Quest(2, "", 2, "포켓몬 진화 2번") : new Quest(3, "", 2, "레벨 30 이상 포켓몬 2마리 포획");
        return new Quest[]{a, b, c};
    }

    private static void questProgress(ServerPlayerEntity p, AlchemyData.Entry e, int kind, Pokemon pk) {
        rollDay(e);
        Quest[] qs = quests(p.getUuid());
        int done = (int) e.num(P + "qdone");
        for (int i = 0; i < qs.length; i++) {
            Quest q = qs[i];
            if ((done & (1 << i)) != 0) continue;
            boolean match = switch (q.kind()) {
                case 0 -> kind == 0 && hasType(pk, q.type());
                case 1 -> kind == 0;
                case 2 -> kind == 2;
                case 3 -> kind == 0 && pk.getLevel() >= 30;
                default -> false;
            };
            if (!match) continue;
            double n = e.addNum(P + "q" + i, 1);
            if (n >= q.need()) {
                done |= 1 << i;
                e.num(P + "qdone", done);
                p.sendMessage(Text.literal("✦ 연구 의뢰 완료: " + q.text()).formatted(Formatting.GREEN), false);
                addRp(p, e, 30, "연구 의뢰");
                if (rank(e) >= 3) {
                    p.getInventory().offerOrDrop(new ItemStack(item("cobblemon:hyper_ball", Items.ENDER_PEARL), 3));
                    p.sendMessage(Text.literal("  보상: 하이퍼볼 3개").formatted(Formatting.GRAY), false);
                }
            }
        }
    }

    private static boolean hasType(Pokemon pk, String type) {
        for (ElementalType t : pk.getTypes()) if (t.getName().equalsIgnoreCase(type)) return true;
        return false;
    }

    // ───────── 이벤트 ─────────

    private static final Set<UUID> SEEN_SHINY = new HashSet<>();
    private static final Set<UUID> SEEN_LEGEND = new HashSet<>();
    private static int clock = 0;

    public static void register() {
        Jobs.registerType(INSTANCE);

        CobblemonEvents.POKEMON_CAPTURED.subscribe(Priority.NORMAL, ev -> {
            ServerPlayerEntity p = ev.getPlayer();
            AlchemyData.Entry e = Jobs.entry(p);
            if (!is(e)) return kotlin.Unit.INSTANCE;
            Pokemon pk = ev.getPokemon();
            double gain = 1;
            String why = "포획";
            NbtList seen = e.jd.getList(P + "species", NbtElement.STRING_TYPE);
            String sp = pk.getSpecies().getResourceIdentifier().toString();
            boolean fresh = true;
            for (int i = 0; i < seen.size(); i++) if (seen.getString(i).equals(sp)) { fresh = false; break; }
            if (fresh) {
                seen.add(NbtString.of(sp));
                e.jd.put(P + "species", seen);
                gain += 10;
                why = "새로운 종 연구";
            }
            if (pk.getShiny()) { gain += 50; why = "이로치 포획"; }
            addRp(p, e, gain, why);
            questProgress(p, e, 0, pk);
            return kotlin.Unit.INSTANCE;
        });

        CobblemonEvents.EVOLUTION_COMPLETE.subscribe(Priority.NORMAL, ev -> {
            ServerPlayerEntity p = ev.getPokemon().getOwnerPlayer();
            if (p == null) return kotlin.Unit.INSTANCE;
            AlchemyData.Entry e = Jobs.entry(p);
            if (!is(e)) return kotlin.Unit.INSTANCE;
            addRp(p, e, 3, "진화 관찰");
            questProgress(p, e, 2, ev.getPokemon());
            return kotlin.Unit.INSTANCE;
        });

        CobblemonEvents.HATCH_EGG_POST.subscribe(Priority.NORMAL, ev -> {
            ServerPlayerEntity p = ev.getPlayer();
            if (p != null && is(Jobs.entry(p))) addRp(p, Jobs.entry(p), 2, "알 부화");
            return kotlin.Unit.INSTANCE;
        });

        // 연구원부터 모든 볼 포획률 +10%
        CobblemonEvents.POKEMON_CATCH_RATE.subscribe(Priority.NORMAL, ev -> {
            if (ev.getThrower() instanceof ServerPlayerEntity p && rank(Jobs.entry(p)) >= 2) ev.setCatchRate(ev.getCatchRate() * 1.10f);
            return kotlin.Unit.INSTANCE;
        });

        // 감정: 웅크린 채 빈손으로 포켓몬 우클릭
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClient || hand != Hand.MAIN_HAND || !player.isSneaking() || !player.getMainHandStack().isEmpty()) return ActionResult.PASS;
            if (!(player instanceof ServerPlayerEntity p) || !(entity instanceof PokemonEntity pe)) return ActionResult.PASS;
            AlchemyData.Entry e = Jobs.entry(p);
            if (!is(e)) return ActionResult.PASS;
            appraise(p, pe.getPokemon(), rank(e));
            return ActionResult.SUCCESS;
        });

        // 박사: 전설·환상 출현 힌트
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (!(entity instanceof PokemonEntity pe) || pe.getOwnerUuid() != null) return;
            Pokemon pk = pe.getPokemon();
            if (!(pk.isLegendary() || pk.isMythical())) return;
            if (!SEEN_LEGEND.add(pe.getUuid())) return;
            for (ServerPlayerEntity p : world.getPlayers()) {
                if (rank(Jobs.entry(p)) < 5) continue;
                double dx = pe.getX() - p.getX(), dz = pe.getZ() - p.getZ();
                int dist = (int) (Math.round(Math.sqrt(dx * dx + dz * dz) / 100.0) * 100);
                p.sendMessage(Text.literal("📡 연구 레이더: " + dir(dx, dz) + " 약 " + Math.max(100, dist) + "블록 거리에서 강한 반응 — ")
                        .formatted(Formatting.LIGHT_PURPLE).append(pk.getSpecies().getTranslatedName().formatted(Formatting.GOLD)), false);
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(PokemonDoctor::tick);
    }

    /** 교수: 64칸 안 야생 이로치 감지 (5초마다) */
    private static void tick(MinecraftServer server) {
        if (++clock % 100 != 0) return;
        if (SEEN_SHINY.size() > 5000) SEEN_SHINY.clear();
        if (SEEN_LEGEND.size() > 5000) SEEN_LEGEND.clear();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (rank(Jobs.entry(p)) < 4) continue;
            ServerWorld w = p.getServerWorld();
            for (PokemonEntity pe : w.getEntitiesByClass(PokemonEntity.class, p.getBoundingBox().expand(64),
                    x -> x.getOwnerUuid() == null && x.getPokemon().getShiny())) {
                w.spawnParticles(p, ParticleTypes.END_ROD, true, pe.getX(), pe.getY() + pe.getHeight() + 0.5, pe.getZ(), 12, 0.2, 1.5, 0.2, 0.02);
                if (SEEN_SHINY.add(pe.getUuid())) {
                    double dx = pe.getX() - p.getX(), dz = pe.getZ() - p.getZ();
                    p.sendMessage(Text.literal("✨ 이로치 감지: ").formatted(Formatting.YELLOW)
                            .append(pe.getPokemon().getSpecies().getTranslatedName().formatted(Formatting.GOLD))
                            .append(Text.literal("  " + dir(dx, dz) + " " + (int) Math.sqrt(dx * dx + dz * dz) + "블록").formatted(Formatting.YELLOW)), false);
                    w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1.0f, 1.6f);
                }
            }
        }
    }

    static String dir(double dx, double dz) {
        double a = Math.toDegrees(Math.atan2(dx, -dz)); // 0 = 북, 90 = 동
        String[] n = {"북쪽", "북동쪽", "동쪽", "남동쪽", "남쪽", "남서쪽", "서쪽", "북서쪽"};
        return n[(int) Math.round(((a % 360) + 360) % 360 / 45.0) % 8];
    }

    // ───────── 감정 ─────────

    static final Stat[] SIX = {Stats.HP, Stats.ATTACK, Stats.DEFENCE, Stats.SPECIAL_ATTACK, Stats.SPECIAL_DEFENCE, Stats.SPEED};
    static final String[] SIX_NAMES = {"HP", "공격", "방어", "특공", "특방", "스피드"};

    public static int iv(Pokemon pk, Stat s) {
        Integer v = pk.getIvs().get(s);
        return v == null ? 0 : v;
    }

    public static int ivTotal(Pokemon pk) {
        int t = 0;
        for (Stat s : SIX) t += iv(pk, s);
        return t;
    }

    public static String stars(Pokemon pk) {
        int t = ivTotal(pk);
        return t >= 151 ? "★★★" : t >= 91 ? "★★☆" : "★☆☆";
    }

    public static void appraise(ServerPlayerEntity p, Pokemon pk, int rank) {
        MutableText head = Text.literal("🔬 감정: ").formatted(Formatting.AQUA).append(pk.getSpecies().getTranslatedName().formatted(Formatting.WHITE))
                .append(Text.literal(" Lv." + pk.getLevel() + (pk.getShiny() ? " ✨" : "")).formatted(Formatting.GRAY));
        p.sendMessage(head, false);
        if (rank < 2) {
            p.sendMessage(Text.literal("  개체값 " + stars(pk) + "  (연구원이 되면 정확한 수치를 봅니다)").formatted(Formatting.YELLOW), false);
        } else {
            StringBuilder sb = new StringBuilder("  개체값 ");
            for (int i = 0; i < SIX.length; i++) {
                int v = iv(pk, SIX[i]);
                sb.append(SIX_NAMES[i]).append(' ').append(v == 31 ? "31★" : String.valueOf(v)).append(i < 5 ? " · " : "");
            }
            sb.append("  (합 ").append(ivTotal(pk)).append("/186)");
            p.sendMessage(Text.literal(sb.toString()).formatted(Formatting.YELLOW), false);
            p.sendMessage(Text.literal("  성격 ").formatted(Formatting.GRAY).append(Text.translatable(pk.getNature().getDisplayName()).formatted(Formatting.WHITE))
                    .append(Text.literal("  ·  특성 ").formatted(Formatting.GRAY))
                    .append(Text.translatable(pk.getAbility().getDisplayName()).formatted(Formatting.WHITE)), false);
        }
        p.getServerWorld().playSound(null, p.getBlockPos(), SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, SoundCategory.PLAYERS, 0.6f, 1.4f);
    }

    // ───────── 다른 사람 포켓몬 연구 의뢰 ─────────

    /** 박사 UUID → (의뢰인 UUID, 만료 시각) */
    static final Map<UUID, UUID> REQUEST = new HashMap<>();
    static final Map<UUID, Long> REQUEST_UNTIL = new HashMap<>();

    public static String request(ServerPlayerEntity client, ServerPlayerEntity doctor) {
        if (client == doctor) return "자기 자신에게는 맡길 수 없습니다.";
        if (rank(Jobs.entry(doctor)) < 3) return doctor.getName().getString() + "님은 아직 연구를 맡을 수 있는 박사(선임 연구원 이상)가 아닙니다.";
        if (client.getServerWorld() != doctor.getServerWorld() || client.squaredDistanceTo(doctor) > 8 * 8) return "박사 8칸 안에서 맡겨야 합니다.";
        REQUEST.put(doctor.getUuid(), client.getUuid());
        REQUEST_UNTIL.put(doctor.getUuid(), System.currentTimeMillis() + 120_000);
        doctor.sendMessage(Text.literal("🔬 " + client.getName().getString() + "님이 포켓몬 연구를 맡겼습니다. /job 창의 [의뢰 받은 포켓몬]에서 2분 안에 처리하세요.").formatted(Formatting.AQUA), false);
        return null;
    }

    /** 지금 유효한 의뢰인 (없으면 null) */
    static ServerPlayerEntity client(ServerPlayerEntity doctor) {
        UUID c = REQUEST.get(doctor.getUuid());
        Long until = REQUEST_UNTIL.get(doctor.getUuid());
        if (c == null || until == null || until < System.currentTimeMillis()) return null;
        ServerPlayerEntity cp = doctor.getServer().getPlayerManager().getPlayer(c);
        if (cp == null || cp.getServerWorld() != doctor.getServerWorld() || cp.squaredDistanceTo(doctor) > 12 * 12) return null;
        return cp;
    }

    // ───────── 도구 ─────────

    static Item item(String id, Item fallback) {
        Identifier i = Identifier.tryParse(id);
        if (i == null || !Registries.ITEM.containsId(i)) return fallback;
        return Registries.ITEM.get(i);
    }

    static ItemStack icon(Pokemon pk) {
        try { return com.cobblemon.mod.common.item.PokemonItem.Companion.from(pk); }
        catch (Throwable t) { return new ItemStack(item("cobblemon:poke_ball", Items.SNOWBALL)); }
    }

    static List<Pokemon> party(ServerPlayerEntity p) {
        List<Pokemon> l = new ArrayList<>();
        var party = Cobblemon.INSTANCE.getStorage().getParty(p);
        for (int i = 0; i < 6; i++) l.add(party.get(i));
        return l;
    }
}
