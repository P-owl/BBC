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
import bbc.job.alchemy.AlchemySounds;
import bbc.job.alchemy.Targets;
import bbc.net.CutscenePayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
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
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 완전생물 (뱀파이어 4단계) 전투 — 참고: 올스타 배틀 R 카즈 플레이 영상 2:53~3:51 (변신 후). 기술·자세 원칙만, 이름·연출은 우리 것.
 * 태양을 넘어선 생물. 빛 칼날은 그대로 (평타 = 빛 루트 평타), 그 위에 날개 · 파문 · 생물 창조 · 변형 팔 · 포식.
 * 기술 (비용 = 피, 7개 중 5개 장착):
 *   ① 휘광 칼날 — 내리벰 → 올려벰 → 빛의 호가 6칸 날아감 · 평타 중: 한 번 · 공중: 내리벰 (다운)
 *   ② 날개 강습 — 날개를 펴고 비스듬히 날아올라 12칸 안 상대에게 두 발로 내리꽂힘 (다운) · 평타 중: 날개로 둘레를 후림 · 공중: 바로 내리꽂힘
 *   ③ 파문 역류 — 파고들어 파란 불꽃이 튀는 잽 넷 → 손바닥 장타 (마비 2초) · 평타 중: 잽 하나 · 공중: 내려찍는 발
 *   ④ 생명 창조 · 무리 — 손바닥을 땅에 꽂으면 생물 다섯이 10칸 안 상대에게 기어가 물어뜯음 (느려짐) · 평타 중: 가까이 셋 · 공중: 아래로 넷
 *   ⑤ 변형 팔 — 오른팔이 가시 돋은 붉은 팔로 7칸 늘어나 앞을 크게 후림 (날림) · 평타 중: 6칸 찔러 끌어옴 · 공중: 앞 땅을 내리침
 *   ⑥ 생명 창조 · 날개 — 날개를 펴고 날기 (켜기/끄기, 전투 중엔 1초에 피 1)
 *   ⑦ 포식 — 파고들어 움켜쥐고 몸으로 빨아들임 (여섯 번, 체력·피 회복) → 내던짐 (잡기)
 * 각성 '궁극 생명' (64틱): 금빛에 싸여 웅크렸다 '바-앙' → 날개를 활짝. 카드가 바뀜, 1초마다 체력 1 회복
 * 각성기 '생명의 정점' (120틱): 날아들어 움켜쥐고 하늘로 8칸 → 파문 칼날로 네 번 → 거꾸로 쥐고 머리부터 내리꽂음 → 땅에서 생물이 솟아 덮침
 */
public final class UltimateForm {
    private UltimateForm() {}

    public static final String EQV = "u4";

    // ───────── 목소리 (이벤트만 모드에, 소리는 리소스팩 'BBC 기둥 속 사내 · 빛 사운드' — 같은 목소리) ─────────
    static final SoundEvent S_WING = snd("wing"), S_WING_Q = snd("wing_q"), S_RIPPLE = snd("ripple"), S_RIPPLE_Q = snd("ripple_q"), S_SWARM = snd("swarm"),
            S_ARM = snd("arm"), S_ARM_Q = snd("arm_q"), S_FLY = snd("fly"), S_FEATHER = snd("feather"), S_EAT = snd("eat"), S_EAT_HIT = snd("eat_hit"),
            S_EAT_END = snd("eat_end"), S_AWAKEN = snd("awaken"), S_ULT1 = snd("ult1"), S_ULT2 = snd("ult2"), S_ULT3 = snd("ult3"),
            S_TRANSFORM1 = snd("transform1"), S_TRANSFORM2 = snd("transform2"), S_TRANSFORM3 = snd("transform3"), S_RITUAL = snd("ritual");
    private static SoundEvent snd(String path) {
        Identifier id = Identifier.of(Bbc.MOD_ID, "ultimate_being." + path);
        return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id));
    }
    static void say(ServerPlayerEntity p, SoundEvent s) { AlchemySounds.play(p, s, 1.6f); }

    // ───────── 효과음 (직접 합성, 모드에 들어 있음 — tools/make_ul_se.py) ─────────
    static final SoundEvent SE_RIPPLE = se2("ripple"), SE_RIPPLE_BIG = se2("ripple_big"), SE_FLESH = se2("flesh"), SE_SWARM = se2("swarm"), SE_WING = se2("wing"), SE_BURST = se2("burst");
    private static SoundEvent se2(String path) {
        Identifier id = Identifier.of(Bbc.MOD_ID, "ultimate_se." + path);
        return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id));
    }
    static void se(ServerWorld w, Vec3d at, SoundEvent s, float vol, float pitch) { PillarLight.se(w, at, s, vol, pitch); }

    static final JobSkills.Skill[] LIST = {
            new JobSkills.Skill("휘광 칼날", "휘광", 4, 15, 7, "내리벰 → 올려벰 → 빛의 호가 6칸 날아감 · 평타 중: 한 번 · 공중: 내리벰 (다운)"),
            new JobSkills.Skill("날개 강습", "강습", 4, 20, 10, "날개를 펴고 날아올라 12칸 안 상대에게 두 발로 내리꽂힘 (다운) · 평타 중: 날개로 둘레를 후림 · 공중: 바로 내리꽂힘"),
            new JobSkills.Skill("파문 역류", "파문", 4, 20, 10, "파고들어 파란 불꽃이 튀는 잽 넷 → 손바닥 장타 (마비) · 평타 중: 잽 하나 · 공중: 내려찍는 발"),
            new JobSkills.Skill("생명 창조 · 무리", "무리", 4, 20, 13, "손바닥을 땅에 꽂아 생물 다섯을 만들어 10칸 안 상대를 물어뜯게 함 (느려짐) · 평타 중: 가까이 셋 · 공중: 아래로 넷"),
            new JobSkills.Skill("변형 팔", "변형", 4, 20, 11, "오른팔이 가시 돋은 붉은 팔로 7칸 늘어나 앞을 크게 후림 (날림) · 평타 중: 6칸 찔러 끌어옴 · 공중: 앞 땅을 내리침 (다운)"),
            new JobSkills.Skill("생명 창조 · 날개", "날개", 4, 0, 3, "등에 날개를 만들어 자유롭게 날기 (켜기/끄기) · 전투 중엔 1초에 피 1"),
            new JobSkills.Skill("포식", "포식", 4, 20, 14, "파고들어 움켜쥐고 몸으로 빨아들임 — 여섯 번, 체력·피 회복 → 내던짐 (잡기 · 가드 무시)"),
    };
    static final JobSkills.Skill[] AWAKE = {
            new JobSkills.Skill("휘광 칼날 · 극", "휘광", 4, 15, 5, "베기 둘 + 빛의 호 둘이 10칸 날아감 · 평타 중: 한 번 · 공중: 내리벰"),
            new JobSkills.Skill("날개 강습 · 연", "강습", 4, 20, 8, "두 번 날아올라 내리꽂힘 · 평타 중: 날개 후리기 · 공중: 바로"),
            new JobSkills.Skill("파문 역류 · 폭주", "파문", 4, 20, 8, "잽 여섯 → 장타가 둘레 4칸에 파문을 터뜨림 (마비)"),
            new JobSkills.Skill("생명 창조 · 대군", "대군", 4, 20, 10, "생물 여덟 — 물면 발밑에서 검은 가시가 솟음"),
            new JobSkills.Skill("변형 팔 · 거수", "변형", 4, 20, 9, "9칸으로 늘어나 갔다가 되돌아오며 두 번 후림"),
            new JobSkills.Skill("깃털 폭풍", "깃털", 4, 20, 10, "날개를 감쌌다 펴며 깃털 열둘을 부채꼴로 쏘아 보냄 (14칸)"),
            new JobSkills.Skill("포식 · 완전", "포식", 4, 20, 12, "여덟 번 빨아들이고 회복이 커짐"),
    };
    static JobSkills.Skill[] list(ServerPlayerEntity p) { return Awaken.active(p) ? AWAKE : LIST; }

    public static void register() {}

    public static boolean is(PlayerData.Entry e) { return VampireJob.stage(e) == 4; }

    public static float mul(ServerPlayerEntity p) { return 1.1f; }

    public static String hud(ServerPlayerEntity p) {
        return (p.getAbilities().flying ? " · 비행" : "") + (UltimateLife.immortalReady(p) ? "" : " · 불멸 재충전");
    }

    // ═════════ 연출 도구 ═════════
    static final DustParticleEffect SPARK_BLUE = new DustParticleEffect(new Vector3f(0.35f, 0.7f, 1f), 0.9f);
    static final DustParticleEffect SPARK_GOLD = new DustParticleEffect(new Vector3f(1f, 0.85f, 0.25f), 0.9f);
    static final DustParticleEffect FLESH = new DustParticleEffect(new Vector3f(0.75f, 0.12f, 0.1f), 1.3f);
    static final DustParticleEffect FLESH_DARK = new DustParticleEffect(new Vector3f(0.4f, 0.05f, 0.05f), 1.1f);
    static final DustParticleEffect BONE = new DustParticleEffect(new Vector3f(0.92f, 0.86f, 0.72f), 0.8f);
    static final DustParticleEffect FEATHER = new DustParticleEffect(new Vector3f(0.55f, 0.3f, 0.75f), 1.0f);
    static final DustParticleEffect FEATHER_PINK = new DustParticleEffect(new Vector3f(0.95f, 0.6f, 0.75f), 0.9f);
    static final DustParticleEffect CRITTER = new DustParticleEffect(new Vector3f(0.25f, 0.18f, 0.12f), 1.0f);
    static final DustParticleEffect GOLD_AURA = new DustParticleEffect(new Vector3f(1f, 0.78f, 0.3f), 1.4f);
    private static final BlockState GLASS = Fx.WHITE, BLUE = Blocks.LIGHT_BLUE_STAINED_GLASS.getDefaultState(), PURPLE = Blocks.PURPLE_STAINED_GLASS.getDefaultState(),
            REDG = Blocks.RED_STAINED_GLASS.getDefaultState();
    private static final BlockStateParticleEffect THORN = new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.BLACK_CONCRETE.getDefaultState());

    /** 파문 불꽃: 파랑·금빛 지직 + 전기 불꽃 */
    static void spark(ServerWorld w, Vec3d c, float size) {
        w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y, c.z, (int) (10 * size), 0.25 * size, 0.25 * size, 0.25 * size, 0.4);
        w.spawnParticles(SPARK_BLUE, c.x, c.y, c.z, (int) (8 * size), 0.3 * size, 0.3 * size, 0.3 * size, 0);
        w.spawnParticles(SPARK_GOLD, c.x, c.y, c.z, (int) (5 * size), 0.25 * size, 0.25 * size, 0.25 * size, 0);
        for (int i = 0; i < 3; i++) {   // 갈라지는 번개 줄기
            Vec3d d = new Vec3d(w.random.nextGaussian(), w.random.nextGaussian() * 0.6, w.random.nextGaussian()).normalize().multiply(0.9 * size);
            Vec3d a = c, b = c;
            for (int s = 0; s < 4; s++) {
                b = a.add(d.multiply(0.25)).add(w.random.nextGaussian() * 0.08, w.random.nextGaussian() * 0.08, w.random.nextGaussian() * 0.08);
                w.spawnParticles(s % 2 == 0 ? SPARK_BLUE : SPARK_GOLD, b.x, b.y, b.z, 1, 0, 0, 0, 0);
                a = b;
            }
        }
    }

    /** 파문이 터짐 (맞은 자리): 불꽃 + 섬광 + 고리 */
    static void rippleHit(ServerWorld w, Vec3d c, float size, Fx.Sfx word) {
        spark(w, c, size);
        Fx.impactFlash(w, c, 0.8f * size);
        Fx.ring3d(w, c, new Vec3d(0, 1, 0), 0.2, 1.2 * size, 0.15f, 5, BLUE);
        if (word != null) Fx.word(w, c.add(0, 1.0, 0), word, 1.0f + 0.25f * size);
        se(w, c, SE_RIPPLE, 0.9f + 0.3f * size, 1.0f);
    }

    /** 깃털이 흩날림 */
    static void feathers(ServerWorld w, Vec3d c, int n, double spread) {
        w.spawnParticles(FEATHER, c.x, c.y, c.z, n, spread, spread * 0.6, spread, 0);
        w.spawnParticles(FEATHER_PINK, c.x, c.y, c.z, n / 2, spread, spread * 0.6, spread, 0);
    }

    /** 날개 펼침 (모델은 클라이언트, 여기선 날갯짓 바람 · 깃털 · 소리) */
    static void wingBurst(ServerWorld w, ServerPlayerEntity p) {
        Vec3d back = p.getPos().add(Combat.horizontal(p.getRotationVec(1f)).multiply(-0.5)).add(0, 1.3, 0);
        feathers(w, back, 14, 0.9);
        w.spawnParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + 0.1, p.getZ(), 12, 1.0, 0.05, 1.0, 0.08);
        se(w, p.getPos(), SE_WING, 1.4f, 0.9f);
        w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_ENDER_DRAGON_FLAP, SoundCategory.PLAYERS, 0.8f, 1.3f);
        Motion.play(p, "#wing:on");
    }

    static List<LivingEntity> around(ServerPlayerEntity p, Vec3d c, double r) {
        List<LivingEntity> out = new ArrayList<>();
        for (Entity x : p.getServerWorld().getOtherEntities(p, new Box(c, c).expand(r), x -> Targets.canHit(p, x)))
            if (x instanceof LivingEntity le && le.squaredDistanceTo(c) <= r * r) out.add(le);
        return out;
    }

    static List<LivingEntity> nearest(ServerPlayerEntity p, double r, int max) {
        List<LivingEntity> l = around(p, p.getPos().add(0, 1, 0), r);
        l.sort((a, b) -> Double.compare(a.squaredDistanceTo(p), b.squaredDistanceTo(p)));
        return l.size() > max ? new ArrayList<>(l.subList(0, max)) : l;
    }

    // ═════════ 매초 ═════════
    static void second(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        PlayerData.Entry e = bbc.job.Jobs.entry(p);
        boolean fly = p.getAbilities().allowFlying && !p.isCreative() && !p.isSpectator();
        if (fly && bbc.combat.CombatMode.active(p)) {   // 전투 중 비행: 1초에 피 1
            VampireJob.addBlood(e, -1);
            if (VampireJob.blood(e) <= 0) { UltimateLife.setFlight(p, false); JobSkills.bar(p, "피가 다해 날개가 사그라졌다", Formatting.DARK_RED); }
        }
        if (fly || Awaken.active(p)) Motion.play(p, "#wing:on");
        if (Awaken.active(p)) {   // 궁극 생명: 금빛 기운 + 체력 회복
            w.spawnParticles(GOLD_AURA, p.getX(), p.getY() + 1, p.getZ(), 6, 0.35, 0.6, 0.35, 0);
            if (p.getHealth() < p.getMaxHealth()) p.heal(1);
        }
    }

    // ═════════ 기술 ═════════
    static boolean use(ServerPlayerEntity p, PlayerData.Entry e, int idx) {
        boolean air = !p.isOnGround(), chain = Combat.inM1Chain(p), aw = Awaken.active(p);
        boolean ok = switch (idx) {
            case 0 -> air ? bladeAir(p) : chain ? bladeQ(p) : blade(p, aw);
            case 1 -> air ? wingDive(p, aw, true) : chain ? wingSweep(p) : wingDive(p, aw, false);
            case 2 -> air ? rippleAir(p) : chain ? rippleQ(p) : ripple(p, aw);
            case 3 -> swarm(p, air ? 4 : chain ? 3 : aw ? 8 : 5, air ? "ul_swarm_air" : chain ? "ul_swarm_q" : "ul_swarm", chain ? 5.0 : 10.0, aw);
            case 4 -> air ? armAir(p) : chain ? armPoke(p) : arm(p, aw);
            case 5 -> aw ? featherStorm(p) : flight(p);
            case 6 -> air ? eatAir(p, aw) : chain ? eatNear(p, aw) : eat(p, aw);
            default -> false;
        };
        if (ok) {
            SoundEvent s = switch (idx) {
                case 0 -> air ? PillarLight.S_AIR : chain ? PillarLight.S_CUT_Q : PillarLight.S_CUT;
                case 1 -> chain ? S_WING_Q : S_WING;
                case 2 -> chain ? S_RIPPLE_Q : air ? S_RIPPLE_Q : S_RIPPLE;
                case 3 -> S_SWARM;
                case 4 -> chain ? S_ARM_Q : S_ARM;
                case 5 -> aw ? S_FEATHER : null;
                case 6 -> S_EAT;
                default -> null; };
            if (s != null) say(p, s);
        }
        return ok;
    }

    // ① 휘광 칼날: 5 내리벰 → 11 올려벰 → 12 빛의 호가 날아감 (각성: 10칸 둘)
    private static final Hit CUT1 = new Hit(0.9f, Hit.Kind.HIT, 18, 0.15, 0, 8, false, false);
    private static final Hit CUT2 = new Hit(1.1f, Hit.Kind.KNOCK, 22, 0.9, 0.3, 10, false, false);
    private static final Hit WAVE = new Hit(0.8f, Hit.Kind.HIT, 16, 0.4, 0, 6, false, false);
    private static final Hit CUT_DOWN = new Hit(1.0f, Hit.Kind.DOWN, 20, 0, -0.7, 8, false, true);

    private static boolean blade(ServerPlayerEntity p, boolean aw) {
        Motion.play(p, "pl_cut");
        Combat.afterSkill(p, 20);
        ServerWorld w = p.getServerWorld();
        Tasks.later(4, () -> { if (alive(p)) Combat.move(p, Combat.horizontal(p.getRotationVec(1f)).multiply(0.5)); });
        int[] at = {5, 11};
        for (int k = 0; k < 2; k++) {
            final int kk = k;
            Tasks.later(at[k], () -> {
                if (!alive(p)) return;
                PillarLight.blade(w, p, 4.0, kk == 0 ? 50 : -40, kk == 1);
                for (LivingEntity t : Combat.targets(p, 4.0, 70, 5))
                    if (Combat.hit(p, t, kk == 0 ? CUT1 : CUT2) != Combat.Result.MISS) PillarLight.cutHit(w, t.getBoundingBox().getCenter(), kk == 0 ? 0.9f : 1.2f, kk == 1 ? Fx.Sfx.ZAN : null);
            });
        }
        Tasks.later(12, () -> { if (alive(p)) flyingArc(p, aw ? 10 : 6, 0); });
        if (aw) Tasks.later(15, () -> { if (alive(p)) flyingArc(p, 10, 0.6); });
        return true;
    }

    /** 빛의 호가 앞으로 날아감 (1틱 1칸) */
    private static void flyingArc(ServerPlayerEntity p, int len, double up) {
        ServerWorld w = p.getServerWorld();
        Vec3d fw = Combat.horizontal(p.getRotationVec(1f)), rt = new Vec3d(-fw.z, 0, fw.x), o = p.getPos().add(0, 1.0 + up, 0);
        Set<Integer> got = new HashSet<>();
        PillarLight.se(w, o, PillarLight.SE_SWISH, 1.2f, 0.85f);
        for (int i = 1; i <= len; i++) {
            final int k = i;
            Tasks.later(i, () -> {
                if (!alive(p)) return;
                Vec3d c = o.add(fw.multiply(k));
                for (int j = -5; j <= 5; j++) { Vec3d q = c.add(rt.multiply(j * 0.25)).add(fw.multiply(-Math.abs(j) * 0.08));
                    w.spawnParticles(j % 2 == 0 ? PillarLight.LIGHT_GOLD : PillarLight.LIGHT, q.x, q.y + j * 0.05, q.z, 1, 0, 0, 0, 0); }
                if (k % 3 == 0) w.spawnParticles(PillarLight.PRISM[k % 4], c.x, c.y, c.z, 2, 0.4, 0.1, 0.4, 0);
                for (LivingEntity t : around(p, c, 1.4)) if (got.add(t.getId()) && Combat.hit(p, t, WAVE) != Combat.Result.MISS) PillarLight.cutHit(w, t.getBoundingBox().getCenter(), 0.8f, null);
            });
        }
    }

    private static boolean bladeQ(ServerPlayerEntity p) {
        Motion.play(p, "pl_cut_q");
        Combat.afterSkill(p, 8);
        ServerWorld w = p.getServerWorld();
        Tasks.later(4, () -> {
            if (!alive(p)) return;
            PillarLight.blade(w, p, 3.4, 50, false);
            for (LivingEntity t : Combat.targets(p, 3.4, 60, 3)) if (Combat.hit(p, t, CUT1) != Combat.Result.MISS) PillarLight.cutHit(w, t.getBoundingBox().getCenter(), 1f, null);
        });
        return true;
    }

    private static boolean bladeAir(ServerPlayerEntity p) {
        Motion.play(p, "pl_cut_air");
        Combat.afterSkill(p, 12);
        ServerWorld w = p.getServerWorld();
        Tasks.later(5, () -> {
            if (!alive(p)) return;
            PillarLight.blade(w, p, 3.2, 80, false);
            for (LivingEntity t : around(p, front(p, 1.4, -0.8), 2.8)) if (Combat.hit(p, t, CUT_DOWN) != Combat.Result.MISS) PillarLight.cutHit(w, t.getBoundingBox().getCenter(), 1f, Fx.Sfx.ZUBA);
        });
        return true;
    }

    // ② 날개 강습: 3 웅크려 날개를 펌 → 6~12 비스듬히 날아오름 → 15~18 상대에게 내리꽂힘 (다운) · 각성: 한 번 더
    private static final Hit DIVE_KICK = new Hit(1.6f, Hit.Kind.DOWN, 30, 0.4, -0.9, 14, true, true);
    private static final Hit WING_SWEEP = new Hit(1.0f, Hit.Kind.KNOCK, 20, 1.3, 0.4, 10, false, false);

    private static boolean wingDive(ServerPlayerEntity p, boolean aw, boolean air) {
        Motion.play(p, air ? "ul_wing_air" : "ul_wing");
        int rise = air ? 0 : 6, dive = air ? 2 : 15;
        Combat.afterSkill(p, air ? 14 : 26);
        Combat.invuln(p, dive + 4);
        ServerWorld w = p.getServerWorld();
        LivingEntity tgt = PillarHeat.near(p, 12);
        if (!air) Tasks.later(3, () -> { if (alive(p)) wingBurst(w, p); });
        else wingBurst(w, p);
        if (!air) for (int i = rise; i < 13; i++) Tasks.later(i, () -> {   // 비스듬히 날아오름
            if (!alive(p)) return;
            Vec3d f = Combat.horizontal(p.getRotationVec(1f));
            Combat.move(p, new Vec3d(f.x * 0.35, 0.55, f.z * 0.35));
            if (w.random.nextInt(2) == 0) feathers(w, p.getPos().add(0, 1, 0), 3, 0.4);
        });
        if (!air) Tasks.later(13, () -> { if (alive(p)) Combat.move(p, new Vec3d(0, 0.05, 0)); });
        boolean[] done = {false};
        for (int i = dive; i < dive + 7; i++) {
            Tasks.later(i, () -> {
                if (!alive(p) || done[0]) return;
                Vec3d to = tgt != null && tgt.isAlive() && tgt.squaredDistanceTo(p) < 16 * 16 ? tgt.getPos() : p.getPos().add(Combat.horizontal(p.getRotationVec(1f)).multiply(3)).add(0, -3, 0);
                Vec3d d = to.subtract(p.getPos());
                double len = d.length();
                Vec3d v = len > 1e-3 ? d.multiply(Math.min(1.8, len) / len) : Vec3d.ZERO;
                Combat.move(p, new Vec3d(v.x, Math.min(-0.4, v.y), v.z));
                feathers(w, p.getPos().add(0, 1, 0), 2, 0.3);
                PillarLight.ray(w, p.getPos().add(0, 1, 0), p.getPos().add(0, 1, 0).subtract(v));
                if (p.isOnGround() || (tgt != null && tgt.squaredDistanceTo(p) < 2.6 * 2.6)) {
                    done[0] = true;
                    landImpact(p, w);
                }
            });
        }
        Tasks.later(dive + 7, () -> { if (alive(p) && !done[0]) landImpact(p, w); });
        if (aw && !air) Tasks.later(dive + 9, () -> { if (alive(p)) { Combat.move(p, new Vec3d(0, 0.9, 0)); Tasks.later(4, () -> { if (alive(p)) wingDive(p, false, true); }); } });
        return true;
    }

    private static void landImpact(ServerPlayerEntity p, ServerWorld w) {
        Vec3d g = p.getPos();
        Fx.ring3d(w, g.add(0, 0.15, 0), new Vec3d(0, 1, 0), 0.4, 3.0, 0.3f, 8, PURPLE);
        Fx.debris(w, g, 12, 1.0);
        feathers(w, g.add(0, 0.8, 0), 18, 1.2);
        Fx.word(w, g.add(0, 1.8, 0), Fx.Sfx.ZUDON, 1.4f);
        Fx.shakeAround(w, g, 8, 0.6f);
        w.playSound(null, BlockPos.ofFloored(g), SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 0.7f, 1.4f);
        for (LivingEntity t : around(p, g.add(0, 0.6, 0), 2.8)) if (Combat.hit(p, t, DIVE_KICK) != Combat.Result.MISS) { Fx.impactFlash(w, t.getBoundingBox().getCenter(), 1.2f); feathers(w, t.getBoundingBox().getCenter(), 8, 0.4); }
    }

    private static boolean wingSweep(ServerPlayerEntity p) {
        Motion.play(p, "ul_wing_q");
        Combat.afterSkill(p, 10);
        ServerWorld w = p.getServerWorld();
        Motion.play(p, "#wing:on");
        Tasks.later(4, () -> {
            if (!alive(p)) return;
            wingBurst(w, p);
            Fx.ring3d(w, p.getPos().add(0, 1.2, 0), new Vec3d(0, 1, 0), 0.6, 3.6, 0.35f, 6, PURPLE);
            for (LivingEntity t : around(p, p.getPos().add(0, 1, 0), 3.6)) if (Combat.hit(p, t, WING_SWEEP) != Combat.Result.MISS) { Fx.impactFlash(w, t.getBoundingBox().getCenter(), 0.9f); feathers(w, t.getBoundingBox().getCenter(), 6, 0.3); }
        });
        return true;
    }

    // ③ 파문 역류: 2 파고듦 → 4·7·10·13 잽 (파란 불꽃) → 18 손바닥 장타 (마비 2초) · 각성: 잽 여섯 + 장타가 둘레 4칸에 터짐
    private static final Hit JAB = new Hit(0.45f, Hit.Kind.HIT, 14, 0.05, 0, 4, false, false);
    private static final Hit PALM = new Hit(1.5f, Hit.Kind.KNOCK, 40, 1.0, 0.3, 14, true, false);
    private static final Hit PALM_RING = new Hit(0.8f, Hit.Kind.HIT, 36, 0.5, 0, 8, false, false);
    private static final Hit JAB_Q = new Hit(0.8f, Hit.Kind.HIT, 22, 0.15, 0, 8, false, false);
    private static final Hit RIPPLE_DOWN = new Hit(1.1f, Hit.Kind.DOWN, 26, 0, -0.9, 10, false, true);

    private static boolean ripple(ServerPlayerEntity p, boolean aw) {
        Motion.play(p, "ul_ripple");
        Combat.afterSkill(p, 24);
        ServerWorld w = p.getServerWorld();
        Tasks.later(2, () -> { if (alive(p)) Combat.move(p, Combat.horizontal(p.getRotationVec(1f)).multiply(0.7)); });
        int[] jabs = aw ? new int[]{4, 6, 8, 10, 12, 14} : new int[]{4, 7, 10, 13};
        for (int k = 0; k < jabs.length; k++) {
            final int kk = k;
            Tasks.later(jabs[k], () -> {
                if (!alive(p)) return;
                Vec3d hand = front(p, 1.1, 1.3);
                spark(w, hand, 0.5f);
                if (kk % 2 == 0) se(w, hand, SE_RIPPLE, 0.7f, 1.2f);
                for (LivingEntity t : Combat.targets(p, 2.9, 50, 2)) if (Combat.hit(p, t, JAB) != Combat.Result.MISS) rippleHit(w, t.getBoundingBox().getCenter(), 0.7f, kk == 0 ? Fx.Sfx.BAKI : null);
            });
        }
        Tasks.later(18, () -> {
            if (!alive(p)) return;
            Vec3d hand = front(p, 1.4, 1.3);
            spark(w, hand, 1.4f);
            se(w, hand, SE_RIPPLE_BIG, 1.6f, 1.0f);
            Fx.ring3d(w, hand, Combat.horizontal(p.getRotationVec(1f)), 0.2, 1.8, 0.2f, 6, BLUE);
            for (LivingEntity t : Combat.targets(p, 3.2, 50, 3)) if (Combat.hit(p, t, PALM) != Combat.Result.MISS) {
                rippleHit(w, t.getBoundingBox().getCenter(), 1.5f, Fx.Sfx.DOGON);
                paralyze(t, 40);
            }
            if (aw) {
                Fx.ring3d(w, p.getPos().add(0, 0.3, 0), new Vec3d(0, 1, 0), 0.5, 4.0, 0.3f, 8, BLUE);
                for (int i = 0; i < 16; i++) { double a = Math.PI * 2 * i / 16; spark(w, p.getPos().add(Math.cos(a) * 3, 0.6, Math.sin(a) * 3), 0.4f); }
                for (LivingEntity t : around(p, p.getPos().add(0, 1, 0), 4.0)) if (Combat.hit(p, t, PALM_RING) != Combat.Result.MISS) paralyze(t, 30);
            }
        });
        return true;
    }

    /** 파문 마비: 느려짐 + 파란 불꽃이 몸에서 튐 */
    static void paralyze(LivingEntity t, int ticks) {
        t.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, ticks, 3, false, true));
        t.addStatusEffect(new StatusEffectInstance(StatusEffects.MINING_FATIGUE, ticks, 1, false, true));
        if (!(t.getWorld() instanceof ServerWorld w)) return;
        for (int i = 4; i < ticks; i += 6) Tasks.later(i, () -> { if (t.isAlive()) { Vec3d c = t.getBoundingBox().getCenter(); w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y, c.z, 4, 0.3, 0.5, 0.3, 0.2); w.spawnParticles(SPARK_BLUE, c.x, c.y, c.z, 2, 0.3, 0.5, 0.3, 0); } });
    }

    private static boolean rippleQ(ServerPlayerEntity p) {
        Motion.play(p, "ul_ripple_q");
        Combat.afterSkill(p, 8);
        ServerWorld w = p.getServerWorld();
        Tasks.later(4, () -> {
            if (!alive(p)) return;
            spark(w, front(p, 1.2, 1.3), 0.7f);
            for (LivingEntity t : Combat.targets(p, 3.0, 50, 2)) if (Combat.hit(p, t, JAB_Q) != Combat.Result.MISS) rippleHit(w, t.getBoundingBox().getCenter(), 1f, Fx.Sfx.BAKI);
        });
        return true;
    }

    private static boolean rippleAir(ServerPlayerEntity p) {
        Motion.play(p, "ul_ripple_air");
        Combat.afterSkill(p, 12);
        ServerWorld w = p.getServerWorld();
        Tasks.later(2, () -> { if (alive(p)) spark(w, p.getPos().add(0, 2.2, 0), 0.6f); });
        Tasks.later(5, () -> {
            if (!alive(p)) return;
            for (LivingEntity t : around(p, front(p, 1.2, -0.6), 2.4)) if (Combat.hit(p, t, RIPPLE_DOWN) != Combat.Result.MISS) { rippleHit(w, t.getBoundingBox().getCenter(), 1.2f, Fx.Sfx.DOGO); paralyze(t, 20); }
        });
        return true;
    }

    // ④ 생명 창조 · 무리: 6 손바닥을 땅에 꽂음 → 생물이 땅을 기어 상대에게 (1틱 0.8칸, 최대 20틱) → 붙으면 4틱마다 다섯 번 물어뜯음
    private static final Hit BITE = new Hit(0.3f, Hit.Kind.HIT, 10, 0, 0, 3, false, false);
    private static final Hit THORN_HIT = new Hit(0.9f, Hit.Kind.AIR, 20, 0.2, 0.8, 8, false, false);

    private static boolean swarm(ServerPlayerEntity p, int n, String mo, double range, boolean aw) {
        Motion.play(p, mo);
        boolean air = mo.endsWith("_air"), q = mo.endsWith("_q");
        int at = q || air ? 4 : 6;
        Combat.afterSkill(p, q ? 8 : air ? 12 : 18);
        ServerWorld w = p.getServerWorld();
        Tasks.later(at, () -> {
            if (!alive(p)) return;
            Vec3d o = air ? p.getPos() : front(p, 0.8, 0);
            Vec3d g = groundAt(w, o);
            w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, w.getBlockState(BlockPos.ofFloored(g).down())), g.x, g.y + 0.1, g.z, 20, 0.5, 0.05, 0.5, 0.1);
            w.spawnParticles(FLESH, g.x, g.y + 0.2, g.z, 12, 0.4, 0.1, 0.4, 0);
            se(w, g, SE_FLESH, 1.2f, 0.9f);
            se(w, g, SE_SWARM, 1.4f, 1.0f);
            Fx.ring3d(w, g.add(0, 0.1, 0), new Vec3d(0, 1, 0), 0.3, 1.8, 0.2f, 6, REDG);
            List<LivingEntity> ts = nearest(p, range, n);
            Vec3d fw = Combat.horizontal(p.getRotationVec(1f));
            for (int i = 0; i < n; i++) {
                LivingEntity t = ts.isEmpty() ? null : ts.get(i % ts.size());
                double a = (i - (n - 1) / 2.0) * 0.35;
                Vec3d dir = PillarHeat.rotY(fw, a);
                critter(p, w, g.add(dir.multiply(0.4)), t, dir, aw, i * 2);
            }
        });
        return true;
    }

    /** 생물 하나: 땅을 기어 t 에게 → 붙어서 물어뜯음 (t 가 없으면 앞으로 기어가다 사라짐) */
    private static void critter(ServerPlayerEntity p, ServerWorld w, Vec3d start, LivingEntity t, Vec3d dir0, boolean aw, int delay) {
        Vec3d[] pos = {start};
        boolean[] latched = {false};
        for (int i = 0; i < 22; i++) {
            final int k = i;
            Tasks.later(delay + i, () -> {
                if (!alive(p) || latched[0]) return;
                Vec3d goal = t != null && t.isAlive() ? t.getPos() : pos[0].add(dir0.multiply(1.0));
                Vec3d d = goal.subtract(pos[0]).multiply(1, 0, 1);
                double len = d.length();
                Vec3d step = len > 1e-3 ? d.multiply(Math.min(0.8, len) / len) : Vec3d.ZERO;
                // 좌우로 꿈틀대며
                Vec3d side = new Vec3d(-step.z, 0, step.x).multiply(Math.sin(k * 1.3) * 0.25);
                pos[0] = groundAt(w, pos[0].add(step).add(side));
                drawCritter(w, pos[0], step);
                if (k % 5 == 0) w.playSound(null, BlockPos.ofFloored(pos[0]), SoundEvents.ENTITY_SILVERFISH_STEP, SoundCategory.PLAYERS, 0.5f, 1.4f);
                if (t != null && t.isAlive() && t.squaredDistanceTo(pos[0]) < 1.1) {
                    latched[0] = true;
                    bite(p, w, t, aw);
                }
            });
        }
    }

    private static void drawCritter(ServerWorld w, Vec3d c, Vec3d dir) {
        Vec3d f = dir.lengthSquared() > 1e-4 ? dir.normalize() : new Vec3d(1, 0, 0), r = new Vec3d(-f.z, 0, f.x);
        for (int s = 0; s < 3; s++) { Vec3d q = c.add(f.multiply(-0.18 * s)).add(0, 0.12, 0); w.spawnParticles(s == 0 ? FLESH_DARK : CRITTER, q.x, q.y, q.z, 1, 0, 0, 0, 0); }
        for (int s = -1; s <= 1; s += 2) { Vec3d q = c.add(r.multiply(0.14 * s)).add(f.multiply(-0.1)).add(0, 0.04, 0); w.spawnParticles(CRITTER, q.x, q.y, q.z, 1, 0, 0, 0, 0); }
        Vec3d eye = c.add(f.multiply(0.1)).add(0, 0.17, 0);
        w.spawnParticles(BONE, eye.x, eye.y, eye.z, 1, 0, 0, 0, 0);
    }

    private static void bite(ServerPlayerEntity p, ServerWorld w, LivingEntity t, boolean aw) {
        t.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 40, 2, false, true));
        for (int k = 0; k < 5; k++) {
            final int kk = k;
            Tasks.later(k * 4, () -> {
                if (!alive(p) || !t.isAlive()) return;
                Vec3d c = t.getPos().add(w.random.nextGaussian() * 0.25, 0.3 + w.random.nextFloat() * 1.2, w.random.nextGaussian() * 0.25);
                drawCritter(w, c, new Vec3d(w.random.nextGaussian(), 0, w.random.nextGaussian()));
                w.spawnParticles(VampireJob.BLOOD, c.x, c.y, c.z, 3, 0.1, 0.1, 0.1, 0);
                if (kk % 2 == 0) w.playSound(null, t.getBlockPos(), SoundEvents.ENTITY_SILVERFISH_HURT, SoundCategory.PLAYERS, 0.6f, 1.5f);
                Combat.hit(p, t, BITE);
            });
        }
        if (!aw) return;
        Tasks.later(8, () -> {   // 대군: 발밑에서 검은 가시가 솟음 (참고 3:09 'アギィィ')
            if (!alive(p) || !t.isAlive()) return;
            Vec3d g = t.getPos();
            for (int i = 0; i < 10; i++) {
                double a = Math.PI * 2 * i / 10;
                for (int h = 0; h < 6; h++) { double rr = 0.15 + h * 0.08; w.spawnParticles(THORN, g.x + Math.cos(a) * rr, g.y + h * 0.3, g.z + Math.sin(a) * rr, 2, 0.02, 0.05, 0.02, 0); }
            }
            Fx.word(w, g.add(0, 2.2, 0), Fx.Sfx.ZUBA, 1.2f);
            se(w, g, SE_FLESH, 1.3f, 0.7f);
            if (Combat.hit(p, t, THORN_HIT) != Combat.Result.MISS) Fx.impactFlash(w, t.getBoundingBox().getCenter(), 1f);
        });
    }

    static Vec3d groundAt(ServerWorld w, Vec3d q) {
        BlockPos b = BlockPos.ofFloored(q.x, q.y + 1.5, q.z);
        for (int i = 0; i < 6; i++) {
            if (!w.getBlockState(b.down()).getCollisionShape(w, b.down()).isEmpty()) return new Vec3d(q.x, b.getY(), q.z);
            b = b.down();
        }
        return q;
    }

    // ⑤ 변형 팔: 4 팔을 뒤로 → 8~13 7칸 늘어난 팔이 오른쪽에서 왼쪽으로 쓸고 지나감 (날림) · 각성: 9칸, 16~20 되돌아오며 한 번 더
    private static final Hit ARM = new Hit(1.4f, Hit.Kind.KNOCK, 24, 1.3, 0.4, 12, false, false);
    private static final Hit ARM_POKE = new Hit(1.0f, Hit.Kind.HIT, 26, 0, 0, 8, false, false);
    private static final Hit ARM_SLAM = new Hit(1.2f, Hit.Kind.DOWN, 24, 0, -0.8, 10, false, true);

    private static boolean arm(ServerPlayerEntity p, boolean aw) {
        Motion.play(p, "ul_arm");
        Combat.afterSkill(p, 22);
        ServerWorld w = p.getServerWorld();
        double r = aw ? 9 : 7;
        Tasks.later(4, () -> { if (alive(p)) se(w, p.getPos(), SE_FLESH, 1.4f, 0.8f); });
        Set<Integer> got = new HashSet<>();
        sweep(p, w, r, 8, 13, 70, -80, got);
        if (aw) Tasks.later(15, () -> { got.clear(); sweep(p, w, r, 1, 6, -80, 70, got); });
        return true;
    }

    /** 늘어난 팔이 from° 에서 to° 로 쓸고 지나감 (0° = 정면, + = 오른쪽) — t0~t1 틱 동안 */
    private static void sweep(ServerPlayerEntity p, ServerWorld w, double r, int t0, int t1, double from, double to, Set<Integer> got) {
        Vec3d fw0 = Combat.horizontal(p.getRotationVec(1f));
        for (int i = t0; i <= t1; i++) {
            final double u0 = (i - t0) / (double) (t1 - t0 + 1), u1 = (i - t0 + 1) / (double) (t1 - t0 + 1);
            final boolean first = i == t0;
            Tasks.later(i, () -> {
                if (!alive(p)) return;
                if (first) w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 1.2f, 0.6f);
                Vec3d o = p.getPos().add(0, 1.0, 0);
                for (double u = u0; u <= u1 + 1e-6; u += (u1 - u0) / 3) {
                    double a = Math.toRadians(from + (to - from) * u);
                    Vec3d d = PillarHeat.rotY(fw0, a);   // + = 오른쪽
                    for (int s = 2; s <= (int) (r * 2); s += 2) { Vec3d q = o.add(d.multiply(s * 0.5)).add(0, -0.3 * s / (r * 2), 0); w.spawnParticles(s % 4 == 0 ? FLESH : FLESH_DARK, q.x, q.y, q.z, 1, 0.05, 0.05, 0.05, 0); }
                    Vec3d tip = o.add(d.multiply(r));
                    w.spawnParticles(BONE, tip.x, tip.y, tip.z, 2, 0.1, 0.1, 0.1, 0);
                    for (LivingEntity t : around(p, o, r + 0.5)) {
                        Vec3d to2 = t.getPos().subtract(p.getPos()).multiply(1, 0, 1);
                        if (to2.lengthSquared() < 0.3) continue;
                        double ang = Math.toDegrees(Math.atan2(to2.normalize().crossProduct(fw0).y, to2.normalize().dotProduct(fw0)));
                        double lo = Math.min(from + (to - from) * u0, from + (to - from) * u1) - 12, hi = Math.max(from + (to - from) * u0, from + (to - from) * u1) + 12;
                        if (-ang < lo || -ang > hi || !got.add(t.getId())) continue;
                        if (Combat.hit(p, t, ARM) != Combat.Result.MISS) { Vec3d c = t.getBoundingBox().getCenter(); Fx.impactFlash(w, c, 1.1f); w.spawnParticles(FLESH, c.x, c.y, c.z, 10, 0.3, 0.3, 0.3, 0); Fx.word(w, c.add(0, 1, 0), Fx.Sfx.DOGO, 1.2f); }
                    }
                }
            });
        }
    }

    private static boolean armPoke(ServerPlayerEntity p) {
        Motion.play(p, "ul_arm_q");
        Combat.afterSkill(p, 10);
        ServerWorld w = p.getServerWorld();
        Tasks.later(4, () -> {
            if (!alive(p)) return;
            se(w, p.getPos(), SE_FLESH, 1.2f, 1.1f);
            Vec3d o = p.getEyePos().add(0, -0.4, 0), d = p.getRotationVec(1f);
            LivingEntity hit = null;
            for (int s = 1; s <= 12; s++) {
                Vec3d q = o.add(d.multiply(s * 0.5));
                w.spawnParticles(s % 2 == 0 ? FLESH : FLESH_DARK, q.x, q.y, q.z, 1, 0.04, 0.04, 0.04, 0);
                if (hit == null) for (LivingEntity t : around(p, q, 0.9)) { hit = t; break; }
            }
            if (hit == null || Combat.hit(p, hit, ARM_POKE) == Combat.Result.MISS) return;
            final LivingEntity t = hit;
            Fx.impactFlash(w, t.getBoundingBox().getCenter(), 1f);
            Vec3d want = front(p, 1.6, 0);
            for (int i = 1; i <= 4; i++) Tasks.later(i, () -> { if (t.isAlive()) { Vec3d c = t.getPos(); Vec3d q = c.lerp(want, 0.45); t.requestTeleport(q.x, q.y, q.z); t.setVelocity(0, 0, 0); t.velocityModified = true; } });
        });
        return true;
    }

    private static boolean armAir(ServerPlayerEntity p) {
        Motion.play(p, "ul_arm_air");
        Combat.afterSkill(p, 14);
        ServerWorld w = p.getServerWorld();
        Tasks.later(4, () -> { if (alive(p)) se(w, p.getPos(), SE_FLESH, 1.2f, 0.9f); });
        Tasks.later(7, () -> {
            if (!alive(p)) return;
            Vec3d fw = Combat.horizontal(p.getRotationVec(1f));
            Set<Integer> got = new HashSet<>();
            for (int s = 1; s <= 6; s++) {
                Vec3d g = groundAt(w, p.getPos().add(fw.multiply(s)));
                w.spawnParticles(FLESH, g.x, g.y + 0.3, g.z, 6, 0.2, 0.2, 0.2, 0);
                Fx.debris(w, g, 3, 0.6);
                for (LivingEntity t : around(p, g.add(0, 0.5, 0), 1.4)) if (got.add(t.getId()) && Combat.hit(p, t, ARM_SLAM) != Combat.Result.MISS) Fx.impactFlash(w, t.getBoundingBox().getCenter(), 1f);
            }
            Fx.word(w, groundAt(w, p.getPos().add(fw.multiply(4))).add(0, 1.4, 0), Fx.Sfx.ZUDON, 1.3f);
            Fx.shakeAround(w, p.getPos(), 8, 0.5f);
        });
        return true;
    }

    // ⑥ 생명 창조 · 날개: 비행 켜기/끄기 · 각성: 깃털 폭풍 (8 깃털 열둘을 부채꼴로 14칸)
    private static boolean flight(ServerPlayerEntity p) {
        boolean on = !p.getAbilities().allowFlying;
        Motion.play(p, "ul_fly");
        Combat.afterSkill(p, 6);
        UltimateLife.setFlight(p, on);
        ServerWorld w = p.getServerWorld();
        if (on) { wingBurst(w, p); say(p, S_FLY); Combat.move(p, new Vec3d(0, 0.6, 0)); }
        else { Motion.play(p, "#wing:off"); feathers(w, p.getPos().add(0, 1.3, 0), 8, 0.5); }
        JobSkills.bar(p, on ? "등에서 거대한 날개가 돋는다 (스페이스 두 번 = 날기)" : "날개를 거둬들였다", Formatting.DARK_PURPLE);
        return true;
    }

    private static final Hit QUILL = new Hit(0.5f, Hit.Kind.HIT, 14, 0.3, 0, 5, false, false);

    private static boolean featherStorm(ServerPlayerEntity p) {
        Motion.play(p, "ul_feather");
        Combat.afterSkill(p, 16);
        ServerWorld w = p.getServerWorld();
        Motion.play(p, "#wing:on");
        Tasks.later(8, () -> {
            if (!alive(p)) return;
            wingBurst(w, p);
            Vec3d o = p.getPos().add(0, 1.3, 0), fw = Combat.horizontal(p.getRotationVec(1f));
            for (int i = 0; i < 12; i++) {
                Vec3d d = PillarHeat.rotY(fw, Math.toRadians(-36 + 72 * i / 11.0)).add(0, (i % 3 - 1) * 0.08, 0).normalize();
                Set<Integer> got = new HashSet<>();
                for (int s = 1; s <= 14; s++) {
                    final int ss = s;
                    Tasks.later(s / 2, () -> {
                        if (!alive(p)) return;
                        Vec3d q = o.add(d.multiply(ss));
                        w.spawnParticles(FEATHER, q.x, q.y, q.z, 1, 0, 0, 0, 0);
                        w.spawnParticles(FEATHER_PINK, q.x - d.x * 0.3, q.y, q.z - d.z * 0.3, 1, 0, 0, 0, 0);
                        for (LivingEntity t : around(p, q, 0.9)) if (got.add(t.getId()) && Combat.hit(p, t, QUILL) != Combat.Result.MISS) { Fx.impactFlash(w, t.getBoundingBox().getCenter(), 0.6f); feathers(w, t.getBoundingBox().getCenter(), 4, 0.2); }
                    });
                }
            }
        });
        return true;
    }

    // ⑦ 포식: 2~7 파고듦 (1.3칸/틱) → 움켜쥠 → 4·8·…·24 빨아들임 (각성 여덟 번) → 29 내던짐
    private static final Hit GRAB = new Hit(0.4f, Hit.Kind.HIT, 40, 0, 0, 0, true, false).noParry();
    private static final Hit DRAIN = new Hit(0.5f, Hit.Kind.HIT, 34, 0, 0, 0, true, false).noParry();
    private static final Hit FLING = new Hit(1.6f, Hit.Kind.KNOCK, 30, 1.5, 0.5, 0, true, false).noParry();

    private static boolean eat(ServerPlayerEntity p, boolean aw) {
        Motion.play(p, "ul_eat");
        Combat.afterSkill(p, 14);
        long t0 = Combat.now();
        ServerWorld w = p.getServerWorld();
        LivingEntity[] got = {null};
        for (int i = 2; i <= 7; i++) Tasks.later(i, () -> {
            if (!alive(p) || got[0] != null || !"ul_eat".equals(Motion.startedSince(p, t0))) return;
            Vec3d f = Combat.horizontal(p.getRotationVec(1f));
            Combat.move(p, new Vec3d(f.x * 1.3, Math.min(0, p.getVelocity().y), f.z * 1.3));
            w.spawnParticles(FLESH_DARK, p.getX(), p.getY() + 0.8, p.getZ(), 3, 0.2, 0.3, 0.2, 0);
            LivingEntity t = Combat.targets(p, 2.2, 50, 1).stream().findFirst().orElse(null);
            if (t == null) return;
            got[0] = t;
            Combat.move(p, new Vec3d(0, Math.min(0, p.getVelocity().y), 0));
            if (Combat.hit(p, t, GRAB) == Combat.Result.HIT) devour(p, w, t, aw);
        });
        return true;
    }

    private static boolean eatNear(ServerPlayerEntity p, boolean aw) {
        Motion.play(p, "ul_eat");
        Combat.afterSkill(p, 8);
        ServerWorld w = p.getServerWorld();
        Tasks.later(4, () -> {
            if (!alive(p)) return;
            LivingEntity t = Combat.targets(p, 3.0, 60, 1).stream().findFirst().orElse(null);
            if (t != null && Combat.hit(p, t, GRAB) == Combat.Result.HIT) devour(p, w, t, aw);
        });
        return true;
    }

    private static boolean eatAir(ServerPlayerEntity p, boolean aw) {
        Motion.play(p, "ul_eat");
        Combat.afterSkill(p, 12);
        ServerWorld w = p.getServerWorld();
        LivingEntity[] got = {null};
        for (int i = 2; i <= 10; i++) Tasks.later(i, () -> {
            if (!alive(p) || got[0] != null) return;
            Vec3d f = Combat.horizontal(p.getRotationVec(1f));
            Combat.move(p, new Vec3d(f.x * 0.9, -1.0, f.z * 0.9));
            LivingEntity t = Combat.targets(p, 2.4, 70, 1).stream().findFirst().orElse(null);
            if (t == null) return;
            got[0] = t;
            if (Combat.hit(p, t, GRAB) == Combat.Result.HIT) Tasks.later(2, () -> { if (alive(p) && t.isAlive()) devour(p, w, t, aw); });
        });
        return true;
    }

    /** 움켜쥔 뒤: 앞 1.1칸에 붙들고 몸으로 빨아들임 → 내던짐 */
    private static void devour(ServerPlayerEntity p, ServerWorld w, LivingEntity t, boolean aw) {
        Motion.play(p, "ul_eat_hit");
        var cf = Combat.of(p);
        cf.skillStart = Combat.now(); cf.skillMotion = "ul_eat_hit"; cf.skillBusyUntil = Combat.now() + 36;
        Combat.afterSkill(p, 34);
        Combat.invuln(p, 30);
        PillarHeat.root(p, 1, 30);
        say(p, S_EAT_HIT);
        PlayerData.Entry e = bbc.job.Jobs.entry(p);
        Vec3d hold = front(p, 1.1, 0);
        for (int i = 0; i < 28; i++) Tasks.later(i, () -> { if (t.isAlive()) { t.requestTeleport(hold.x, hold.y, hold.z); t.setVelocity(0, 0, 0); t.velocityModified = true; } });
        int n = aw ? 8 : 6;
        for (int k = 0; k < n; k++) {
            final int kk = k;
            Tasks.later(4 + k * (aw ? 3 : 4), () -> {
                if (!alive(p) || !t.isAlive()) return;
                Vec3d a = t.getBoundingBox().getCenter(), b = p.getPos().add(0, 1.1, 0);
                for (int i = 0; i <= 6; i++) { Vec3d q = a.lerp(b, i / 6.0); w.spawnParticles(i % 2 == 0 ? FLESH : VampireJob.BLOOD, q.x, q.y, q.z, 2, 0.05, 0.05, 0.05, 0); }
                if (kk % 2 == 0) se(w, a, SE_FLESH, 1.1f, 0.8f + kk * 0.05f);
                if (Combat.hit(p, t, DRAIN) != Combat.Result.MISS) {
                    p.heal(aw ? 2.5f : 1.5f);
                    VampireJob.addBlood(e, 4);
                }
            });
        }
        Tasks.later(29, () -> {
            if (!alive(p) || !t.isAlive()) return;
            say(p, S_EAT_END);
            if (Combat.hit(p, t, FLING) == Combat.Result.MISS) return;
            Vec3d c = t.getBoundingBox().getCenter();
            Fx.impactFlash(w, c, 1.3f);
            Fx.word(w, c.add(0, 1.2, 0), Fx.Sfx.DOGOOON, 1.4f);
            w.spawnParticles(FLESH, c.x, c.y, c.z, 16, 0.4, 0.4, 0.4, 0);
            if (t.isAlive()) VampireJob.drink(p, e, t);
        });
    }

    // ═════════ 각성 '궁극 생명' (64틱, 참고 2:56.5~3:05 변신의 줄임: 금빛 웅크림 → 바-앙 → 날개) ═════════
    static final int AWAKEN_SCENE = 64;

    static void onAwaken(ServerPlayerEntity p, PlayerData.Entry e) {
        ServerWorld w = p.getServerWorld();
        Motion.play(p, "ul_awaken");
        if (ServerPlayNetworking.canSend(p, CutscenePayload.ID)) ServerPlayNetworking.send(p, new CutscenePayload("ul_awaken", p.getId(), -1));
        Combat.invuln(p, AWAKEN_SCENE);
        Combat.afterSkill(p, AWAKEN_SCENE - 6);
        Awaken.extend(p, AWAKEN_SCENE);
        VampireJob.addBlood(e, 20);
        Tasks.later(2, () -> { if (alive(p)) AlchemySounds.voice(p, S_AWAKEN, List.of(p)); });
        for (int t = 4; t < 22; t += 2) Tasks.later(t, () -> { if (alive(p)) { w.spawnParticles(GOLD_AURA, p.getX(), p.getY() + 0.8, p.getZ(), 10, 0.5, 0.6, 0.5, 0); w.spawnParticles(ParticleTypes.END_ROD, p.getX(), p.getY() + 1, p.getZ(), 2, 0.4, 0.6, 0.4, 0.02); } });
        Tasks.later(22, () -> {
            if (!alive(p)) return;
            goldBurst(w, p, 1.0f);
            Fx.word(w, p.getPos().add(0, 2.6, 0), Fx.Sfx.DOGOOON, 1.8f);
        });
        Tasks.later(36, () -> { if (alive(p)) wingBurst(w, p); });
        Tasks.later(AWAKEN_SCENE - 4, () -> { if (alive(p) && Awaken.active(p)) JobSkills.bar(p, "✦ 궁극 생명 — 1초마다 회복 · 기술이 바뀜  (G = 생명의 정점)", Formatting.GOLD); });
    }

    /** 금빛이 터짐 (껍질이 깨짐): 섬광 + 금빛 고리 + 돌 조각 + 빛줄기 */
    static void goldBurst(ServerWorld w, ServerPlayerEntity p, float size) {
        Vec3d c = p.getPos().add(0, 1.1, 0);
        w.spawnParticles(ParticleTypes.FLASH, c.x, c.y, c.z, 2, 0, 0, 0, 0);
        w.spawnParticles(GOLD_AURA, c.x, c.y, c.z, (int) (80 * size), 0.8, 1.0, 0.8, 0);
        w.spawnParticles(ParticleTypes.END_ROD, c.x, c.y, c.z, (int) (40 * size), 0.2, 0.3, 0.2, 0.35);
        w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.STONE.getDefaultState()), c.x, c.y, c.z, (int) (40 * size), 0.4, 0.8, 0.4, 0.3);
        Fx.ring3d(w, p.getPos().add(0, 0.2, 0), new Vec3d(0, 1, 0), 0.4, 6.0 * size, 0.35f, 12, Fx.GOLD);
        for (int i = 0; i < 16; i++) { double a = Math.PI * 2 * i / 16; Vec3d d = new Vec3d(Math.cos(a), Math.sin(a * 2) * 0.3 + 0.2, Math.sin(a)); PillarLight.ray(w, c, c.add(d.multiply(3.5 * size))); }
        Fx.shakeAround(w, c, 12, 0.7f);
        se(w, c, SE_BURST, 2.0f, 1.0f);
        PillarLight.glint(w, c.add(0, 0.8, 0), 2.0f * size);
    }

    // ═════════ 각성기 '생명의 정점' (120틱) ═════════
    //   0~10 날개를 펴며 웅크림 → 12~18 2칸 날아들어 20 움켜쥠 → 22~44 둘이 하늘로 8칸 → 48·54·60·66 파문 칼날 →
    //   72 한 바퀴 → 76~89 거꾸로 쥐고 내리꽂음 → 90 처박음 (크레이터) → 92~104 생물이 솟아 덮침 → 110 날림 + 흡혈
    static final int ULT_SCENE = 120;
    private static final Hit ULT_GRAB = new Hit(0.3f, Hit.Kind.HIT, 100, 0, 0, 0, true, false).noParry();
    private static final Hit ULT_SLASH = new Hit(0.9f, Hit.Kind.HIT, 80, 0, 0, 0, true, false).noParry();
    private static final Hit ULT_SLAM = new Hit(2.2f, Hit.Kind.HIT, 60, 0, 0, 0, true, true).noParry();
    private static final Hit ULT_BITE = new Hit(0.3f, Hit.Kind.HIT, 40, 0, 0, 0, true, true).noParry();
    private static final Hit ULT_END = new Hit(3.0f, Hit.Kind.KNOCK, 40, 1.6, 0.8, 0, true, true).noParry();

    /** 시작 자리에서 앞으로 나간 거리 · 위로 오른 높이 (컷신 대본과 같은 값) */
    static double ultFwd(int t) { return t < 12 ? 0 : t < 19 ? 2.0 * (t - 12) / 7.0 : 2.0; }
    static double ultUp(int t) {
        if (t < 22) return 0;
        if (t < 44) { double x = (t - 22) / 22.0; return 8 * (1 - (1 - x) * (1 - x)); }
        if (t < 76) return 8;
        if (t < 90) { double x = (t - 76) / 14.0; return 8 * (1 - x * x); }
        return 0;
    }

    static boolean ultimate(ServerPlayerEntity p, PlayerData.Entry e) {
        ServerWorld w = p.getServerWorld();
        LivingEntity t = PillarHeat.near(p, 9);
        if (t == null) { JobSkills.bar(p, "앞에 대상이 없습니다 (9칸)", Formatting.GRAY); return false; }
        Motion.play(p, "ul_ult");
        Combat.invuln(p, ULT_SCENE);
        Combat.afterSkill(p, ULT_SCENE - 6);
        Awaken.extend(p, ULT_SCENE);
        List<ServerPlayerEntity> viewers = new ArrayList<>(List.of(p));
        if (t instanceof ServerPlayerEntity sp) viewers.add(sp);
        CutscenePayload pl = new CutscenePayload("ul_ult", p.getId(), t.getId());
        for (ServerPlayerEntity v : viewers) if (ServerPlayNetworking.canSend(v, CutscenePayload.ID)) ServerPlayNetworking.send(v, pl);
        Tasks.later(2, () -> { if (alive(p)) AlchemySounds.voice(p, S_ULT1, viewers); });
        Tasks.later(46, () -> { if (alive(p)) AlchemySounds.voice(p, S_ULT2, viewers); });
        Tasks.later(100, () -> { if (alive(p)) AlchemySounds.voice(p, S_ULT3, viewers); });
        Vec3d fw = Combat.horizontal(p.getRotationVec(1f)).normalize();
        Vec3d start = p.getPos();
        float yaw = p.getYaw(), pitch = p.getPitch();
        Vec3d want = start.add(fw.multiply(3.0));
        Vec3d hold0 = w.isSpaceEmpty(t, t.getBoundingBox().offset(want.subtract(t.getPos()))) ? want : t.getPos();
        // 위로 얼마나 오를 수 있나 (천장이 있으면 그만큼만)
        double cap = 0;
        for (double h = 0.5; h <= 8.0; h += 0.5) {
            Vec3d q = start.add(fw.multiply(2.0)).add(0, h, 0);
            if (!w.isSpaceEmpty(p, p.getBoundingBox().offset(q.subtract(p.getPos())).expand(0, 0.5, 0)) || !w.isSpaceEmpty(t, t.getBoundingBox().offset(q.add(fw).subtract(t.getPos())))) break;
            cap = h;
        }
        final double maxUp = cap;
        for (int i = 0; i < 112; i++) {
            final int k = i;
            Tasks.later(i, () -> {
                if (!alive(p)) return;
                double up = Math.min(maxUp, ultUp(k));
                Vec3d q = start.add(fw.multiply(ultFwd(k))).add(0, up, 0);
                if (k < 20 || w.isSpaceEmpty(p, p.getBoundingBox().offset(q.subtract(p.getPos())))) {
                    p.networkHandler.requestTeleport(q.x, q.y, q.z, yaw, pitch);
                    p.setVelocity(0, 0, 0);
                }
                if (!t.isAlive()) return;
                Vec3d tq = k < 20 ? hold0 : k < 90 ? q.add(fw.multiply(1.0)) : start.add(fw.multiply(3.2));
                t.requestTeleport(tq.x, tq.y, tq.z);
                t.setVelocity(0, 0, 0);
                t.velocityModified = true;
            });
        }
        Tasks.later(4, () -> { if (alive(p)) wingBurst(w, p); });
        for (int i = 12; i < 20; i += 2) Tasks.later(i, () -> { if (alive(p)) feathers(w, p.getPos().add(0, 1, 0), 4, 0.4); });
        Tasks.later(20, () -> {
            if (!alive(p) || !t.isAlive()) return;
            Combat.hit(p, t, ULT_GRAB);
            Fx.impactFlash(w, t.getBoundingBox().getCenter(), 1.2f);
            Fx.word(w, t.getBoundingBox().getCenter().add(0, 1.2, 0), Fx.Sfx.GAGIN, 1.4f);
            se(w, p.getPos(), SE_WING, 1.6f, 0.8f);
        });
        for (int i = 24; i < 44; i += 3) Tasks.later(i, () -> { if (alive(p)) { feathers(w, p.getPos().add(0, 1.2, 0), 5, 0.6); if (w.random.nextBoolean()) se(w, p.getPos(), SE_WING, 1.0f, 1.0f); } });
        // 48·54·60·66 파문 칼날: 거대한 빛의 호 + 파란 불꽃
        for (int k = 0; k < 4; k++) {
            final int kk = k;
            Tasks.later(48 + k * 6, () -> {
                if (!alive(p) || !t.isAlive()) return;
                boolean right = kk % 2 == 0;
                Fx.slash(w, p, 3.2, right ? 35 : -35, !right, GLASS);
                Fx.slash(w, p, 3.45, right ? 35 : -35, !right, BLUE);
                PillarLight.arcSparkle(w, p, 3.6, right ? 35 : -35, !right);
                Vec3d c = t.getBoundingBox().getCenter();
                spark(w, c, 1.3f);
                PillarLight.se(w, c, PillarLight.SE_BIG, 1.6f, right ? 1.0f : 1.1f);
                se(w, c, SE_RIPPLE, 1.4f, 0.9f);
                if (Combat.hit(p, t, ULT_SLASH) != Combat.Result.MISS) PillarLight.cutHit(w, c, 1.3f, kk == 0 ? Fx.Sfx.ZAN : kk == 3 ? Fx.Sfx.ZUBA : null);
            });
        }
        Tasks.later(72, () -> { if (alive(p)) { Fx.ring3d(w, p.getPos().add(0, 1.0, 0), new Vec3d(0, 1, 0), 0.5, 3.0, 0.3f, 6, PURPLE); feathers(w, p.getPos().add(0, 1.2, 0), 16, 1.0); se(w, p.getPos(), SE_WING, 1.6f, 1.2f); } });
        for (int i = 76; i < 90; i += 2) Tasks.later(i, () -> { if (alive(p)) { PillarLight.ray(w, p.getPos().add(0, 1, 0), p.getPos().add(0, 3, 0)); spark(w, p.getPos().add(fw.multiply(1.0)).add(0, 0.5, 0), 0.5f); } });
        Tasks.later(90, () -> {
            if (!alive(p)) return;
            Vec3d g = start.add(fw.multiply(3.2));
            Fx.ring3d(w, g.add(0, 0.15, 0), new Vec3d(0, 1, 0), 0.5, 6.0, 0.4f, 12, BLUE);
            Fx.ring3d(w, g.add(0, 0.2, 0), new Vec3d(0, 1, 0), 0.4, 4.0, 0.3f, 10, Fx.GOLD);
            Fx.debris(w, g, 30, 2.0);
            for (int i = 0; i < 20; i++) { double a = Math.PI * 2 * i / 20; spark(w, g.add(Math.cos(a) * 2.5, 0.4, Math.sin(a) * 2.5), 0.4f); }
            w.spawnParticles(ParticleTypes.FLASH, g.x, g.y + 1, g.z, 1, 0, 0, 0, 0);
            Fx.word(w, g.add(0, 2.4, 0), Fx.Sfx.DOGOOON, 2.0f);
            Fx.shakeAround(w, g, 16, 1.0f);
            se(w, g, SE_RIPPLE_BIG, 2.2f, 0.8f);
            w.playSound(null, BlockPos.ofFloored(g), SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 1.2f, 0.8f);
            if (t.isAlive()) Combat.hit(p, t, ULT_SLAM);
        });
        // 92~104 땅에서 생물이 솟아 덮침
        for (int k = 0; k < 6; k++) {
            final int kk = k;
            Tasks.later(92 + k * 2, () -> {
                if (!alive(p) || !t.isAlive()) return;
                Vec3d c = t.getPos().add(Math.cos(kk) * 0.5, 0.2 + kk * 0.2, Math.sin(kk) * 0.5);
                drawCritter(w, c, new Vec3d(-Math.cos(kk), 0, -Math.sin(kk)));
                w.spawnParticles(FLESH, c.x, c.y, c.z, 6, 0.2, 0.2, 0.2, 0);
                w.spawnParticles(THORN, c.x, c.y, c.z, 6, 0.1, 0.4, 0.1, 0);
                if (kk % 2 == 0) se(w, c, SE_SWARM, 1.4f, 1.0f);
                Combat.hit(p, t, ULT_BITE);
            });
        }
        Tasks.later(110, () -> {
            if (!alive(p)) return;
            Vec3d c = t.isAlive() ? t.getBoundingBox().getCenter() : start.add(fw.multiply(3.2)).add(0, 1, 0);
            for (LivingEntity x : around(p, c, 5)) Combat.hit(p, x, ULT_END);
            goldBurst(w, p, 0.6f);
            PillarLight.glint(w, c, 2.6f);
            if (t.isAlive()) VampireJob.drink(p, bbc.job.Jobs.entry(p), t);
        });
        return true;
    }

    // ═════════ 도움 ═════════
    static boolean alive(ServerPlayerEntity p) { return PillarHeat.alive(p); }
    static Vec3d front(ServerPlayerEntity p, double d, double up) { return PillarHeat.front(p, d, up); }

}
