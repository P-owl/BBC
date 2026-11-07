package bbc.job.vampire;

import bbc.Bbc;
import bbc.combat.Awaken;
import bbc.combat.Combat;
import bbc.combat.Fx;
import bbc.combat.Hit;
import bbc.combat.Motion;
import bbc.core.PlayerData;
import bbc.core.Tasks;
import bbc.job.JobSkills;
import bbc.job.Jobs;
import bbc.job.alchemy.AlchemyFx;
import bbc.job.alchemy.Targets;
import bbc.net.CutscenePayload;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtFloat;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 기둥 속 사내 · 열 루트 (뱀파이어 3단계, 참고: 올스타 배틀 R 1부 열의 기둥 속 사내 플레이 영상 — 기술·자세 원칙만, 이름은 우리 것).
 *
 * 열(0~5): 맞을 때마다 1 쌓임(0.5초에 한 번), 10초 동안 안 쌓이면 2초마다 1씩 빠짐. 1당 주는 피해 +4%.
 *   분노의 열(모드)·각성 중엔 5 고정. 전신 비등이 쌓인 열을 모두 터뜨림.
 * 기술 (기준 스탯 힘, 비용 = 피, 7개 중 5개 장착):
 *   ① 끓는 혈관 — 몸을 크게 틀며 팔을 휘둘러 붉은 혈관 여러 가닥이 앞을 둥글게 휘감음 (3타) · 평타 중: 손끝을 찔러 혈관 한 가닥 꽂고 끌어당김 ·
 *      공중: 혈관을 아래로 내리쳐 땅의 상대를 다운 · 모드 중: 주먹을 가슴에 꽂고 혈관으로 끓여 터뜨림(잡기) · 각성 중: 혈관 바늘 8개를 부채꼴로
 *   ② 열사 방벽 — 가슴을 내밀고 열을 뿜으며 0.7초 받아냄 (앞쪽·4.5칸 안) → 맞으면 손끝을 꽂아 혈관으로 끓이고 터뜨림
 *   ③ 끓는 피탄 — 팔을 뿌려 끓는 피 세 덩이 (모드 중 다섯), 맞으면 불
 *   ④ 울어서 식히기 — 두 손으로 얼굴을 감싸고 울다가 울부짖음: 끝까지 버티면(1초) 체력 30%·열 5·각성 게이지 +20. 그 사이 맞으면 끊김
 *   ⑤ 용암 분출 — 웅크려 손바닥을 땅에 짚으면 앞 땅이 끓어올라 띄우고 8초 동안 용암 장판 (각성 중엔 나를 따라다님)
 *   ⑥ 분노의 열 — 웅크렸다 포효하며 김이 터짐: 10초 모드 (열 5 고정, 평타에 불, ①·③ 이 바뀜)
 *   ⑦ 전신 비등 — 낮게 달려들어 무릎 → 손바닥을 대고 쌓인 열을 모두 터뜨림 (열 1당 피해 +0.5)
 * 각성 '비등점' (90틱 컷신): 웅크려 손바닥을 땅에 짚고 김을 뿜다가 몸을 펴며 혈관이 사방으로 터짐. 열 5 고정, 평타에 불 + 작은 폭발, ①은 바늘 8개, ⑤는 따라다님
 * 각성기 '증기 폭발' (250틱 컷신 ≈ 참고 영상 각성기 12.5초): 혈관이 몸에서 터져 나옴 → 상대에게 꽂힘 → 웅크려 혈관으로 끓는 피를 펌프질 →
 *   상대가 거듭 폭발 → 마지막에 상대 둘레 9칸 대폭발로 모두 띄움 (PvE 광역)
 * 햇빛: 죽는 대신 석화 (움직이지도 못하고 기술·평타도 못 씀, 햇빛 피해는 없음)
 */
public final class PillarHeat {
    private PillarHeat() {}

    public static final String ROUTE = "heat";
    public static final String EQV = "p3h";

    // ───────── 소리 (리소스팩 BBC 뱀파이어 사운드에 파일이 들어오면 남. 없으면 조용) ─────────
    static final SoundEvent S_VEINS = snd("pillar_heat.veins"), S_COUNTER = snd("pillar_heat.counter"), S_BULLET = snd("pillar_heat.bullet"),
            S_CRY = snd("pillar_heat.cry"), S_LAVA = snd("pillar_heat.lava"), S_RAGE = snd("pillar_heat.rage"), S_BOIL = snd("pillar_heat.boil"),
            S_STEW = snd("pillar_heat.stew"), S_AWAKEN = snd("pillar_heat.awaken"), S_ULT = snd("pillar_heat.ult");
    private static SoundEvent snd(String path) {
        Identifier id = Identifier.of(Bbc.MOD_ID, path);
        return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id));
    }

    // ───────── 평타 ─────────
    /** 왼 잽 → 오른 훅 → 왼 올려치기 → 오른 손등 → 낮게 쓸어 차기 (막타 선딜 6). 공중 ph_a1~3, 슬램 = 깍지 낀 두 주먹 */
    static final bbc.combat.Weapon.M1Profile M1 = new bbc.combat.Weapon.M1Profile(5, 4, 2, 1.1f, 0.3, "ph_", 6);

    static final JobSkills.Skill[] LIST = {
            new JobSkills.Skill("끓는 혈관", "혈관", 3, 15, 7, "몸을 틀며 혈관 여러 가닥으로 앞을 둥글게 휘감음 (3타, 불) · 평타 중: 혈관 한 가닥을 꽂아 끌어당김 · 공중: 아래로 내리쳐 다운 · 분노의 열 중: 주먹을 꽂아 혈관으로 끓여 터뜨림 (잡기) · 각성 중: 혈관 바늘 8개"),
            new JobSkills.Skill("열사 방벽", "방벽", 3, 20, 16, "열을 뿜으며 0.7초 받아냄 — 앞에서 가까이 때리면 손끝을 꽂아 끓이고 터뜨림"),
            new JobSkills.Skill("끓는 피탄", "피탄", 3, 15, 8, "팔을 뿌려 끓는 피 세 덩이 (분노의 열 중 다섯), 맞으면 불"),
            new JobSkills.Skill("울어서 식히기", "울음", 3, 0, 40, "얼굴을 감싸고 울다가 울부짖음 — 1초 버티면 체력 30% · 열 5 · 각성 게이지 +20 (그사이 맞으면 끊김)"),
            new JobSkills.Skill("용암 분출", "용암", 3, 25, 22, "손바닥을 땅에 짚어 앞 땅을 끓여 띄우고 8초 동안 용암 장판 (각성 중엔 나를 따라다님)"),
            new JobSkills.Skill("분노의 열", "분노", 3, 20, 30, "포효하며 김이 터짐 (주위 밀쳐냄) — 10초 동안 열 5 고정 · 평타에 불 · 끓는 혈관·피탄이 바뀜"),
            new JobSkills.Skill("전신 비등", "비등", 3, 30, 18, "낮게 달려들어 무릎 → 쌓인 열을 모두 터뜨림 (열 1당 피해 +0.5, 날려보냄)"),
    };

    // ───────── 상태 ─────────
    private static final Map<UUID, Integer> HEAT = new HashMap<>();
    private static final Map<UUID, Long> HEAT_AT = new HashMap<>();
    private static final Map<UUID, Long> MODE_UNTIL = new HashMap<>();
    private static final Set<UUID> PETRIFIED = new HashSet<>();

    public static boolean is(PlayerData.Entry e) { return VampireJob.stage(e) >= 3 && ROUTE.equals(e.str(VampireJob.P + "route")); }

    public static int heat(ServerPlayerEntity p) {
        if (Awaken.active(p) || mode(p)) return 5;
        return HEAT.getOrDefault(p.getUuid(), 0);
    }
    static void setHeat(ServerPlayerEntity p, int v) { HEAT.put(p.getUuid(), Math.max(0, Math.min(5, v))); HEAT_AT.put(p.getUuid(), Combat.now()); }
    static boolean mode(ServerPlayerEntity p) { Long u = MODE_UNTIL.get(p.getUuid()); return u != null && Combat.now() < u; }
    public static boolean petrified(ServerPlayerEntity p) { return PETRIFIED.contains(p.getUuid()); }

    /** 주는 피해 배수: 열 1당 +4% */
    public static float mul(ServerPlayerEntity p) { return 1f + 0.04f * heat(p); }

    public static String hud(ServerPlayerEntity p) {
        int h = heat(p);
        return " · 열 " + "●".repeat(h) + "○".repeat(5 - h) + (mode(p) ? " (분노)" : "") + (petrified(p) ? " · 석화" : "");
    }

    public static void register() {
        // 맞을 때마다 열이 쌓임 (0.5초에 한 번)
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            if (!(entity instanceof ServerPlayerEntity p) || taken <= 0) return;
            if (!is(Jobs.entry(p))) return;
            Long at = HEAT_AT.get(p.getUuid());
            if (at != null && Combat.now() - at < 10) return;
            int before = HEAT.getOrDefault(p.getUuid(), 0);
            setHeat(p, before + 1);
            if (before < 5) p.getServerWorld().spawnParticles(ParticleTypes.FLAME, p.getX(), p.getY() + 1.2, p.getZ(), 6, 0.3, 0.4, 0.3, 0.02);
        });
    }

    /** 매초 (VampireJob.tick 에서): 열이 식음 + 모드 오라 + 햇빛 석화 */
    static void second(ServerPlayerEntity p, boolean sunlit) {
        ServerWorld w = p.getServerWorld();
        Long at = HEAT_AT.get(p.getUuid());
        int h = HEAT.getOrDefault(p.getUuid(), 0);
        if (h > 0 && at != null && Combat.now() - at > 200 && !mode(p) && !Awaken.active(p)) { HEAT.put(p.getUuid(), h - 1); HEAT_AT.put(p.getUuid(), Combat.now() - 160); }
        if (heat(p) >= 3) w.spawnParticles(ParticleTypes.WHITE_SMOKE, p.getX(), p.getY() + 1.4, p.getZ(), heat(p), 0.25, 0.4, 0.25, 0.01);   // 몸에서 김
        if (sunlit) {
            if (PETRIFIED.add(p.getUuid())) {
                p.sendMessage(Text.literal("☀ 햇빛을 받아 몸이 돌로 굳는다…! 그늘·밤까지 움직일 수 없다").formatted(Formatting.GOLD), false);
                w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_STONE_PLACE, SoundCategory.PLAYERS, 1.2f, 0.6f);
                Motion.play(p, "stop");
            }
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 30, 9, false, false));
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.MINING_FATIGUE, 30, 4, false, false));
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.JUMP_BOOST, 30, 128, false, false));   // 점프 못 함
            Combat.move(p, new Vec3d(0, Math.min(0, p.getVelocity().y), 0));
            w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.STONE.getDefaultState()), p.getX(), p.getY() + 1, p.getZ(), 10, 0.3, 0.6, 0.3, 0.05);
            p.extinguish();
        } else if (PETRIFIED.remove(p.getUuid())) {
            p.removeStatusEffect(StatusEffects.SLOWNESS);
            p.removeStatusEffect(StatusEffects.MINING_FATIGUE);
            p.removeStatusEffect(StatusEffects.JUMP_BOOST);
            w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.STONE.getDefaultState()), p.getX(), p.getY() + 1, p.getZ(), 40, 0.4, 0.8, 0.4, 0.2);
            w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_STONE_BREAK, SoundCategory.PLAYERS, 1.2f, 0.7f);
            p.sendMessage(Text.literal("돌 껍질이 갈라지며 다시 움직인다").formatted(Formatting.GRAY), true);
        }
    }

    static void clear(UUID id) { HEAT.remove(id); HEAT_AT.remove(id); MODE_UNTIL.remove(id); PETRIFIED.remove(id); }

    // ═════════ 평타 연출 ═════════
    private static final BlockState ORANGE = Blocks.ORANGE_STAINED_GLASS.getDefaultState();
    static final DustParticleEffect HOT = new DustParticleEffect(new Vector3f(1f, 0.35f, 0.05f), 1.3f);
    static final DustParticleEffect VEIN = new DustParticleEffect(new Vector3f(0.55f, 0f, 0.02f), 1.0f);

    static void m1Fx(ServerPlayerEntity p, String motion) {
        ServerWorld w = p.getServerWorld();
        Vec3d fw = Combat.horizontal(p.getRotationVec(1f));
        var f = Combat.of(p);
        LivingEntity t = f.lastTargetAt == Combat.now() && f.lastTarget != null && w.getEntity(f.lastTarget) instanceof LivingEntity le ? le : null;
        Vec3d c = t != null ? t.getBoundingBox().getCenter() : p.getPos().add(fw.multiply(1.4)).add(0, 1.1, 0);
        boolean hot = mode(p) || Awaken.active(p);
        switch (motion) {
            case "ph_2" -> veinArc(w, p, 2.2, -70, 40, 1.15, 6);          // 훅에 혈관이 따라 휘어짐
            case "ph_4" -> veinArc(w, p, 2.4, 70, -50, 1.25, 6);
            case "ph_5" -> { if (t != null) { heatHit(w, c, fw, 1.4f, Fx.Sfx.DOGO, true); Fx.debris(w, t.getPos(), 8, 0.8); } }
            case "ph_rise" -> { if (t != null) { heatHit(w, c, new Vec3d(0, 1, 0), 1.2f, null, true); Fx.kickStreak(w, c.add(0, 0.4, 0), new Vec3d(0, 1, 0)); } }
            case "ph_slam" -> Tasks.later(3, () -> {
                if (!alive(p)) return;
                Vec3d g = p.getPos().add(Combat.horizontal(p.getRotationVec(1f)));
                Fx.ring3d(w, g.add(0, 0.15, 0), new Vec3d(0, 1, 0), 0.4, 3.0, 0.3f, 7, ORANGE);
                Fx.debris(w, g, 10, 1.0);
                w.spawnParticles(ParticleTypes.LAVA, g.x, g.y + 0.2, g.z, 6, 0.4, 0.1, 0.4, 0);
            });
            default -> { }
        }
        if (t != null && !motion.equals("ph_5") && !motion.equals("ph_rise")) heatHit(w, c, fw, 0.8f, null, false);
        if (t != null && hot) {   // 모드·각성: 평타에 불, 각성은 작은 폭발까지
            t.setOnFireFor(3);
            w.spawnParticles(ParticleTypes.FLAME, c.x, c.y, c.z, 10, 0.25, 0.35, 0.25, 0.03);
            if (Awaken.active(p)) {
                w.spawnParticles(ParticleTypes.EXPLOSION, c.x, c.y, c.z, 1, 0, 0, 0, 0);
                Fx.ring3d(w, c, fw, 0.2, 1.4, 0.12f, 5, ORANGE);
                w.playSound(null, t.getBlockPos(), SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 0.35f, 1.6f);
            }
        }
    }

    /** 열 타격: 주황 고리 + 주황·붉은 가시 + 불꽃 */
    static void heatHit(ServerWorld w, Vec3d c, Vec3d dir, float size, Fx.Sfx word, boolean big) {
        Fx.ring3d(w, c, dir, 0.3, 1.8 * size, 0.12f, 6, ORANGE);
        Fx.burst(w, c, dir, 1.1f * size, ORANGE, Fx.RED);
        Fx.impactFlash(w, c, (big ? 1.1f : 0.6f) * size);
        w.spawnParticles(ParticleTypes.FLAME, c.x, c.y, c.z, big ? 14 : 6, 0.2, 0.3, 0.2, 0.05);
        w.spawnParticles(VEIN, c.x, c.y, c.z, 4, 0.2, 0.2, 0.2, 0);
        if (word != null) Fx.word(w, c.add(0, 1.1, 0), word, big ? 1.3f : 1.1f);
    }

    // ═════════ 혈관 (블록 디스플레이 가는 붉은 선) ═════════
    private static final BlockState VEIN_BLOCK = Blocks.RED_CONCRETE.getDefaultState();
    private static final BlockState VEIN_HOT = Blocks.ORANGE_CONCRETE.getDefaultState();

    /** from → to 를 잇는 혈관 한 가닥 (가운데가 bend 만큼 옆으로 휨), life 틱 뒤 사라짐 */
    static void vein(ServerWorld w, Vec3d from, Vec3d to, Vec3d bend, int life, boolean hot) {
        int n = 7;
        Vec3d prev = from;
        List<Entity> segs = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            double s = i / (double) n;
            Vec3d q = from.lerp(to, s).add(bend.multiply(4 * s * (1 - s)));
            Entity e = segment(w, prev, q, hot ? VEIN_HOT : VEIN_BLOCK, 0.07f);
            if (e != null) segs.add(e);
            prev = q;
        }
        Tasks.later(life, () -> segs.forEach(Entity::discard));
    }

    /** 몸을 중심으로 앞을 둥글게 휘감는 혈관 채찍 (각도는 정면 기준, 도), 가닥 여러 개 */
    static void veinArc(ServerWorld w, ServerPlayerEntity p, double r, double fromDeg, double toDeg, double h, int life) {
        Vec3d fw = Combat.horizontal(p.getRotationVec(1f));
        Vec3d side = new Vec3d(-fw.z, 0, fw.x);
        Vec3d sh = p.getPos().add(0, 1.35, 0);
        for (int k = 0; k < 3; k++) {
            final int kk = k;
            Tasks.later(k, () -> {
                double a0 = Math.toRadians(fromDeg), a1 = Math.toRadians(toDeg);
                Vec3d prev = sh;
                for (int i = 1; i <= 8; i++) {
                    double s = i / 8.0;
                    double a = a0 + (a1 - a0) * s;
                    double rr = r * Math.min(1, s * 2.2) + kk * 0.25;
                    Vec3d q = p.getPos().add(fw.multiply(Math.cos(a) * rr)).add(side.multiply(Math.sin(a) * rr)).add(0, h + 0.2 * Math.sin(s * 9 + kk), 0);
                    Entity e = segment(w, prev, q, VEIN_BLOCK, 0.07f);
                    if (e != null) Tasks.later(life, e::discard);
                    prev = q;
                }
            });
        }
        w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 0.8f, 0.7f);
    }

    static Entity segment(ServerWorld w, Vec3d from, Vec3d to, BlockState state, float width) {
        Vec3d d = to.subtract(from);
        float len = (float) d.length() + 0.03f;
        if (len < 0.05f) return null;
        Quaternionf rot = new Quaternionf().rotationTo(0, 1, 0, (float) d.x, (float) d.y, (float) d.z);
        Vector3f c = new Vector3f(width / 2, 0, width / 2).rotate(new Quaternionf(rot));
        NbtCompound n = new NbtCompound();
        n.putString("id", "minecraft:block_display");
        n.put("block_state", NbtHelper.fromBlockState(state));
        NbtCompound t = new NbtCompound();
        t.put("translation", floats(-c.x, -c.y, -c.z));
        t.put("left_rotation", floats(rot.x, rot.y, rot.z, rot.w));
        t.put("scale", floats(width, len, width));
        t.put("right_rotation", floats(0, 0, 0, 1));
        n.put("transformation", t);
        n.put("brightness", AlchemyFx.bright());
        Entity e = EntityType.loadEntityWithPassengers(n, w, ent -> { ent.refreshPositionAndAngles(from.x, from.y, from.z, 0, 0); return ent; });
        if (e != null) w.spawnEntity(e);
        return e;
    }

    /** 바닥에 납작한 판 (용암 장판). teleport_duration 1 → 매 틱 옮기면 미끄러지듯 따라감 */
    static Entity plate(ServerWorld w, BlockState state, Vec3d at, float sx, float sz) {
        NbtCompound n = new NbtCompound();
        n.putString("id", "minecraft:block_display");
        n.put("block_state", NbtHelper.fromBlockState(state));
        NbtCompound t = new NbtCompound();
        t.put("translation", floats(-sx / 2, 0, -sz / 2));
        t.put("left_rotation", floats(0, 0, 0, 1));
        t.put("scale", floats(sx, 0.06f, sz));
        t.put("right_rotation", floats(0, 0, 0, 1));
        n.put("transformation", t);
        n.putInt("teleport_duration", 1);
        n.put("brightness", AlchemyFx.bright());
        Entity e = EntityType.loadEntityWithPassengers(n, w, ent -> { ent.refreshPositionAndAngles(at.x, at.y, at.z, 0, 0); return ent; });
        if (e != null) w.spawnEntity(e);
        return e;
    }

    private static NbtList floats(float... v) { NbtList l = new NbtList(); for (float f : v) l.add(NbtFloat.of(f)); return l; }

    // ═════════ 기술 ═════════
    private static final Hit LASH = new Hit(0.8f, Hit.Kind.HIT, 14, 0.15, 0, 10, false, false);
    private static final Hit LASH_END = new Hit(1.1f, Hit.Kind.KNOCK, 16, 1.0, 0.35, 15, false, false);
    private static final Hit STAB = new Hit(1.0f, Hit.Kind.HIT, 18, -0.6, 0, 10, false, false);
    private static final Hit DOWNLASH = new Hit(1.4f, Hit.Kind.DOWN, 30, 0, -1.0, 15, false, true);
    private static final Hit NEEDLE = new Hit(0.6f, Hit.Kind.HIT, 14, 0.1, 0, 6, false, false);
    private static final Hit GRAB = new Hit(0.6f, Hit.Kind.HIT, 50, 0, 0, 0, true, false).noParry();
    private static final Hit BOIL = new Hit(0.35f, Hit.Kind.HIT, 40, 0, 0, 0, true, false).noParry();
    private static final Hit BURST = new Hit(2.0f, Hit.Kind.KNOCK, 20, 1.2, 0.5, 0, true, false).noParry();
    private static final Hit COUNTER_BOIL = new Hit(1.3f, Hit.Kind.HIT, 26, 0, 0, 0, true, false).noParry();
    private static final Hit COUNTER_BURST = new Hit(1.3f, Hit.Kind.KNOCK, 20, 1.0, 0.4, 0, true, false).noParry();
    private static final Hit GLOB = new Hit(0.9f, Hit.Kind.HIT, 16, 0.3, 0, 8, false, false);
    private static final Hit ERUPT = new Hit(1.2f, Hit.Kind.AIR, 22, 0.1, 0.95, 15, false, false);
    private static final Hit LAVA_TICK = new Hit(0.4f, Hit.Kind.HIT, 8, 0, 0, 3, false, false);
    private static final Hit STEAM = new Hit(0.5f, Hit.Kind.KNOCK, 14, 0.7, 0.3, 10, false, false);
    private static final Hit KNEE = new Hit(1.0f, Hit.Kind.HIT, 30, 0, 0, 12, false, false);

    static boolean use(ServerPlayerEntity p, PlayerData.Entry e, int idx) {
        boolean air = !p.isOnGround(), chain = Combat.inM1Chain(p), aw = Awaken.active(p), md = mode(p);
        boolean ok = switch (idx) {
            case 0 -> air ? veinsAir(p) : chain ? veinsQuick(p) : aw ? needles(p) : md ? stew(p) : veins(p);
            case 1 -> counter(p);
            case 2 -> bullets(p, md || aw ? 5 : 3);
            case 3 -> cry(p);
            case 4 -> lava(p, aw);
            case 5 -> rage(p);
            case 6 -> boil(p);
            default -> false;
        };
        if (ok) {
            SoundEvent s = switch (idx) { case 0 -> !air && !chain && !aw && md ? S_STEW : S_VEINS; case 1 -> null; case 2 -> S_BULLET; case 3 -> S_CRY;
                case 4 -> S_LAVA; case 5 -> S_RAGE; case 6 -> S_BOIL; default -> null; };
            if (s != null) bbc.job.alchemy.AlchemySounds.play(p, s, 1.6f);
        }
        return ok;
    }

    // ① 끓는 혈관 (땅): 6·10·14 판정, 앞 4.5칸 부채꼴, 마지막은 날려보냄 + 불
    private static boolean veins(ServerPlayerEntity p) {
        Motion.play(p, "ph_veins");
        Combat.afterSkill(p, 12);
        ServerWorld w = p.getServerWorld();
        int[] at = {6, 11, 14};
        for (int k = 0; k < 3; k++) {
            final int kk = k;
            Tasks.later(at[k], () -> {
                if (!alive(p)) return;
                if (kk == 0) veinArc(w, p, 4.2, -80, 70, 1.2, 6);
                else if (kk == 1) veinArc(w, p, 4.2, 80, -70, 1.1, 6);
                for (LivingEntity t : Combat.targets(p, 4.6, 70, 4)) {
                    if (Combat.hit(p, t, kk == 2 ? LASH_END : LASH) == Combat.Result.MISS) continue;
                    t.setOnFireFor(2);
                    heatHit(w, t.getBoundingBox().getCenter(), Combat.horizontal(p.getRotationVec(1f)), kk == 2 ? 1.1f : 0.7f, kk == 2 ? Fx.Sfx.ZUBA : null, kk == 2);
                }
            });
        }
        return true;
    }

    // ① 평타 중: 손끝을 찔러 혈관 한 가닥을 6칸 꽂아 끌어당김 (4)
    private static boolean veinsQuick(ServerPlayerEntity p) {
        Motion.play(p, "ph_veins_q");
        Combat.afterSkill(p, 7);
        ServerWorld w = p.getServerWorld();
        Tasks.later(4, () -> {
            if (!alive(p)) return;
            LivingEntity t = Combat.targets(p, 6.0, 25, 1).stream().findFirst().orElse(null);
            Vec3d hand = front(p, 0.7, 1.35);
            Vec3d end = t != null ? t.getBoundingBox().getCenter() : hand.add(p.getRotationVector().multiply(5.5));
            vein(w, hand, end, new Vec3d(0, 0.25, 0), 6, false);
            if (t != null && Combat.hit(p, t, STAB) != Combat.Result.MISS) { t.setOnFireFor(2); heatHit(w, end, p.getRotationVector(), 0.7f, null, false); }
        });
        return true;
    }

    // ① 공중: 팔을 내리쳐 아래 앞 3.5칸에 혈관을 꽂아 다운 (6)
    private static boolean veinsAir(ServerPlayerEntity p) {
        Motion.play(p, "ph_veins_air");
        Combat.afterSkill(p, 12);
        ServerWorld w = p.getServerWorld();
        for (int i = 0; i < 8; i++) Tasks.later(i, () -> { if (alive(p) && !p.isOnGround()) Combat.move(p, new Vec3d(p.getVelocity().x * 0.4, Math.max(p.getVelocity().y, -0.05), p.getVelocity().z * 0.4)); });
        Tasks.later(6, () -> {
            if (!alive(p)) return;
            Vec3d hand = p.getPos().add(0, 1.2, 0);
            Vec3d g = p.getPos().add(Combat.horizontal(p.getRotationVec(1f)).multiply(1.8)).add(0, -2.5, 0);
            for (int k = -2; k <= 2; k++) vein(w, hand, g.add(Combat.horizontal(p.getRotationVec(1f)).crossProduct(new Vec3d(0, 1, 0)).multiply(k * 0.6)), new Vec3d(0, 0.3, 0), 7, false);
            Box b = new Box(p.getPos().add(-3.5, -4, -3.5), p.getPos().add(3.5, 0.5, 3.5));
            for (Entity x : w.getOtherEntities(p, b, x -> Targets.canHit(p, x))) {
                LivingEntity t = (LivingEntity) x;
                if (Combat.hit(p, t, DOWNLASH) != Combat.Result.MISS) { t.setOnFireFor(2); heatHit(w, t.getBoundingBox().getCenter(), new Vec3d(0, -1, 0), 1.0f, Fx.Sfx.ZUDON, true); }
            }
        });
        return true;
    }

    // ① 각성 중: 혈관 바늘 8개를 부채꼴로 (6), 각각 10칸 직선, 처음 닿은 상대
    private static boolean needles(ServerPlayerEntity p) {
        Motion.play(p, "ph_veins");
        Combat.afterSkill(p, 12);
        ServerWorld w = p.getServerWorld();
        Tasks.later(6, () -> {
            if (!alive(p)) return;
            Vec3d eye = p.getPos().add(0, 1.3, 0);
            Vec3d look = p.getRotationVector();
            Set<UUID> got = new HashSet<>();
            for (int k = 0; k < 8; k++) {
                double a = Math.toRadians(-42 + k * 12);
                Vec3d d = rotY(look, a);
                Vec3d end = eye.add(d.multiply(10));
                LivingEntity hit = null;
                double best = 10;
                for (Entity x : w.getOtherEntities(p, new Box(eye, end).expand(1), x -> Targets.canHit(p, x))) {
                    Vec3d c = x.getBoundingBox().getCenter();
                    double along = c.subtract(eye).dotProduct(d);
                    if (along < 0 || along > best) continue;
                    if (c.distanceTo(eye.add(d.multiply(along))) > x.getWidth() / 2 + 0.5) continue;
                    best = along; hit = (LivingEntity) x;
                }
                Vec3d tip = hit != null ? hit.getBoundingBox().getCenter() : end;
                vein(w, eye, tip, new Vec3d(0, 0.1 * (k % 3 - 1), 0), 7, true);
                if (hit != null && Combat.hit(p, hit, NEEDLE) != Combat.Result.MISS) {
                    hit.setOnFireFor(3);
                    if (got.add(hit.getUuid())) heatHit(w, tip, d, 0.8f, null, false);
                }
            }
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_ARROW_SHOOT, SoundCategory.PLAYERS, 1.0f, 0.6f);
        });
        return true;
    }

    // ① 분노의 열 중: 부글부글 끓는 스튜 — 주먹을 꽂아(4) 혈관으로 끓임(10·16·22·28) → 두 손바닥으로 터뜨림(34)
    private static boolean stew(ServerPlayerEntity p) {
        Motion.play(p, "ph_stew");
        Combat.afterSkill(p, 10);
        ServerWorld w = p.getServerWorld();
        Tasks.later(4, () -> {
            if (!alive(p)) return;
            LivingEntity t = Combat.targets(p, 2.8, 55, 1).stream().findFirst().orElse(null);
            if (t == null || Combat.hit(p, t, GRAB) != Combat.Result.HIT) { Motion.play(p, "stop"); return; }
            Combat.afterSkill(p, 38);
            heatHit(w, t.getBoundingBox().getCenter(), Combat.horizontal(p.getRotationVec(1f)), 1.0f, Fx.Sfx.DOGO, true);
            for (int i = 1; i <= 30; i++) Tasks.later(i, () -> {
                if (!alive(p) || !t.isAlive()) return;
                Vec3d at = front(p, 1.05, 0);
                t.requestTeleport(at.x, t.getY(), at.z);
                t.setVelocity(0, Math.min(0, t.getVelocity().y), 0);
                t.velocityModified = true;
            });
            root(p, 1, 32);
            for (int k = 0; k < 4; k++) {
                final int kk = k;
                Tasks.later(6 + k * 6, () -> {
                    if (!alive(p) || !t.isAlive()) return;
                    Combat.hit(p, t, BOIL);
                    t.setOnFireFor(3);
                    Vec3d c = t.getBoundingBox().getCenter();
                    for (int v = 0; v < 4; v++) {   // 몸을 감싸는 혈관
                        double a = Math.PI * 2 * (v + kk * 0.3) / 4;
                        vein(w, front(p, 0.6, 1.3), c.add(Math.cos(a) * 0.45, (v - 1.5) * 0.35, Math.sin(a) * 0.45), new Vec3d(0, 0.2, 0), 6, kk >= 2);
                    }
                    w.spawnParticles(ParticleTypes.LAVA, c.x, c.y, c.z, 4, 0.3, 0.4, 0.3, 0);
                    w.spawnParticles(ParticleTypes.WHITE_SMOKE, c.x, c.y + 0.5, c.z, 10, 0.3, 0.4, 0.3, 0.03);
                    w.playSound(null, t.getBlockPos(), SoundEvents.BLOCK_LAVA_POP, SoundCategory.PLAYERS, 1.0f, 0.7f + kk * 0.15f);
                });
            }
            Tasks.later(30, () -> {
                if (!alive(p) || !t.isAlive()) return;
                Combat.hit(p, t, BURST);
                explode(w, t.getBoundingBox().getCenter(), 1.4f);
                Fx.word(w, t.getPos().add(0, 2.0, 0), Fx.Sfx.DOGOOON, 1.5f);
            });
        });
        return true;
    }

    // ② 열사 방벽: 3틱부터 14틱 (앞쪽·4.5칸) 받아냄 → 손끝을 꽂아 끓이고(2) 12틱 뒤 터뜨림
    private static boolean counter(ServerPlayerEntity p) {
        Motion.play(p, "ph_counter");
        Combat.afterSkill(p, 28);
        ServerWorld w = p.getServerWorld();
        for (int i = 3; i < 30; i += 2) Tasks.later(i, () -> { if (alive(p)) w.spawnParticles(ParticleTypes.FLAME, p.getX(), p.getY() + 1.1, p.getZ(), 4, 0.35, 0.5, 0.35, 0.02); });
        Tasks.later(3, () -> {
            if (!alive(p)) return;
            Combat.counterWindow(p, 14, true, 4.5, atk -> {
                if (!alive(p)) return;
                Motion.play(p, "ph_counter_hit");
                bbc.job.alchemy.AlchemySounds.play(p, S_COUNTER, 1.6f);
                Combat.afterSkill(p, 16);
                var cf = Combat.of(p);
                cf.skillStart = Combat.now(); cf.skillMotion = "ph_counter_hit"; cf.skillBusyUntil = Combat.now() + 19;
                Combat.invuln(p, 12);
                Fx.flash(p, 0.7f);
                Vec3d to = atk.getPos().subtract(p.getPos());
                p.setYaw((float) Math.toDegrees(Math.atan2(-to.x, to.z)));
                p.setHeadYaw(p.getYaw());
                Tasks.later(2, () -> {
                    if (!alive(p) || !atk.isAlive() || atk.squaredDistanceTo(p) > 36) return;
                    if (Combat.hit(p, atk, COUNTER_BOIL) != Combat.Result.HIT) return;
                    atk.setOnFireFor(4);
                    Vec3d c = atk.getBoundingBox().getCenter();
                    for (int v = 0; v < 5; v++) vein(w, front(p, 0.6, 1.3), c.add((v - 2) * 0.15, (v % 2) * 0.3 - 0.15, 0), new Vec3d(0, 0.15 * (v - 2), 0), 12, true);
                    heatHit(w, c, Combat.horizontal(p.getRotationVec(1f)), 1.2f, Fx.Sfx.GAGIN, true);
                    Tasks.later(12, () -> { if (alive(p) && atk.isAlive()) { Combat.hit(p, atk, COUNTER_BURST); explode(w, atk.getBoundingBox().getCenter(), 1.0f); } });
                });
            });
        });
        return true;
    }

    // ③ 끓는 피탄: 6틱에 n덩이 부채꼴 (덩이당 14칸, 1.1칸/틱)
    private static boolean bullets(ServerPlayerEntity p, int n) {
        Motion.play(p, "ph_bullet");
        Combat.afterSkill(p, 10);
        ServerWorld w = p.getServerWorld();
        Tasks.later(6, () -> {
            if (!alive(p)) return;
            Vec3d from = p.getPos().add(Combat.horizontal(p.getRotationVec(1f)).multiply(0.7)).add(0, 1.35, 0);
            Vec3d look = p.getRotationVector();
            w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_LAVA_EXTINGUISH, SoundCategory.PLAYERS, 0.8f, 1.2f);
            for (int k = 0; k < n; k++) {
                double a = Math.toRadians((k - (n - 1) / 2.0) * (n > 3 ? 9 : 12));
                glob(p, w, from, rotY(look, a), 13);
            }
        });
        return true;
    }

    private static void glob(ServerPlayerEntity p, ServerWorld w, Vec3d from, Vec3d dir, int ticks) {
        Vec3d[] pos = {from};
        boolean[] done = {false};
        DustParticleEffect blob = new DustParticleEffect(new Vector3f(0.75f, 0.05f, 0.0f), 2.0f);
        for (int i = 1; i <= ticks; i++) {
            Tasks.later(i, () -> {
                if (done[0]) return;
                Vec3d next = pos[0].add(dir.multiply(1.1)).add(0, -0.02, 0);
                for (double s = 0; s < 1; s += 0.34) {
                    Vec3d q = pos[0].lerp(next, s);
                    w.spawnParticles(blob, q.x, q.y, q.z, 1, 0.03, 0.03, 0.03, 0);
                    w.spawnParticles(ParticleTypes.SMALL_FLAME, q.x, q.y, q.z, 1, 0.05, 0.05, 0.05, 0);
                }
                w.spawnParticles(ParticleTypes.FALLING_LAVA, next.x, next.y, next.z, 1, 0.05, 0.05, 0.05, 0);
                BlockPos bp = BlockPos.ofFloored(next);
                if (!w.getBlockState(bp).getCollisionShape(w, bp).isEmpty()) { done[0] = true; splash(w, next); return; }
                for (Entity x : w.getOtherEntities(p, new Box(next, next).expand(0.7), x -> Targets.canHit(p, x))) {
                    LivingEntity t = (LivingEntity) x;
                    done[0] = true;
                    if (Combat.hit(p, t, GLOB) != Combat.Result.MISS) t.setOnFireFor(3);
                    splash(w, t.getBoundingBox().getCenter());
                    return;
                }
                pos[0] = next;
            });
        }
    }

    private static void splash(ServerWorld w, Vec3d c) {
        w.spawnParticles(ParticleTypes.LAVA, c.x, c.y, c.z, 5, 0.2, 0.2, 0.2, 0);
        w.spawnParticles(VeinDust.BLOOD, c.x, c.y, c.z, 10, 0.25, 0.25, 0.25, 0);
        w.spawnParticles(ParticleTypes.WHITE_SMOKE, c.x, c.y, c.z, 6, 0.2, 0.2, 0.2, 0.02);
        w.playSound(null, BlockPos.ofFloored(c), SoundEvents.BLOCK_LAVA_POP, SoundCategory.PLAYERS, 1.0f, 1.0f);
    }

    private static final class VeinDust { static final DustParticleEffect BLOOD = new DustParticleEffect(new Vector3f(0.6f, 0.0f, 0.02f), 1.2f); }

    // ④ 울어서 식히기: 44틱. 20틱까지 안 끊기면 체력 30% · 열 5 · 각성 게이지 +20
    private static boolean cry(ServerPlayerEntity p) {
        Motion.play(p, "ph_cry");
        Combat.afterSkill(p, 40);
        long t0 = Combat.now();
        ServerWorld w = p.getServerWorld();
        for (int i = 4; i < 20; i += 3) Tasks.later(i, () -> {   // 눈물
            if (alive(p) && "ph_cry".equals(Motion.startedSince(p, t0))) w.spawnParticles(ParticleTypes.FALLING_WATER, p.getX(), p.getEyeY(), p.getZ(), 4, 0.15, 0.05, 0.15, 0);
        });
        Tasks.later(20, () -> {
            if (!alive(p) || !"ph_cry".equals(Motion.startedSince(p, t0)) || !Combat.canAct(p)) {
                if (alive(p)) JobSkills.bar(p, "울음이 끊겼다…", Formatting.GRAY);
                return;
            }
            p.heal(p.getMaxHealth() * 0.3f);
            p.extinguish();
            setHeat(p, 5);
            Awaken.add(p, 20);
            Fx.word(w, p.getPos().add(0, 2.4, 0), Fx.Sfx.BUON, 1.4f);
            w.spawnParticles(ParticleTypes.WHITE_SMOKE, p.getX(), p.getY() + 1.4, p.getZ(), 30, 0.4, 0.6, 0.4, 0.05);
            w.spawnParticles(ParticleTypes.SPLASH, p.getX(), p.getEyeY(), p.getZ(), 30, 0.5, 0.2, 0.5, 0.2);
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_GHAST_SCREAM, SoundCategory.PLAYERS, 0.6f, 1.6f);
            JobSkills.bar(p, "울고 나니 머리가 식었다 — 체력 회복 · 열 5", Formatting.GOLD);
        });
        return true;
    }

    // ⑤ 용암 분출: 8틱에 앞 땅이 끓어 띄움 → 160틱 용암 장판 (반지름 2.5, 10틱마다 불 + 작은 피해). 각성 중엔 나를 따라다님 (반지름 3)
    private static boolean lava(ServerPlayerEntity p, boolean follow) {
        Motion.play(p, "ph_lava");
        Combat.afterSkill(p, 14);
        ServerWorld w = p.getServerWorld();
        Tasks.later(8, () -> {
            if (!alive(p)) return;
            double r = follow ? 3.0 : 2.5;
            Vec3d c0 = follow ? p.getPos() : p.getPos().add(Combat.horizontal(p.getRotationVec(1f)).multiply(2.0));
            double gy = p.getY();
            for (Entity x : w.getOtherEntities(p, new Box(c0, c0).expand(r, 2, r), x -> Targets.canHit(p, x))) {
                if (Combat.hit(p, (LivingEntity) x, ERUPT) != Combat.Result.MISS) x.setOnFireFor(3);
            }
            Fx.ring3d(w, c0.add(0, 0.2, 0), new Vec3d(0, 1, 0), 0.3, r + 0.8, 0.3f, 8, ORANGE);
            Fx.debris(w, c0, 14, r * 0.5);
            Fx.word(w, c0.add(0, 2.2, 0), Fx.Sfx.ZUDON, 1.4f);
            w.spawnParticles(ParticleTypes.LAVA, c0.x, gy + 0.3, c0.z, 30, r * 0.4, 0.2, r * 0.4, 0);
            w.playSound(null, BlockPos.ofFloored(c0), SoundEvents.BLOCK_LAVA_AMBIENT, SoundCategory.PLAYERS, 1.5f, 0.6f);
            w.playSound(null, BlockPos.ofFloored(c0), SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 0.6f, 1.4f);
            Entity plate = plate(w, Blocks.MAGMA_BLOCK.getDefaultState(), new Vec3d(c0.x, gy + 0.02, c0.z), (float) (r * 2), (float) (r * 2));
            for (int i = 1; i <= 160; i++) {
                final int k = i;
                Tasks.later(i, () -> {
                    Vec3d c = follow && alive(p) ? new Vec3d(p.getX(), p.isOnGround() ? p.getY() : gy, p.getZ()) : new Vec3d(c0.x, gy, c0.z);
                    if (plate != null && follow) plate.requestTeleport(c.x, c.y + 0.02, c.z);
                    if (k % 3 == 0) w.spawnParticles(ParticleTypes.LAVA, c.x, c.y + 0.1, c.z, 1, r * 0.4, 0, r * 0.4, 0);
                    if (k % 4 == 0) w.spawnParticles(ParticleTypes.SMOKE, c.x, c.y + 0.2, c.z, 3, r * 0.4, 0.05, r * 0.4, 0.01);
                    if (k % 10 != 0 || !alive(p)) return;
                    for (Entity x : w.getOtherEntities(p, new Box(c, c).expand(r, 1.2, r), x -> Targets.canHit(p, x) && x.isOnGround())) {
                        if (x.squaredDistanceTo(c.x, x.getY(), c.z) > r * r) continue;
                        Combat.hit(p, (LivingEntity) x, LAVA_TICK);
                        x.setOnFireFor(2);
                    }
                });
            }
            Tasks.later(161, () -> { if (plate != null) plate.discard(); });
        });
        return true;
    }

    // ⑥ 분노의 열: 14틱에 포효 — 김이 터져 주위 3칸 밀쳐냄, 10초 모드
    private static boolean rage(ServerPlayerEntity p) {
        Motion.play(p, "ph_rage");
        Combat.afterSkill(p, 16);
        ServerWorld w = p.getServerWorld();
        for (int i = 2; i < 14; i += 2) Tasks.later(i, () -> { if (alive(p)) w.spawnParticles(ParticleTypes.WHITE_SMOKE, p.getX(), p.getY() + 0.8, p.getZ(), 6, 0.4, 0.5, 0.4, 0.02); });
        Tasks.later(14, () -> {
            if (!alive(p)) return;
            MODE_UNTIL.put(p.getUuid(), Combat.now() + 200);
            setHeat(p, 5);
            for (Entity x : w.getOtherEntities(p, p.getBoundingBox().expand(3), x -> Targets.canHit(p, x))) Combat.hit(p, (LivingEntity) x, STEAM);
            w.spawnParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + 1, p.getZ(), 40, 0.6, 0.8, 0.6, 0.2);
            Fx.wave(w, p.getPos().add(0, 0.1, 0), 0.4, 4.0, ORANGE, 8);
            Fx.rings(w, p.getPos().add(0, 1, 0), new Vec3d(0, 1, 0), 3f, 2);
            Fx.word(w, p.getPos().add(0, 2.6, 0), Fx.Sfx.GOGO, 1.5f);
            Fx.shakeAround(w, p.getPos(), 10, 0.5f);
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_RAVAGER_ROAR, SoundCategory.PLAYERS, 1.0f, 1.2f);
            w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.PLAYERS, 1.2f, 0.6f);
            for (int i = 0; i < 200; i += 5) Tasks.later(i, () -> {
                if (alive(p) && mode(p)) {
                    w.spawnParticles(ParticleTypes.FLAME, p.getX(), p.getY() + 1.0, p.getZ(), 3, 0.3, 0.5, 0.3, 0.01);
                    w.spawnParticles(HOT, p.getX(), p.getY() + 1.0, p.getZ(), 3, 0.3, 0.6, 0.3, 0);
                }
            });
            Tasks.later(200, () -> { if (alive(p)) JobSkills.bar(p, "분노의 열이 가라앉았다", Formatting.GRAY); });
        });
        return true;
    }

    // ⑦ 전신 비등: 3~9 낮게 달려듦 → 10 무릎 → 18 손바닥으로 쌓인 열을 모두 터뜨림 (열 1당 +0.5)
    private static boolean boil(ServerPlayerEntity p) {
        Motion.play(p, "ph_boil");
        Combat.afterSkill(p, 14);
        ServerWorld w = p.getServerWorld();
        LivingEntity[] got = {null};
        for (int i = 4; i <= 9; i++) Tasks.later(i, () -> {
            if (!alive(p) || got[0] != null) return;
            Vec3d f = Combat.horizontal(p.getRotationVec(1f));
            Combat.move(p, new Vec3d(f.x * 0.9, p.getVelocity().y, f.z * 0.9));
            w.spawnParticles(ParticleTypes.FLAME, p.getX(), p.getY() + 0.3, p.getZ(), 3, 0.2, 0.05, 0.2, 0.01);
            LivingEntity t = Combat.targets(p, 1.8, 60, 1).stream().findFirst().orElse(null);
            if (t != null) { got[0] = t; Combat.move(p, new Vec3d(0, p.getVelocity().y, 0)); }
        });
        Tasks.later(10, () -> {
            if (!alive(p)) return;
            LivingEntity t = got[0] != null ? got[0] : Combat.targets(p, 2.6, 55, 1).stream().findFirst().orElse(null);
            if (t == null || Combat.hit(p, t, KNEE) == Combat.Result.MISS) return;
            got[0] = t;
            Combat.afterSkill(p, 22);
            heatHit(w, t.getBoundingBox().getCenter(), Combat.horizontal(p.getRotationVec(1f)), 1.0f, Fx.Sfx.DOGO, true);
            root(p, 1, 10);
        });
        Tasks.later(18, () -> {
            if (!alive(p)) return;
            int h = heat(p);
            if (!Awaken.active(p) && !mode(p)) setHeat(p, 0);
            Hit hit = new Hit(1.2f + 0.5f * h, Hit.Kind.KNOCK, 20, 1.3, 0.5, 20, h >= 3, false);
            Vec3d c = got[0] != null && got[0].isAlive() ? got[0].getBoundingBox().getCenter() : front(p, 1.6, 1.1);
            for (Entity x : w.getOtherEntities(p, new Box(c, c).expand(1.6), x -> Targets.canHit(p, x))) {
                if (Combat.hit(p, (LivingEntity) x, hit) != Combat.Result.MISS) x.setOnFireFor(2 + h);
            }
            explode(w, c, 0.8f + 0.15f * h);
            Fx.word(w, c.add(0, 1.6, 0), Fx.Sfx.DOGOOON, 1.2f + 0.08f * h);
        });
        return true;
    }

    /** 끓어 터지는 폭발 (불꽃 덩어리 + 김 + 고리) */
    static void explode(ServerWorld w, Vec3d c, float size) {
        w.spawnParticles(ParticleTypes.EXPLOSION, c.x, c.y, c.z, Math.max(1, (int) (size * 2)), 0.3 * size, 0.3 * size, 0.3 * size, 0);
        w.spawnParticles(ParticleTypes.FLAME, c.x, c.y, c.z, (int) (30 * size), 0.4 * size, 0.4 * size, 0.4 * size, 0.12);
        w.spawnParticles(ParticleTypes.LAVA, c.x, c.y, c.z, (int) (8 * size), 0.3, 0.3, 0.3, 0);
        w.spawnParticles(ParticleTypes.CLOUD, c.x, c.y, c.z, (int) (14 * size), 0.5 * size, 0.5 * size, 0.5 * size, 0.08);
        Fx.ring3d(w, c, new Vec3d(0, 1, 0), 0.3, 2.2 * size, 0.2f, 7, ORANGE);
        Fx.burst(w, c, new Vec3d(0, 1, 0), 1.2f * size, ORANGE, Fx.RED);
        Fx.impactFlash(w, c, 1.2f * size);
        Fx.shakeAround(w, c, 8, 0.4f * size);
        w.playSound(null, BlockPos.ofFloored(c), SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 0.7f * size, 1.1f);
        w.playSound(null, BlockPos.ofFloored(c), SoundEvents.BLOCK_LAVA_EXTINGUISH, SoundCategory.PLAYERS, 0.8f, 0.6f);
    }

    // ═════════ 각성 '비등점' (90틱) ═════════
    static final int AWAKEN_SCENE = 90;

    static void onAwaken(ServerPlayerEntity p, PlayerData.Entry e) {
        ServerWorld w = p.getServerWorld();
        Motion.play(p, "ph_awaken");
        CutscenePayload pl = new CutscenePayload("ph_awaken", p.getId(), -1);
        if (ServerPlayNetworking.canSend(p, CutscenePayload.ID)) ServerPlayNetworking.send(p, pl);
        Combat.invuln(p, AWAKEN_SCENE);
        Combat.afterSkill(p, AWAKEN_SCENE - 6);
        Awaken.extend(p, AWAKEN_SCENE);
        VampireJob.addBlood(e, 20);
        setHeat(p, 5);
        bbc.job.alchemy.AlchemySounds.voice(p, S_AWAKEN, List.of(p));
        for (int t = 8; t < 44; t += 2) Tasks.later(t, () -> {   // 손바닥 아래 땅이 달아오르고 몸에서 김
            if (!alive(p)) return;
            Vec3d hand = front(p, 0.8, 0.05);
            w.spawnParticles(ParticleTypes.LAVA, hand.x, hand.y, hand.z, 1, 0.3, 0, 0.3, 0);
            w.spawnParticles(ParticleTypes.WHITE_SMOKE, p.getX(), p.getY() + 1.0, p.getZ(), 6, 0.35, 0.5, 0.35, 0.03);
            w.spawnParticles(HOT, p.getX(), p.getY() + 0.8, p.getZ(), 3, 0.4, 0.6, 0.4, 0);
        });
        for (int t = 8; t < 44; t += 12) Tasks.later(t, () -> { if (alive(p)) w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.PLAYERS, 1.8f, 0.7f); });
        Tasks.later(8, () -> { if (alive(p)) Fx.word(w, p.getPos().add(0, 2.4, 0), Fx.Sfx.GOGO, 1.5f); });
        Tasks.later(46, () -> {   // 몸을 펴며 혈관이 사방으로 터져 나옴
            if (!alive(p)) return;
            Vec3d back = p.getPos().add(0, 1.3, 0);
            for (int k = 0; k < 12; k++) {
                double a = Math.PI * 2 * k / 12;
                Vec3d tip = back.add(Math.cos(a) * 2.6, 0.6 + Math.sin(k * 1.7) * 1.2, Math.sin(a) * 2.6);
                vein(w, back, tip, new Vec3d(0, 0.4, 0), 30, k % 2 == 0);
            }
            w.spawnParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + 1, p.getZ(), 60, 0.8, 1.0, 0.8, 0.25);
            w.spawnParticles(ParticleTypes.FLAME, p.getX(), p.getY() + 1, p.getZ(), 50, 0.6, 0.8, 0.6, 0.2);
            Fx.wave(w, p.getPos().add(0, 0.1, 0), 0.5, 7.0, ORANGE, 12);
            Fx.rings(w, p.getPos().add(0, 1, 0), new Vec3d(0, 1, 0), 3.5f, 3);
            Fx.shakeAround(w, p.getPos(), 16, 0.9f);
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_RAVAGER_ROAR, SoundCategory.PLAYERS, 1.2f, 1.0f);
            w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.PLAYERS, 1.4f, 0.5f);
        });
        Tasks.later(AWAKEN_SCENE - 4, () -> { if (alive(p) && Awaken.active(p)) JobSkills.bar(p, "♨ 비등점 — 열 5 고정 · 평타에 불과 작은 폭발 · 혈관 바늘 8개 · 용암이 따라다님  (G = 증기 폭발)", Formatting.GOLD); });
        for (int t = AWAKEN_SCENE; t < 900 + AWAKEN_SCENE; t += 10) Tasks.later(t, () -> {
            if (alive(p) && Awaken.active(p)) {
                w.spawnParticles(ParticleTypes.WHITE_SMOKE, p.getX(), p.getY() + 1.2, p.getZ(), 3, 0.3, 0.5, 0.3, 0.02);
                w.spawnParticles(ParticleTypes.SMALL_FLAME, p.getX(), p.getY() + 1.0, p.getZ(), 2, 0.3, 0.5, 0.3, 0.01);
            }
        });
    }

    // ═════════ 각성기 '증기 폭발' (250틱) ═════════
    static final int ULT_SCENE = 250;
    private static final Hit ULT_PIERCE = new Hit(1.2f, Hit.Kind.HIT, 200, 0, 0, 0, true, false).noParry();
    private static final Hit ULT_PUMP = new Hit(0.3f, Hit.Kind.HIT, 120, 0, 0, 0, true, false).noParry();
    private static final Hit ULT_POP = new Hit(0.5f, Hit.Kind.HIT, 80, 0, 0, 0, true, false).noParry();
    private static final Hit ULT_END = new Hit(4.0f, Hit.Kind.AIR, 40, 0.6, 1.2, 0, true, true).noParry();

    static boolean ultimate(ServerPlayerEntity p, PlayerData.Entry e) {
        ServerWorld w = p.getServerWorld();
        LivingEntity t = near(p, 9);
        if (t == null) { JobSkills.bar(p, "앞에 대상이 없습니다 (9칸)", Formatting.GRAY); return false; }
        Motion.play(p, "ph_ult");
        Combat.invuln(p, ULT_SCENE);
        Combat.afterSkill(p, ULT_SCENE - 6);
        Awaken.extend(p, ULT_SCENE);
        List<ServerPlayerEntity> viewers = new ArrayList<>(List.of(p));
        if (t instanceof ServerPlayerEntity sp) viewers.add(sp);
        CutscenePayload pl = new CutscenePayload("ph_ult", p.getId(), t.getId());
        for (ServerPlayerEntity v : viewers) if (ServerPlayNetworking.canSend(v, CutscenePayload.ID)) ServerPlayNetworking.send(v, pl);
        bbc.job.alchemy.AlchemySounds.voice(p, S_ULT, viewers);
        root(p, 1, ULT_SCENE - 10);
        Vec3d hold = t.getPos();
        for (int i = 0; i < 222; i++) Tasks.later(i, () -> {   // 상대를 제자리에 묶어 둠 (꽂힌 뒤엔 혈관에 매달려 살짝 뜸)
            if (!t.isAlive()) return;
            t.requestTeleport(hold.x, t.getY() < hold.y ? hold.y : t.getY(), hold.z);
            t.setVelocity(0, Math.min(0, t.getVelocity().y), 0);
            t.velocityModified = true;
        });
        // 0~14 웅크려 힘을 모음
        for (int i = 2; i < 26; i += 3) Tasks.later(i, () -> { if (alive(p)) w.spawnParticles(ParticleTypes.WHITE_SMOKE, p.getX(), p.getY() + 1.0, p.getZ(), 5, 0.4, 0.6, 0.4, 0.03); });
        Tasks.later(2, () -> { if (alive(p)) w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.PLAYERS, 2.0f, 0.7f); });
        // 28 몸에서 혈관이 사방으로 터져 나옴
        Tasks.later(28, () -> {
            if (!alive(p)) return;
            Vec3d c = p.getPos().add(0, 1.3, 0);
            for (int k = 0; k < 16; k++) {
                double a = Math.PI * 2 * k / 16;
                Vec3d tip = c.add(Math.cos(a) * 2.8, 0.8 + (k % 4) * 0.5, Math.sin(a) * 2.8);
                vein(w, c, tip, new Vec3d(0, 0.5, 0), 26, k % 3 == 0);
            }
            w.spawnParticles(VeinDust.BLOOD, c.x, c.y, c.z, 60, 0.6, 0.6, 0.6, 0);
            Fx.word(w, c.add(0, 1.6, 0), Fx.Sfx.BAKIN, 1.5f);
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_SLIME_SQUISH, SoundCategory.PLAYERS, 1.6f, 0.5f);
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_WITHER_SHOOT, SoundCategory.PLAYERS, 0.8f, 0.6f);
        });
        // 56 혈관이 날아가 꽂힘 → 172 까지 혈관이 이어져 있음 (10틱마다 다시 그림)
        Tasks.later(56, () -> {
            if (!alive(p) || !t.isAlive()) return;
            Combat.hit(p, t, ULT_PIERCE);
            Vec3d c = t.getBoundingBox().getCenter();
            heatHit(w, c, Combat.horizontal(p.getRotationVec(1f)), 1.3f, Fx.Sfx.ZUBA, true);
            w.spawnParticles(VeinDust.BLOOD, c.x, c.y, c.z, 40, 0.3, 0.4, 0.3, 0);
            w.playSound(null, t.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.PLAYERS, 1.2f, 0.6f);
        });
        for (int t0 = 56; t0 < 172; t0 += 10) {
            final int tt = t0;
            Tasks.later(t0, () -> {
                if (!alive(p) || !t.isAlive()) return;
                Vec3d c = t.getBoundingBox().getCenter();
                boolean hot = tt >= 106;
                for (int v = 0; v < 6; v++) {
                    Vec3d from = front(p, 0.5, 1.0 + (v % 2) * 0.35).add((v - 2.5) * 0.08, 0, 0);
                    vein(w, from, c.add((v - 2.5) * 0.12, (v % 3 - 1) * 0.3, 0), new Vec3d(0, 0.25 * ((v % 3) - 1), 0), 11, hot);
                }
            });
        }
        // 106~166 펌프질: 6틱마다 끓는 피를 밀어넣음 (불꽃이 혈관을 타고 감, 상대가 끓음)
        for (int k = 0; k < 11; k++) {
            final int kk = k;
            Tasks.later(106 + k * 6, () -> {
                if (!alive(p) || !t.isAlive()) return;
                Combat.hit(p, t, ULT_PUMP);
                t.setOnFireFor(4);
                Vec3d a = front(p, 0.5, 1.1), b = t.getBoundingBox().getCenter();
                for (int s = 0; s <= 8; s++) {
                    Vec3d q = a.lerp(b, s / 8.0);
                    Tasks.later(s / 3, () -> w.spawnParticles(ParticleTypes.FLAME, q.x, q.y, q.z, 2, 0.05, 0.05, 0.05, 0.01));
                }
                w.spawnParticles(ParticleTypes.LAVA, b.x, b.y, b.z, 3, 0.3, 0.4, 0.3, 0);
                w.spawnParticles(ParticleTypes.WHITE_SMOKE, b.x, b.y + 0.4, b.z, 8, 0.3, 0.4, 0.3, 0.03);
                if (kk % 3 == 0) Fx.word(w, b.add(0, 1.2, 0), Fx.Sfx.DODODO, 1.2f);
                w.playSound(null, t.getBlockPos(), SoundEvents.BLOCK_LAVA_POP, SoundCategory.PLAYERS, 1.2f, 0.6f + kk * 0.06f);
                w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.PLAYERS, 1.0f, 1.0f + kk * 0.05f);
            });
        }
        // 172~214 상대가 거듭 폭발 (8틱마다)
        for (int k = 0; k < 6; k++) {
            final int kk = k;
            Tasks.later(172 + k * 8, () -> {
                if (!alive(p) || !t.isAlive()) return;
                Combat.hit(p, t, ULT_POP);
                Vec3d c = t.getBoundingBox().getCenter().add((kk % 3 - 1) * 0.3, (kk % 2) * 0.3, 0);
                explode(w, c, 0.8f + kk * 0.08f);
            });
        }
        // 222 마지막 대폭발: 상대 둘레 9칸, 모두 띄움
        Tasks.later(222, () -> {
            if (!alive(p)) return;
            Vec3d c = t.isAlive() ? t.getBoundingBox().getCenter() : hold.add(0, 1, 0);
            for (Entity x : w.getOtherEntities(p, new Box(c, c).expand(9), x -> Targets.canHit(p, x))) {
                if (x.squaredDistanceTo(c) > 81) continue;
                if (Combat.hit(p, (LivingEntity) x, ULT_END) != Combat.Result.MISS) x.setOnFireFor(6);
            }
            explode(w, c, 2.2f);
            w.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, c.x, c.y, c.z, 2, 0.5, 0.5, 0.5, 0);
            w.spawnParticles(ParticleTypes.CLOUD, c.x, c.y, c.z, 120, 3.0, 1.5, 3.0, 0.15);
            Fx.wave(w, c.add(0, -0.8, 0), 0.5, 9.0, ORANGE, 14);
            Fx.rings(w, c, new Vec3d(0, 1, 0), 6f, 3);
            Fx.debris(w, c.add(0, -1, 0), 30, 3.0);
            Fx.word(w, c.add(0, 2.4, 0), Fx.Sfx.DOGOOON, 2.0f);
            Fx.shakeAround(w, c, 20, 1.2f);
            w.playSound(null, BlockPos.ofFloored(c), SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 2.0f, 0.6f);
            if (t.isAlive()) VampireJob.drink(p, Jobs.entry(p), t);
        });
        return true;
    }

    // ═════════ 도움 ═════════
    static boolean alive(ServerPlayerEntity p) { return !p.isDisconnected() && p.isAlive(); }

    static Vec3d front(ServerPlayerEntity p, double d, double up) {
        return p.getPos().add(Combat.horizontal(p.getRotationVec(1f)).multiply(d)).add(0, up, 0);
    }

    static void root(ServerPlayerEntity p, int from, int ticks) {
        for (int i = from; i < from + ticks; i++) Tasks.later(i, () -> { if (alive(p) && p.isOnGround()) Combat.move(p, new Vec3d(0, p.getVelocity().y, 0)); });
    }

    static LivingEntity near(ServerPlayerEntity p, double range) {
        LivingEntity t = VampireJob.aimTarget(p, range);
        return t != null ? t : Combat.targets(p, range, 50, 1).stream().findFirst().orElse(null);
    }

    static Vec3d rotY(Vec3d v, double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new Vec3d(v.x * c - v.z * s, v.y, v.x * s + v.z * c);
    }
}
