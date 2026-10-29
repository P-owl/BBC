package bus.muhyeon.duel;

import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.loot.LootPool;
import net.minecraft.loot.condition.RandomChanceLootCondition;
import net.minecraft.loot.entry.ItemEntry;
import net.minecraft.loot.provider.number.ConstantLootNumberProvider;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.gen.structure.StructureKeys;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static bus.muhyeon.duel.Vampire.P;

/**
 * 기둥 속 사내 (뱀파이어 3단계). 각성하면 뱀파이어 기술이 전부 사라지고 고른 루트의 기술 7개로 바뀐다.
 *
 * 각성 자격 (순수하게 시간이 걸리는 조건)
 *   숙련된 뱀파이어 상태로 누적 접속 40시간 · 숙련으로 밤 60번 생존 · 피 '포만'(70+) 누적 20시간 · 신부에게 정화당한 적 없음
 *   햇빛에 죽으면 '밤 생존' -5
 * 재료: 석가면 3개(쓰고 있는 것 제외) + 고대의 기둥(고대 도시 상자 1%) + 루트 촉매
 *   바람 = 예리한부리 4 + 비행 타입 포켓몬을 파티에 넣고 누적 10시간
 *   열   = 불꽃의돌 4 + 목탄 4 + 불꽃 타입 누적 10시간
 *   빛   = 빛나는돌 4 + 네더라이트 주괴 2 + 페어리 타입 누적 10시간
 *   흡수 = 서로 다른 포켓몬 30종을 한 번씩 흡혈
 * 의식: 고대 도시·사막 피라미드 안에서 밤에 고대의 기둥을 설치 → 기둥 속에서 실시간 30분 잠듦 (로그아웃해도 진행)
 *   그동안 기둥 위치가 공개되고, 기둥이 피해 300을 받거나 빛의 십자가에 맞으면 실패 → 재료 소멸, 일반 뱀파이어로, 7일 재도전 불가
 *   루트는 서버에 1명씩
 * 공통: 피 대신 스태미나 · 맨손 공격 피해의 30% 흡수 · 최대 체력 +10 · 햇빛 → 석화(3초 안에 그늘로 못 가면 죽음)
 *   루트 숙련도: 기술로 준 피해 2,000 = 100% (완전생물 조건)
 */
public final class Pillar {
    private Pillar() {}

    public static final String WIND = "wind", HEAT = "heat", LIGHT = "light", ABSORB = "absorb";
    public static final String[] ROUTES = {WIND, HEAT, LIGHT, ABSORB};

    public static final double NEED_TIME = 40 * 3600, NEED_FULL = 20 * 3600, NEED_TYPE = 10 * 3600;
    public static final int NEED_NIGHTS = 60, NEED_SPECIES = 30, NEED_MASKS = 3;
    public static final long RITUAL_MS = 30 * 60_000L;
    public static final double PILLAR_HP = 300;
    public static final double MASTERY_DAMAGE = 2000;

    private static final Identifier HP_ID = Identifier.of(DuelMod.MOD_ID, "pillar_man_hp");
    private static final Identifier BUFF_ATK = Identifier.of(DuelMod.MOD_ID, "pillar_buff_atk");
    static final DustParticleEffect STONE_DUST = new DustParticleEffect(new Vector3f(0.55f, 0.53f, 0.5f), 1.2f);

    public static String routeName(String r) {
        return switch (r) {
            case WIND -> "바람";
            case HEAT -> "열";
            case LIGHT -> "빛";
            case ABSORB -> "흡수";
            default -> "-";
        };
    }

    public static String routeName(AlchemyData.Entry e) { return routeName(e.str(P + "route")); }
    public static double mastery(AlchemyData.Entry e) { return Math.min(100, e.num(P + "mastery")); }

    static String typeOf(String route) {
        return switch (route) { case WIND -> "flying"; case HEAT -> "fire"; case LIGHT -> "fairy"; default -> ""; };
    }

    // ───────── 진행도 (숙련 뱀파이어일 때 1초마다) ─────────

    static void track(ServerPlayerEntity p, AlchemyData.Entry e, int clock) {
        e.addNum(P + "ptime", 1);
        if (Vampire.blood(e) >= 70) e.addNum(P + "fulltime", 1);
        if (clock % 400 == 0) {
            Set<String> types = new HashSet<>();
            for (Pokemon pk : PokemonDoctor.party(p)) if (pk != null) for (ElementalType t : pk.getTypes()) types.add(t.getName().toLowerCase());
            for (String r : new String[]{WIND, HEAT, LIGHT}) if (types.contains(typeOf(r))) e.addNum(P + "t_" + r, 20);
        }
    }

    static void onDrinkPokemon(ServerPlayerEntity p, AlchemyData.Entry e, PokemonEntity pe) {
        if (Vampire.stage(e) != 2) return;
        String sp = pe.getPokemon().getSpecies().getResourceIdentifier().toString();
        NbtList l = e.jd.getList(P + "species", NbtElement.STRING_TYPE);
        for (int i = 0; i < l.size(); i++) if (l.getString(i).equals(sp)) return;
        l.add(NbtString.of(sp));
        e.jd.put(P + "species", l);
    }

    static int speciesCount(AlchemyData.Entry e) { return e.jd.getList(P + "species", NbtElement.STRING_TYPE).size(); }

    static boolean qualified(AlchemyData.Entry e) {
        return e.num(P + "ptime") >= NEED_TIME && e.num(P + "pnights") >= NEED_NIGHTS && e.num(P + "fulltime") >= NEED_FULL && !e.flag("flag.vam_purified");
    }

    static String hours(double sec) { return String.format("%.1f", sec / 3600.0); }

    static String[] progressLore(ServerPlayerEntity p, AlchemyData.Entry e) {
        List<String> l = new ArrayList<>();
        l.add(JobMenu.mark(e.num(P + "ptime") >= NEED_TIME) + "숙련 상태로 접속 40시간  (" + hours(e.num(P + "ptime")) + ")");
        l.add(JobMenu.mark(e.num(P + "pnights") >= NEED_NIGHTS) + "숙련으로 밤 60번  (" + (int) e.num(P + "pnights") + ")");
        l.add(JobMenu.mark(e.num(P + "fulltime") >= NEED_FULL) + "포만(피 70+) 누적 20시간  (" + hours(e.num(P + "fulltime")) + ")");
        l.add(JobMenu.mark(!e.flag("flag.vam_purified")) + "신부에게 정화당한 적 없음");
        l.add("재료: 석가면 3 + 고대의 기둥 + 루트 촉매");
        l.add(" 바람: 예리한부리 4 + 비행 타입 동행 10시간 (" + hours(e.num(P + "t_wind")) + ")" + owner(p, WIND));
        l.add(" 열: 불꽃의돌 4 + 목탄 4 + 불꽃 타입 10시간 (" + hours(e.num(P + "t_heat")) + ")" + owner(p, HEAT));
        l.add(" 빛: 빛나는돌 4 + 네더라이트 2 + 페어리 타입 10시간 (" + hours(e.num(P + "t_light")) + ")" + owner(p, LIGHT));
        l.add(" 흡수: 서로 다른 포켓몬 30종 흡혈 (" + speciesCount(e) + ")" + owner(p, ABSORB));
        long fail = e.time(P + "pfail");
        if (fail > System.currentTimeMillis()) l.add("✘ 의식 실패 — " + ((fail - System.currentTimeMillis()) / 3_600_000L + 1) + "시간 뒤 재도전");
        return l.toArray(new String[0]);
    }

    static String owner(ServerPlayerEntity p, String route) {
        String o = global(p.getServer()).getString("route." + route);
        return o.isEmpty() ? "" : "  [주인 있음]";
    }

    static NbtCompound global(MinecraftServer s) { return AlchemyData.get(s).global; }

    // ───────── 촉매 ─────────

    record Need(Item item, int count) {}

    static List<Need> catalyst(String route) {
        List<Need> l = new ArrayList<>();
        switch (route) {
            case WIND -> l.add(new Need(PokemonDoctor.item("cobblemon:sharp_beak", Items.FEATHER), 4));
            case HEAT -> { l.add(new Need(PokemonDoctor.item("cobblemon:fire_stone", Items.BLAZE_POWDER), 4)); l.add(new Need(PokemonDoctor.item("cobblemon:charcoal_stick", Items.CHARCOAL), 4)); }
            case LIGHT -> { l.add(new Need(PokemonDoctor.item("cobblemon:shiny_stone", Items.GLOWSTONE_DUST), 4)); l.add(new Need(Items.NETHERITE_INGOT, 2)); }
            default -> { }
        }
        return l;
    }

    /** 이 루트로 의식을 시작할 수 있는가 (null = 가능, 아니면 이유) */
    static String routeProblem(ServerPlayerEntity p, AlchemyData.Entry e, String route) {
        String o = global(p.getServer()).getString("route." + route);
        if (!o.isEmpty() && !o.equals(p.getUuidAsString())) return routeName(route) + " 루트는 이미 다른 기둥 속 사내가 차지했습니다";
        if (route.equals(ABSORB)) { if (speciesCount(e) < NEED_SPECIES) return "서로 다른 포켓몬 " + NEED_SPECIES + "종을 흡혈해야 합니다"; }
        else if (e.num(P + "t_" + route) < NEED_TYPE) return typeKo(route) + " 타입 포켓몬과 10시간을 함께해야 합니다";
        for (Need n : catalyst(route)) if (Jobs.count(p, n.item()) < n.count()) return "촉매가 부족합니다: " + new ItemStack(n.item()).getName().getString() + " " + n.count() + "개";
        return null;
    }

    static String typeKo(String r) { return switch (r) { case WIND -> "비행"; case HEAT -> "불꽃"; case LIGHT -> "페어리"; default -> ""; }; }

    /** 머리에 쓴 것 말고 석가면 개수 */
    static int spareMasks(ServerPlayerEntity p) { return Jobs.count(p, DuelMod.STONE_MASK); }

    static boolean inSite(ServerWorld w, BlockPos pos) {
        return w.getStructureAccessor().getStructureContaining(pos, e -> e.matchesKey(StructureKeys.ANCIENT_CITY) || e.matchesKey(StructureKeys.DESERT_PYRAMID)).hasChildren();
    }

    // ───────── 의식 ─────────

    /** 고대의 기둥 아이템 사용 → 루트 고르기 창 */
    static final Map<UUID, BlockPos> PENDING = new HashMap<>();

    public static ActionResult tryPlace(ServerPlayerEntity p, BlockPos at) {
        AlchemyData.Entry e = Jobs.entry(p);
        if (Vampire.stage(e) != 2) { msg(p, "숙련된 뱀파이어만 기둥에 들어갈 수 있습니다"); return ActionResult.FAIL; }
        if (!qualified(e)) { msg(p, "아직 기둥에 들 자격이 없습니다 (/job 에서 진행도 확인)"); return ActionResult.FAIL; }
        if (e.time(P + "pfail") > System.currentTimeMillis()) { msg(p, "의식 실패의 상처가 아물지 않았습니다"); return ActionResult.FAIL; }
        ServerWorld w = p.getServerWorld();
        if (!w.isNight() && w.getDimension().hasSkyLight()) { msg(p, "의식은 밤에 시작해야 합니다"); return ActionResult.FAIL; }
        if (!inSite(w, at)) { msg(p, "고대 도시나 사막 피라미드 안에서만 기둥을 세울 수 있습니다"); return ActionResult.FAIL; }
        if (spareMasks(p) < NEED_MASKS) { msg(p, "석가면이 " + NEED_MASKS + "개 필요합니다 (쓰고 있는 것 제외)"); return ActionResult.FAIL; }
        PENDING.put(p.getUuid(), at);
        JobMenu.later(p, () -> RouteMenu.open(p));
        return ActionResult.SUCCESS;
    }

    static void begin(ServerPlayerEntity p, String route) {
        AlchemyData data = AlchemyData.get(p.getServer());
        AlchemyData.Entry e = data.of(p.getUuid());
        BlockPos at = PENDING.remove(p.getUuid());
        if (at == null || Vampire.stage(e) != 2) return;
        String why = routeProblem(p, e, route);
        if (why == null && spareMasks(p) < NEED_MASKS) why = "석가면이 부족합니다";
        if (why == null && Jobs.count(p, DuelMod.ANCIENT_PILLAR) < 1) why = "고대의 기둥이 없습니다";
        if (why != null) { msg(p, why); return; }
        Jobs.take(p, DuelMod.STONE_MASK, NEED_MASKS);
        Jobs.take(p, DuelMod.ANCIENT_PILLAR, 1);
        for (Need n : catalyst(route)) Jobs.take(p, n.item(), n.count());

        ServerWorld w = p.getServerWorld();
        Vec3d c = Vec3d.ofBottomCenter(at);
        String tag = "duel_pillar:" + p.getUuidAsString();
        List<Entity> parts = new ArrayList<>();
        parts.add(display(w, Blocks.CHISELED_STONE_BRICKS.getDefaultState(), c.x, c.y, c.z, 1.3f, 3.4f, 1.3f));
        parts.add(display(w, Blocks.STONE_BRICKS.getDefaultState(), c.x, c.y - 0.05, c.z, 1.6f, 0.4f, 1.6f));
        parts.add(display(w, Blocks.STONE_BRICKS.getDefaultState(), c.x, c.y + 3.2, c.z, 1.6f, 0.4f, 1.6f));
        Entity hit = EntityType.INTERACTION.create(w);
        if (hit != null) {
            NbtCompound n = new NbtCompound();
            n.putFloat("width", 1.6f);
            n.putFloat("height", 3.6f);
            n.putBoolean("response", true);
            hit.readNbt(withPos(n, hit));
            hit.refreshPositionAndAngles(c.x, c.y, c.z, 0, 0);
            w.spawnEntity(hit);
            parts.add(hit);
        }
        for (Entity x : parts) if (x != null) x.addCommandTag(tag);

        NbtCompound r = new NbtCompound();
        r.putString("dim", w.getRegistryKey().getValue().toString());
        r.putDouble("x", c.x); r.putDouble("y", c.y); r.putDouble("z", c.z);
        r.putLong("start", System.currentTimeMillis());
        r.putString("route", route);
        r.putDouble("hp", PILLAR_HP);
        r.putString("name", p.getName().getString());
        data.global.put("ritual." + p.getUuidAsString(), r);
        data.global.putString("route." + route, p.getUuidAsString());
        data.markDirty();

        CombatMode.exit(p, null);
        p.requestTeleport(c.x, c.y + 0.6, c.z);
        sleepFx(p);
        w.playSound(null, at, SoundEvents.BLOCK_DEEPSLATE_BRICKS_PLACE, SoundCategory.PLAYERS, 2.0f, 0.5f);
        w.playSound(null, at, SoundEvents.ENTITY_WARDEN_DIG, SoundCategory.PLAYERS, 1.5f, 0.6f);
        p.sendMessage(Text.literal("🗿 기둥 속에서 잠든다… 30분 동안 기둥이 버티면 깨어난다 (" + routeName(route) + " 루트)").formatted(Formatting.DARK_PURPLE, Formatting.BOLD), false);
        p.getServer().getPlayerManager().broadcast(Text.literal("🗿 고대의 기둥이 세워졌다 — " + p.getName().getString() + "이(가) 잠들었다. 위치: "
                + (int) c.x + ", " + (int) c.y + ", " + (int) c.z + " (" + dimName(w) + ") · 30분 안에 부수면 각성을 막을 수 있다").formatted(Formatting.DARK_PURPLE), false);
    }

    private static NbtCompound withPos(NbtCompound n, Entity e) {
        NbtCompound base = e.writeNbt(new NbtCompound());
        base.copyFrom(n);
        return base;
    }

    static String dimName(ServerWorld w) {
        String id = w.getRegistryKey().getValue().getPath();
        return switch (id) { case "overworld" -> "오버월드"; case "the_nether" -> "네더"; case "the_end" -> "엔드"; default -> id; };
    }

    private static Entity display(ServerWorld w, net.minecraft.block.BlockState st, double x, double y, double z, float sx, float sy, float sz) {
        NbtCompound n = new NbtCompound();
        n.putString("id", "minecraft:block_display");
        n.put("block_state", net.minecraft.nbt.NbtHelper.fromBlockState(st));
        NbtCompound tr = new NbtCompound();
        tr.put("left_rotation", floats(0, 0, 0, 1));
        tr.put("right_rotation", floats(0, 0, 0, 1));
        tr.put("translation", floats(-sx / 2, 0, -sz / 2));
        tr.put("scale", floats(sx, sy, sz));
        n.put("transformation", tr);
        n.putFloat("view_range", 3f);
        Entity e = EntityType.loadEntityWithPassengers(n, w, ent -> { ent.refreshPositionAndAngles(x, y, z, 0, 0); return ent; });
        if (e != null) w.spawnEntity(e);
        return e;
    }

    private static net.minecraft.nbt.NbtList floats(float... v) {
        net.minecraft.nbt.NbtList l = new net.minecraft.nbt.NbtList();
        for (float f : v) l.add(net.minecraft.nbt.NbtFloat.of(f));
        return l;
    }

    static void sleepFx(ServerPlayerEntity p) {
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.INVISIBILITY, 60, 0, false, false));
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 60, 9, false, false));
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 60, 4, false, false));
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.WATER_BREATHING, 60, 0, false, false));
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, 60, 0, false, false));
    }

    static NbtCompound ritual(MinecraftServer s, UUID id) {
        NbtCompound g = global(s);
        String k = "ritual." + id;
        return g.contains(k) ? g.getCompound(k) : null;
    }

    public static boolean sleeping(ServerPlayerEntity p) { return ritual(p.getServer(), p.getUuid()) != null; }

    /** 의식 중 매초 (접속해 있을 때) */
    static void ritualTick(ServerPlayerEntity p, NbtCompound r) {
        MinecraftServer s = p.getServer();
        ServerWorld w = s.getWorld(RegistryKey.of(RegistryKeys.WORLD, Identifier.of(r.getString("dim"))));
        if (w == null) return;
        Vec3d c = new Vec3d(r.getDouble("x"), r.getDouble("y"), r.getDouble("z"));
        if (p.getServerWorld() != w || p.squaredDistanceTo(c.x, c.y + 0.6, c.z) > 0.6) p.teleport(w, c.x, c.y + 0.6, c.z, p.getYaw(), p.getPitch());
        sleepFx(p);
        long left = r.getLong("start") + RITUAL_MS - System.currentTimeMillis();
        if (left <= 0) { awaken(p, r); return; }
        p.sendMessage(Text.literal("🗿 기둥 속에서 잠든 중… " + (left / 60_000 + 1) + "분 · 기둥 " + (int) r.getDouble("hp") + "/" + (int) PILLAR_HP).formatted(Formatting.DARK_PURPLE), true);
        w.spawnParticles(STONE_DUST, c.x, c.y + 1.7, c.z, 3, 0.5, 1.2, 0.5, 0);
    }

    static void awaken(ServerPlayerEntity p, NbtCompound r) {
        AlchemyData data = AlchemyData.get(p.getServer());
        AlchemyData.Entry e = data.of(p.getUuid());
        String route = r.getString("route");
        removeRitual(p.getServer(), p.getUuid(), true);
        e.num(P + "rank", 3);
        e.str(P + "route", route);
        e.num(P + "mastery", 0);
        data.global.putString("route." + route, p.getUuidAsString());
        data.markDirty();
        ensureMods(p, e);
        p.setHealth(p.getMaxHealth());
        ServerWorld w = p.getServerWorld();
        w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.CHISELED_STONE_BRICKS.getDefaultState()), p.getX(), p.getY() + 1.5, p.getZ(), 150, 0.7, 1.5, 0.7, 0.3);
        w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_WITHER_BREAK_BLOCK, SoundCategory.PLAYERS, 1.5f, 0.6f);
        w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_WARDEN_ROAR, SoundCategory.PLAYERS, 1.5f, 0.8f);
        p.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.SubtitleS2CPacket(Text.literal(routeName(route) + "의 흐름을 다루는 자").formatted(Formatting.GRAY)));
        p.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.TitleS2CPacket(Text.literal("기둥 속 사내").formatted(Formatting.DARK_PURPLE, Formatting.BOLD)));
        p.sendMessage(Text.literal("🗿 각성했다. 뱀파이어의 기술은 버리고, " + routeName(route) + " 루트의 기술을 얻었다. (/job 에서 확인)").formatted(Formatting.DARK_PURPLE, Formatting.BOLD), false);
        p.getServer().getPlayerManager().broadcast(Text.literal("🗿 기둥이 무너지고 " + p.getName().getString() + "이(가) 깨어났다 — 기둥 속 사내 (" + routeName(route) + ")").formatted(Formatting.DARK_PURPLE), false);
    }

    /** 의식 실패 (기둥 파괴·빛의 십자가) */
    static void fail(MinecraftServer s, UUID id, String why) {
        NbtCompound r = ritual(s, id);
        if (r == null) return;
        String route = r.getString("route");
        removeRitual(s, id, false);
        AlchemyData data = AlchemyData.get(s);
        if (data.global.getString("route." + route).equals(id.toString())) data.global.remove("route." + route);
        AlchemyData.Entry e = data.of(id);
        e.num(P + "rank", 1);
        e.time(P + "pfail", System.currentTimeMillis() + 7 * 86_400_000L);
        data.markDirty();
        s.getPlayerManager().broadcast(Text.literal("🗿 " + r.getString("name") + "의 기둥이 무너졌다 (" + why + ") — 각성 실패").formatted(Formatting.GOLD), false);
        ServerPlayerEntity p = s.getPlayerManager().getPlayer(id);
        if (p != null) {
            p.clearStatusEffects();
            p.sendMessage(Text.literal("기둥이 부서졌다… 힘이 빠져나가 일반 뱀파이어로 돌아갔다. (7일 뒤 재도전)").formatted(Formatting.RED), false);
        }
    }

    static void removeRitual(MinecraftServer s, UUID id, boolean crumble) {
        NbtCompound r = ritual(s, id);
        AlchemyData data = AlchemyData.get(s);
        data.global.remove("ritual." + id);
        data.markDirty();
        if (r == null) return;
        ServerWorld w = s.getWorld(RegistryKey.of(RegistryKeys.WORLD, Identifier.of(r.getString("dim"))));
        if (w == null) return;
        Vec3d c = new Vec3d(r.getDouble("x"), r.getDouble("y"), r.getDouble("z"));
        String tag = "duel_pillar:" + id;
        for (Entity x : w.getOtherEntities(null, new Box(c, c).expand(3, 5, 3), x -> x.getCommandTags().contains(tag))) x.discard();
        w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.STONE_BRICKS.getDefaultState()), c.x, c.y + 1.7, c.z, 80, 0.6, 1.5, 0.6, 0.2);
        w.playSound(null, BlockPos.ofFloored(c), SoundEvents.BLOCK_STONE_BREAK, SoundCategory.PLAYERS, 2.0f, 0.5f);
    }

    // ───────── 공통 능력 ─────────

    static void ensureMods(ServerPlayerEntity p, AlchemyData.Entry e) {
        EntityAttributeInstance hp = p.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
        if (hp == null) return;
        double want = Vampire.stage(e) >= 4 ? 20 : Vampire.stage(e) == 3 ? 10 : 0;
        EntityAttributeModifier cur = hp.getModifier(HP_ID);
        if (want == 0) { if (cur != null) hp.removeModifier(HP_ID); return; }
        if (cur == null || cur.value() != want) {
            hp.removeModifier(HP_ID);
            hp.addTemporaryModifier(new EntityAttributeModifier(HP_ID, want, EntityAttributeModifier.Operation.ADD_VALUE));
        }
    }

    static void clearMods(ServerPlayerEntity p) {
        for (var a : List.of(EntityAttributes.GENERIC_MAX_HEALTH, EntityAttributes.GENERIC_ATTACK_DAMAGE)) {
            EntityAttributeInstance i = p.getAttributeInstance(a);
            if (i != null) { i.removeModifier(HP_ID); i.removeModifier(BUFF_ATK); }
        }
        if (p.getHealth() > p.getMaxHealth()) p.setHealth(p.getMaxHealth());
    }

    /** 석화: 햇빛에 3초 */
    private static final Map<UUID, Long> PETRIFY = new HashMap<>();

    static void sunTick(ServerPlayerEntity p, AlchemyData.Entry e, boolean sun) {
        ensureMods(p, e);
        if (Ultimate.inRitual(p)) return; // 적석 의식 중엔 햇빛을 견딤
        Long since = PETRIFY.get(p.getUuid());
        if (!sun) {
            if (since != null) {
                PETRIFY.remove(p.getUuid());
                p.removeStatusEffect(StatusEffects.SLOWNESS);
                p.sendMessage(Text.literal("굳어가던 몸이 풀린다").formatted(Formatting.GRAY), true);
            }
            return;
        }
        long now = System.currentTimeMillis();
        ServerWorld w = p.getServerWorld();
        if (since == null) { PETRIFY.put(p.getUuid(), now); since = now; }
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 30, 3, false, false));
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 30, 4, false, false));
        w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.STONE.getDefaultState()), p.getX(), p.getY() + 1, p.getZ(), 20, 0.3, 0.8, 0.3, 0.05);
        long left = 3000 - (now - since);
        if (left > 0) {
            p.sendMessage(Text.literal("☀ 몸이 돌로 굳는다! " + (left / 1000 + 1) + "초 안에 그늘로!").formatted(Formatting.GOLD, Formatting.BOLD), true);
        } else {
            PETRIFY.remove(p.getUuid());
            p.removeStatusEffect(StatusEffects.RESISTANCE);
            Vampire.markSun(p);
            p.damage(p.getDamageSources().magic(), Float.MAX_VALUE);
            w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_STONE_BREAK, SoundCategory.PLAYERS, 2.0f, 0.5f);
        }
    }

    // ───────── 등록 ─────────

    public static void register() {
        LootTableEvents.MODIFY.register((key, builder, source, registries) -> {
            if (!key.getValue().toString().equals("minecraft:chests/ancient_city")) return;
            builder.pool(LootPool.builder().rolls(ConstantLootNumberProvider.create(1))
                    .with(ItemEntry.builder(DuelMod.ANCIENT_PILLAR)).conditionally(RandomChanceLootCondition.builder(0.01f)));
        });

        // 기둥 때리기
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClient || !(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;
            for (String t : entity.getCommandTags()) {
                if (!t.startsWith("duel_pillar:")) continue;
                UUID id = UUID.fromString(t.substring("duel_pillar:".length()));
                if (id.equals(sp.getUuid())) return ActionResult.FAIL;
                NbtCompound r = ritual(sp.getServer(), id);
                if (r == null) { entity.discard(); return ActionResult.FAIL; }
                double dmg = Math.max(1, sp.getAttributeValue(EntityAttributes.GENERIC_ATTACK_DAMAGE)) * Math.max(0.2f, sp.getAttackCooldownProgress(0.5f));
                if (Priest.is(Jobs.entry(sp))) dmg *= 2;
                double hp = r.getDouble("hp") - dmg;
                r.putDouble("hp", hp);
                AlchemyData.get(sp.getServer()).markDirty();
                ServerWorld w = sp.getServerWorld();
                w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.STONE_BRICKS.getDefaultState()), entity.getX(), entity.getY() + 1.5, entity.getZ(), 15, 0.4, 0.8, 0.4, 0.1);
                w.playSound(null, entity.getBlockPos(), SoundEvents.BLOCK_STONE_HIT, SoundCategory.PLAYERS, 1.2f, 0.6f);
                sp.sendMessage(Text.literal("🗿 기둥 " + (int) Math.max(0, hp) + " / " + (int) PILLAR_HP).formatted(Formatting.GRAY), true);
                if (hp <= 0) fail(sp.getServer(), id, sp.getName().getString() + "이(가) 부숨");
                return ActionResult.SUCCESS;
            }
            return ActionResult.PASS;
        });

        // 빛의 십자가가 기둥 근처에 떨어지면 실패
        Priest.HIT_BY_CROSS.add((w, at) -> {
            for (String k : new ArrayList<>(global(w.getServer()).getKeys())) {
                if (!k.startsWith("ritual.")) continue;
                NbtCompound r = global(w.getServer()).getCompound(k);
                if (!r.getString("dim").equals(w.getRegistryKey().getValue().toString())) continue;
                if (at.squaredDistanceTo(r.getDouble("x"), r.getDouble("y") + 1, r.getDouble("z")) <= 3.5 * 3.5)
                    fail(w.getServer(), UUID.fromString(k.substring(7)), "빛의 십자가");
            }
        });

        // 고아가 된 기둥 조각 정리
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            for (String t : entity.getCommandTags()) {
                if (!t.startsWith("duel_pillar:")) continue;
                try {
                    if (ritual(world.getServer(), UUID.fromString(t.substring(12))) == null) entity.discard();
                } catch (IllegalArgumentException ignored) {}
            }
        });

        // 맨손 공격 흡수 + 열·빛 루트 반응
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            if (taken > 0 && source.getAttacker() instanceof ServerPlayerEntity atk && source.getSource() == atk) {
                AlchemyData.Entry e = Jobs.entry(atk);
                if (Vampire.stage(e) >= 3 && atk.getMainHandStack().isEmpty()) {
                    atk.heal(taken * 0.3f);
                    e.addNum(P + "drink", taken * 10); // 흡수량 (완전생물 조건)
                }
                Routes.onMelee(atk, e, entity, taken);
            }
            if (entity instanceof ServerPlayerEntity victim && taken > 0) Routes.onHurt(victim, Jobs.entry(victim), source);
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayerEntity v && sleeping(v)) return false; // 잠든 동안 본체는 무적 (기둥을 부숴야 함)
            return !(entity instanceof ServerPlayerEntity v2) || Routes.allowDamage(v2, source);
        });

        ServerPlayConnectionEvents.JOIN.register((h, sender, s) -> Tasks.later(10, () -> {
            ServerPlayerEntity p = h.getPlayer();
            if (p.isDisconnected()) return;
            AlchemyData.Entry e = Jobs.entry(p);
            ensureMods(p, e);
            NbtCompound r = ritual(s, p.getUuid());
            if (r != null) ritualTick(p, r);
        }));
        CombatMode.BLOCKERS.add(Pillar::sleeping);
        Markers.PROVIDERS.add((server, out) -> {
            for (String k : global(server).getKeys()) {
                if (!k.startsWith("ritual.")) continue;
                NbtCompound r = global(server).getCompound(k);
                out.add(new MarkerPayload.Marker("pillar_" + k.substring(7), "기둥: " + r.getString("name"), "柱", r.getString("dim"),
                        (int) r.getDouble("x"), (int) r.getDouble("y"), (int) r.getDouble("z"), Markers.PURPLE));
            }
        });
    }

    /** Vampire.tick 에서 1초마다 */
    static void secondTick(ServerPlayerEntity p, AlchemyData.Entry e, int clock) {
        int s = Vampire.stage(e);
        if (s == 2) track(p, e, clock);
        NbtCompound r = ritual(p.getServer(), p.getUuid());
        if (r != null) { ritualTick(p, r); return; }
        if (s >= 3) ensureMods(p, e);
        // 의식 중인 기둥이 있다는 걸 5분마다 다시 알림
        if (clock % 6000 == 0) {
            for (String k : global(p.getServer()).getKeys()) {
                if (!k.startsWith("ritual.")) continue;
                NbtCompound x = global(p.getServer()).getCompound(k);
                long left = x.getLong("start") + RITUAL_MS - System.currentTimeMillis();
                p.sendMessage(Text.literal("🗿 " + x.getString("name") + "의 기둥: " + (int) x.getDouble("x") + ", " + (int) x.getDouble("y") + ", " + (int) x.getDouble("z")
                        + " · 각성까지 " + Math.max(0, left / 60_000) + "분 · 내구 " + (int) x.getDouble("hp")).formatted(Formatting.DARK_PURPLE), false);
            }
        }
    }

    static boolean isRitualEntity(Entity e) {
        for (String t : e.getCommandTags()) if (t.startsWith("duel_pillar:")) return true;
        return false;
    }

    // ───────── 기술 ─────────

    static JobSkills.Skill[] skills(AlchemyData.Entry e) { return Routes.list(e.str(P + "route")); }

    static boolean use(ServerPlayerEntity p, AlchemyData.Entry e, int idx) { return Routes.use(p, e, e.str(P + "route"), idx); }

    /** 기술 피해 + 루트 숙련도 */
    static boolean hit(ServerPlayerEntity p, Entity t, float dmg) {
        if (!Targets.canHit(p, t)) return false;
        boolean ok = Targets.damage(p, t, dmg);
        if (ok) {
            AlchemyData.Entry e = Jobs.entry(p);
            if (Vampire.stage(e) == 3 && mastery(e) < 100) {
                double before = mastery(e);
                e.num(P + "mastery", Math.min(100, e.num(P + "mastery") + dmg / MASTERY_DAMAGE * 100));
                if (before < 100 && mastery(e) >= 100)
                    p.sendMessage(Text.literal("🗿 " + routeName(e) + " 루트를 완전히 익혔다 — 완전생물의 문턱").formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD), false);
            }
        }
        return ok;
    }

    static void msg(ServerPlayerEntity p, String m) { p.sendMessage(Text.literal(m).formatted(Formatting.RED), false); }

    static Vec3d flat(ServerPlayerEntity p) {
        float yaw = p.getYaw() * MathHelper.RADIANS_PER_DEGREE;
        return new Vec3d(-MathHelper.sin(yaw), 0, MathHelper.cos(yaw));
    }

    // ═════════ 루트 고르기 창 ═════════
    public static class RouteMenu extends ButtonMenu {
        public static void open(ServerPlayerEntity p) {
            p.openHandledScreen(new net.minecraft.screen.SimpleNamedScreenHandlerFactory((id, inv, x) -> new RouteMenu(id, inv, p), Text.literal("기둥 각성 — 루트 선택")));
        }

        private RouteMenu(int syncId, net.minecraft.entity.player.PlayerInventory inv, ServerPlayerEntity player) {
            super(syncId, inv, player);
            AlchemyData.Entry e = Jobs.entry(player);
            Item[] icons = {Items.FEATHER, Items.BLAZE_POWDER, Items.GLOWSTONE_DUST, Items.SLIME_BALL};
            String[] desc = {"원거리 압박, 바람으로 몰아붙임", "지속 피해, 분노할수록 강해짐", "근접 최강, 출혈", "잠입·침투, 질긴 생존"};
            for (int i = 0; i < 4; i++) {
                String r = ROUTES[i];
                String why = routeProblem(player, e, r);
                button(i * 2, icon(why == null ? icons[i] : Items.GRAY_DYE, routeName(r) + " 루트", why == null ? Formatting.LIGHT_PURPLE : Formatting.DARK_GRAY,
                        desc[i], why == null ? "▶ 클릭하면 촉매를 바치고 기둥에 듭니다" : "✘ " + why));
            }
            button(8, icon(Items.BARRIER, "그만두기", Formatting.WHITE));
        }

        @Override
        protected boolean onPick(int slot) {
            if (slot == 8) { PENDING.remove(player.getUuid()); return true; }
            if (slot % 2 != 0 || slot / 2 >= 4) return false;
            String r = ROUTES[slot / 2];
            if (routeProblem(player, Jobs.entry(player), r) != null) return false;
            begin(player, r);
            return true;
        }

        @Override
        protected void onCancel() { PENDING.remove(player.getUuid()); }
    }

}
