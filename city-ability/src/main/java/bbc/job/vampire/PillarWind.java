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
import net.minecraft.entity.mob.MobEntity;
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
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 기둥 속 사내 · 바람 루트 (뱀파이어 3단계, 참고: 올스타 배틀 R '바람의 기둥 속 사내' + 아이즈 오브 헤븐 캐릭터 영상 — 기술·자세 원칙만, 이름은 우리 것).
 *
 * 무겁고 강한 한 방 위주. 바람의 유법(모드) 중엔 몸에 바람을 둘러 주는 피해 +20%·빨라짐·평타에 칼바람.
 * 기술 (비용 = 피, 7개 중 5개 장착):
 *   ① 진공 소용돌이 — 머리 위 팔을 대각선으로 휘둘러 진공 칼바람 (세 번 휘두름, 매번 여러 타) · 평타 중: 한 번 · 공중: 아래로 휘몰아침
 *   ② 바람의 프로텍터 — 몸의 관에서 바람을 뿜어 빛을 굴절, 6초 투명 (몹이 놓침). 투명할 때 첫 타격 +30% · 평타 중·공중: 바람을 타고 옆으로 빠짐
 *   ③ 그림자 일격 — 땅에 붙을 만큼 엎드렸다 바람을 감아 올려쳐 띄움 · 평타 중: 엎드리지 않고 바로 · 공중: 내리꽂아 착지하며
 *   ④ 비틀어 치기 — 몸을 뒤로 비틀었다 온몸을 던져 곧게 내지름 → 한 바퀴 돌며 가로로 후려침 (가드 깨기) · 평타 중: 내지르기만 · 공중: 내리꽂는 주먹
 *   ⑤ 하늘 내려찍기 — 높이 뛰어 두 주먹으로 내리찍고, 튀어 올라 한 번 더 · 공중: 바로 내리찍기부터
 *   ⑥ 바람의 유법 — 10초 모드
 *   ⑦ 신사폭풍 — 두 팔을 반대로 돌려 가로 회오리를 앞으로 12칸 쏨 (연타 → 날려보냄) · 평타 중: 한 팔로 짧게 · 공중: 비스듬히 아래로
 * 각성 '바람만을 느껴 사물을 보겠다' (64틱): 스스로 눈을 감고 바람으로 봄 — 내 화면은 푸르게, 주위 생물은 윤곽으로 보임. 카드가 통째로 바뀜
 * 각성기 '최종 유법' (170틱 ≈ 참고 영상 8.2초): 머리의 뿔에서 실 같은 바람 칼날을 쏘아 꽂고, 고개로 8 자를 그리며 수십 번 벰 → 마지막 회오리로 날림
 * 햇빛: 열 루트와 같이 석화
 */
public final class PillarWind {
    private PillarWind() {}

    public static final String ROUTE = "wind";
    public static final String EQV = "p3w";

    // ───────── 소리 (이벤트만 모드에 등록, 소리 파일은 따로 주는 리소스팩 'BBC 기둥 속 사내 · 바람 사운드') ─────────
    static final SoundEvent S_VAC = snd("vac"), S_VAC_Q = snd("vac_q"), S_AIR = snd("air"), S_PROTECT = snd("protect"), S_SLIP = snd("slip"),
            S_SHADOW = snd("shadow"), S_TWIST = snd("twist"), S_TWIST_END = snd("twist_end"), S_LEAP = snd("leap"),
            S_MODE = snd("mode"), S_MODE_END = snd("mode_end"), S_SAND = snd("sand"), S_SAND_Q = snd("sand_q"),
            S_DRILL = snd("drill"), S_SENSE = snd("sense"), S_SENSE_HIT = snd("sense_hit"), S_AMBUSH = snd("ambush"),
            S_AWAKEN = snd("awaken"), S_AWAKEN2 = snd("awaken2"), S_ULT1 = snd("ult1"), S_ULT2 = snd("ult2"), S_ULT3 = snd("ult3"), S_ULT4 = snd("ult4"),
            S_ULT_SE = snd("ult_se"), S_PETRIFY = snd("petrify"), S_M1 = snd("m1"), S_M1_HEAVY = snd("m1_heavy"), S_M1_AIR = snd("m1_air");

    private static SoundEvent snd(String path) {
        Identifier id = Identifier.of(Bbc.MOD_ID, "pillar_wind." + path);
        return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id));
    }
    private static void say(ServerPlayerEntity p, SoundEvent s) { AlchemySounds.play(p, s, 1.6f); }

    // ───────── 평타: 왼 잽 → 머리 위 오른팔 내리찍기 → 왼 올려치기 → 오른 손등 → 두 손바닥 밀치기 (막타 선딜 6) ─────────
    static final bbc.combat.Weapon.M1Profile M1 = new bbc.combat.Weapon.M1Profile(5, 4, 2, 1.1f, 0.3, "pw_", 6);

    static final JobSkills.Skill[] LIST = {
            new JobSkills.Skill("진공 소용돌이", "진공", 3, 15, 8, "머리 위 팔을 대각선으로 세 번 휘둘러 진공 칼바람으로 벰 (매번 여러 타) · 평타 중: 한 번 · 공중: 아래로 휘몰아침 · 유법 중: 더 넓고 많이"),
            new JobSkills.Skill("바람의 프로텍터", "투명", 3, 20, 18, "몸의 관에서 바람을 뿜어 빛을 굴절 — 6초 투명 (몹이 놓침), 투명할 때 첫 타격 +30% · 평타 중·공중: 바람을 타고 옆으로 빠짐"),
            new JobSkills.Skill("그림자 일격", "그림자", 3, 15, 9, "땅에 붙을 만큼 엎드렸다 바람을 감아 올려쳐 띄움 · 평타 중: 바로 올려침 · 공중: 내리꽂아 착지하며 올려침"),
            new JobSkills.Skill("비틀어 치기", "비틀기", 3, 20, 12, "몸을 뒤로 비틀었다 온몸을 던져 내지름 → 한 바퀴 돌며 바람을 실어 가로로 후려침 (가드 깨기) · 평타 중: 내지르기만 · 공중: 내리꽂는 주먹"),
            new JobSkills.Skill("하늘 내려찍기", "내려찍기", 3, 20, 14, "높이 뛰어 상대 위로 → 두 주먹으로 내리찍고 튀어 올라 한 번 더 (다운) · 공중: 바로 내리찍기부터"),
            new JobSkills.Skill("바람의 유법", "유법", 3, 20, 30, "몸에 바람을 두름 — 10초 동안 주는 피해 +20% · 빨라짐 · 평타에 칼바람 · 진공 소용돌이가 커짐"),
            new JobSkills.Skill("신사폭풍", "폭풍", 3, 30, 18, "두 팔을 반대로 돌려 가로 회오리를 앞으로 12칸 쏨 — 연타로 갈다가 날려보냄 · 평타 중: 한 팔로 짧게 · 공중: 비스듬히 아래로"),
    };

    /** 각성 중 기술 카드 */
    static final JobSkills.Skill[] AWAKE = {
            new JobSkills.Skill("진공 대소용돌이", "진공", 3, 15, 6, "진공 칼바람 세 번 + 작은 회오리 셋이 앞으로 굴러감 · 평타 중: 한 번 · 공중: 아래로"),
            new JobSkills.Skill("바람 감지", "감지", 3, 15, 12, "바람 소리를 들음 — 0.8초 안에 맞으면 상대 등 뒤로 돌아 후려쳐 날림 · 평타 중·공중: 옆으로 빠짐"),
            new JobSkills.Skill("그림자 회오리", "그림자", 3, 15, 8, "엎드렸다 올려치며 둘레 3.5칸을 모두 띄우고 회오리 기둥이 1.5초 남음"),
            new JobSkills.Skill("바람 드릴", "드릴", 3, 20, 10, "몸을 숙여 바람 고리를 쏴 묶고 → 오른주먹에 회오리를 감아 찔러 넣음 (연타 → 날림) · 공중: 내리꽂는 주먹"),
            new JobSkills.Skill("하늘 내려찍기 · 폭풍", "내려찍기", 3, 20, 12, "높이 뛰어 두 번 내리찍음 — 착지마다 반지름 3칸 바람 충격"),
            new JobSkills.Skill("바람 습격", "습격", 3, 20, 12, "모습을 지우고 12칸 안 상대 등 뒤에 나타나 후려침 (공중에서도)"),
            new JobSkills.Skill("투기 신사폭풍", "폭풍", 3, 30, 14, "더 크고 긴 회오리 (16칸, 넓게) · 평타 중: 한 팔로 짧게 · 공중: 비스듬히 아래로"),
    };

    static JobSkills.Skill[] list(ServerPlayerEntity p) { return Awaken.active(p) ? AWAKE : LIST; }

    /** 클래스 로딩 = 소리 이벤트 등록 (모드 초기화에서 불러야 함 — 레지스트리가 얼기 전에) */
    public static void register() {}

    // ───────── 상태 ─────────
    private static final Map<UUID, Long> MODE_UNTIL = new HashMap<>();
    private static final Map<UUID, Long> HIDE_UNTIL = new HashMap<>();

    public static boolean is(PlayerData.Entry e) { return VampireJob.stage(e) >= 3 && ROUTE.equals(e.str(VampireJob.P + "route")); }
    static boolean mode(ServerPlayerEntity p) { Long u = MODE_UNTIL.get(p.getUuid()); return u != null && Combat.now() < u; }
    static boolean hidden(ServerPlayerEntity p) { Long u = HIDE_UNTIL.get(p.getUuid()); return u != null && Combat.now() < u; }

    /** 주는 피해 배수: 유법 +20%, 각성 +10%, 투명할 때 첫 타격 +30% (투명이 풀림) */
    public static float mul(ServerPlayerEntity p) {
        float m = 1f;
        if (mode(p)) m += 0.2f;
        if (Awaken.active(p)) m += 0.1f;
        if (hidden(p)) { m += 0.3f; unhide(p); }
        return m;
    }

    public static String hud(ServerPlayerEntity p) {
        return (mode(p) ? " · 바람의 유법" : "") + (hidden(p) ? " · 투명" : "") + (PillarHeat.petrified(p) ? " · 석화" : "");
    }

    static void clear(UUID id) { MODE_UNTIL.remove(id); HIDE_UNTIL.remove(id); }

    /** 매초: 햇빛 석화 · 유법 바람 · 투명 · 각성 바람 시야 */
    static void second(ServerPlayerEntity p, boolean sunlit) {
        ServerWorld w = p.getServerWorld();
        PillarHeat.sun(p, sunlit, S_PETRIFY);
        if (mode(p)) {
            w.spawnParticles(ParticleTypes.SMALL_GUST, p.getX(), p.getY() + 1.0, p.getZ(), 2, 0.5, 0.6, 0.5, 0);
            helix(w, p.getPos(), new Vec3d(0, 1, 0), 2.0, 0.7, 2, Combat.now() * 0.3, 18, WIND);
        } else if (MODE_UNTIL.remove(p.getUuid()) != null) {
            say(p, S_MODE_END);
            JobSkills.bar(p, "바람의 유법이 풀렸다", Formatting.GRAY);
        }
        if (hidden(p)) {
            for (MobEntity m : w.getEntitiesByClass(MobEntity.class, p.getBoundingBox().expand(24), m -> m.getTarget() == p)) m.setTarget(null);
            w.spawnParticles(WIND, p.getX(), p.getY() + 1.0, p.getZ(), 3, 0.3, 0.6, 0.3, 0);
        } else if (HIDE_UNTIL.remove(p.getUuid()) != null) {
            p.removeStatusEffect(StatusEffects.INVISIBILITY);
        }
        if (Awaken.active(p)) sight(p, true);
    }

    private static void unhide(ServerPlayerEntity p) {
        HIDE_UNTIL.remove(p.getUuid());
        p.removeStatusEffect(StatusEffects.INVISIBILITY);
        p.getServerWorld().spawnParticles(ParticleTypes.GUST, p.getX(), p.getY() + 1.0, p.getZ(), 1, 0, 0, 0, 0);
    }

    /** 각성 중 바람 시야 (내 화면만): 푸르게 + 주위 생물 윤곽. 클라이언트는 3초 소식이 없으면 저절로 끔 */
    static void sight(ServerPlayerEntity p, boolean on) {
        if (ServerPlayNetworking.canSend(p, CutscenePayload.ID)) ServerPlayNetworking.send(p, new CutscenePayload(on ? "#windsight:on" : "#windsight:off", p.getId(), -1));
    }

    // ═════════ 연출 도구 ═════════
    static final DustParticleEffect WIND = new DustParticleEffect(new Vector3f(0.92f, 0.95f, 0.97f), 1.1f);
    static final DustParticleEffect WIND_GOLD = new DustParticleEffect(new Vector3f(1f, 0.88f, 0.45f), 1.0f);
    private static final BlockState GLASS = Fx.WHITE;

    /** 축 dir 을 따라 len 만큼 감기는 나선 (바람 소용돌이) */
    static void helix(ServerWorld w, Vec3d from, Vec3d dir, double len, double r, int turns, double phase, int pts, DustParticleEffect dust) {
        Vec3d d = dir.normalize();
        Vec3d a = Math.abs(d.y) > 0.9 ? new Vec3d(1, 0, 0) : new Vec3d(0, 1, 0);
        Vec3d u = d.crossProduct(a).normalize(), v = d.crossProduct(u).normalize();
        int n = Math.max(6, pts * turns);
        for (int i = 0; i < n; i++) {
            double f = i / (double) n, ang = phase + f * turns * Math.PI * 2;
            Vec3d q = from.add(d.multiply(f * len)).add(u.multiply(Math.cos(ang) * r)).add(v.multiply(Math.sin(ang) * r));
            w.spawnParticles(dust, q.x, q.y, q.z, 1, 0, 0, 0, 0);
        }
    }

    /** 가로 회오리 한 덩어리 (신사폭풍): from 에서 dir 로 len, 반지름 r — 나선 둘 + 흙먼지·바위 조각 */
    static void tornado(ServerWorld w, Vec3d from, Vec3d dir, double len, double r, double phase) {
        helix(w, from, dir, len, r, (int) Math.max(2, len), phase, 10, WIND);
        helix(w, from, dir, len, r * 0.6, (int) Math.max(2, len), phase + Math.PI, 8, WIND);
        Vec3d d = dir.normalize();
        for (int i = 0; i < 4; i++) {
            Vec3d q = from.add(d.multiply(w.getRandom().nextDouble() * len));
            w.spawnParticles(ParticleTypes.CLOUD, q.x, q.y, q.z, 2, r * 0.5, r * 0.5, r * 0.5, 0.05);
            w.spawnParticles(new net.minecraft.particle.BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.SAND.getDefaultState()), q.x, q.y, q.z, 3, r * 0.5, r * 0.5, r * 0.5, 0.1);
        }
    }

    /** 진공 칼바람 한 줄기 (흰 호 + 휘감김) */
    static void vacuum(ServerWorld w, ServerPlayerEntity p, double radius, double tilt, boolean reverse) {
        Fx.slash(w, p, radius, tilt, reverse, GLASS);
        Vec3d c = front(p, radius * 0.6, 1.1);
        w.spawnParticles(ParticleTypes.SWEEP_ATTACK, c.x, c.y, c.z, 2, 0.4, 0.3, 0.4, 0);
        w.spawnParticles(ParticleTypes.SMALL_GUST, c.x, c.y, c.z, 2, 0.5, 0.4, 0.5, 0);
        w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_BREEZE_SHOOT, SoundCategory.PLAYERS, 0.8f, 1.3f);
    }

    static void windHit(ServerWorld w, Vec3d c, float size, Fx.Sfx word) {
        w.spawnParticles(ParticleTypes.GUST, c.x, c.y, c.z, 1, 0, 0, 0, 0);
        w.spawnParticles(ParticleTypes.CLOUD, c.x, c.y, c.z, (int) (8 * size), 0.3 * size, 0.3 * size, 0.3 * size, 0.08);
        Fx.swirl(w, c, 2, 0.9f * size);
        Fx.impactFlash(w, c, 0.9f * size);
        if (word != null) Fx.word(w, c.add(0, 1.0, 0), word, 1.0f + 0.2f * size);
        w.playSound(null, BlockPos.ofFloored(c), SoundEvents.ENTITY_BREEZE_WIND_BURST.value(), SoundCategory.PLAYERS, 0.9f, 0.9f);
    }

    private static List<LivingEntity> around(ServerPlayerEntity p, Vec3d c, double r) {
        List<LivingEntity> out = new ArrayList<>();
        for (Entity x : p.getServerWorld().getOtherEntities(p, new Box(c, c).expand(r), x -> Targets.canHit(p, x)))
            if (x instanceof LivingEntity le && le.squaredDistanceTo(c) <= r * r) out.add(le);
        return out;
    }

    // ═════════ 평타 연출 ═════════
    private static final Hit WIND_CUT = new Hit(0.3f, Hit.Kind.HIT, 8, 0.25, 0, 2, false, false);

    static void m1Fx(ServerPlayerEntity p, String motion) {
        ServerWorld w = p.getServerWorld();
        Vec3d fw = Combat.horizontal(p.getRotationVec(1f));
        var f = Combat.of(p);
        LivingEntity t = f.lastTargetAt == Combat.now() && f.lastTarget != null && w.getEntity(f.lastTarget) instanceof LivingEntity le ? le : null;
        Vec3d c = t != null ? t.getBoundingBox().getCenter() : p.getPos().add(fw.multiply(1.4)).add(0, 1.1, 0);
        SoundEvent v = switch (motion) {
            case "pw_1", "pw_2", "pw_3", "pw_4" -> p.getRandom().nextFloat() < 0.55f ? S_M1 : null;
            case "pw_5", "pw_rise" -> S_M1_HEAVY;
            case "pw_a1", "pw_a2", "pw_a3" -> p.getRandom().nextFloat() < 0.6f ? S_M1_AIR : null;
            case "pw_slam" -> S_AIR;
            default -> null; };
        if (v != null) AlchemySounds.play(p, v, 1.3f);
        switch (motion) {
            case "pw_2" -> Fx.slash(w, p, 2.0, 50, false, GLASS);
            case "pw_4" -> Fx.slash(w, p, 2.2, 0, true, GLASS);
            case "pw_5" -> { if (t != null) { windHit(w, c, 1.3f, Fx.Sfx.DOGO); helix(w, p.getEyePos().add(0, -0.4, 0), fw, 3.0, 0.5, 3, 0, 8, WIND); } }
            case "pw_rise" -> { if (t != null) { windHit(w, c, 1.1f, null); helix(w, t.getPos(), new Vec3d(0, 1, 0), 2.5, 0.6, 2, 0, 10, WIND); } }
            case "pw_slam" -> Tasks.later(3, () -> {
                if (!alive(p)) return;
                Vec3d g = p.getPos().add(Combat.horizontal(p.getRotationVec(1f)));
                Fx.ring3d(w, g.add(0, 0.15, 0), new Vec3d(0, 1, 0), 0.4, 3.0, 0.3f, 7, GLASS);
                Fx.debris(w, g, 10, 1.0);
                w.spawnParticles(ParticleTypes.GUST, g.x, g.y + 0.3, g.z, 1, 0, 0, 0, 0);
            });
            default -> { }
        }
        if (t != null && !motion.equals("pw_5") && !motion.equals("pw_rise")) {
            w.spawnParticles(ParticleTypes.SMALL_GUST, c.x, c.y, c.z, 2, 0.2, 0.2, 0.2, 0);
            w.spawnParticles(ParticleTypes.CLOUD, c.x, c.y, c.z, 4, 0.2, 0.2, 0.2, 0.04);
        }
        if (t != null && (mode(p) || Awaken.active(p))) {   // 유법·각성: 평타에 칼바람이 한 번 더 지나감
            final LivingEntity tt = t;
            Tasks.later(2, () -> {
                if (!alive(p) || !tt.isAlive()) return;
                Combat.hit(p, tt, WIND_CUT);
                Fx.swirl(w, tt.getBoundingBox().getCenter(), 2, 0.8f);
                w.spawnParticles(ParticleTypes.SWEEP_ATTACK, tt.getX(), tt.getY() + 1, tt.getZ(), 1, 0, 0, 0, 0);
            });
        }
    }

    // ═════════ 기술 ═════════
    static boolean use(ServerPlayerEntity p, PlayerData.Entry e, int idx) {
        boolean air = !p.isOnGround(), chain = Combat.inM1Chain(p), aw = Awaken.active(p), md = mode(p);
        boolean ok = aw ? switch (idx) {
            case 0 -> air ? vacAir(p, true) : chain ? vacQ(p, true) : vac(p, true);
            case 1 -> air || chain ? slip(p) : sense(p);
            case 2 -> air ? shadowAir(p, true) : chain ? shadowQ(p, true) : shadow(p, true);
            case 3 -> air ? twistAir(p, true) : drill(p);
            case 4 -> air ? leapAir(p, true) : leap(p, true);
            case 5 -> ambush(p);
            case 6 -> air ? sandAir(p, true) : chain ? sandQ(p, true) : sand(p, true);
            default -> false;
        } : switch (idx) {
            case 0 -> air ? vacAir(p, md) : chain ? vacQ(p, md) : vac(p, md);
            case 1 -> air || chain ? slip(p) : protect(p);
            case 2 -> air ? shadowAir(p, false) : chain ? shadowQ(p, false) : shadow(p, false);
            case 3 -> air ? twistAir(p, false) : chain ? twistQ(p) : twist(p);
            case 4 -> air ? leapAir(p, false) : leap(p, false);
            case 5 -> windMode(p);
            case 6 -> air ? sandAir(p, false) : chain ? sandQ(p, false) : sand(p, false);
            default -> false;
        };
        if (ok) {
            SoundEvent s = switch (idx) {   // ②④⑥ 일부는 기술 안에서
                case 0 -> air ? S_AIR : chain ? S_VAC_Q : S_VAC;
                case 2 -> air ? S_AIR : S_SHADOW;
                case 3 -> air ? S_AIR : aw ? S_DRILL : S_TWIST;
                case 4 -> air ? S_AIR : S_LEAP;
                case 6 -> chain ? S_SAND_Q : air ? S_AIR : S_SAND;
                default -> null; };
            if (s != null) say(p, s);
        }
        return ok;
    }

    // ① 진공 소용돌이 (참고 '진공파문의 여파다' 0:52~0:58): 6·14·22 세 번 휘두름, 매번 칼바람이 2틱 간격 세 번 감김 (앞 3.5칸 부채꼴)
    private static final Hit VAC = new Hit(0.35f, Hit.Kind.HIT, 14, 0.05, 0, 4, false, false);
    private static final Hit VAC_END = new Hit(0.8f, Hit.Kind.KNOCK, 22, 0.9, 0.3, 8, false, false);
    private static final Hit VAC_Q = new Hit(0.6f, Hit.Kind.HIT, 16, 0.2, 0, 6, false, false);

    private static boolean vac(ServerPlayerEntity p, boolean big) {
        Motion.play(p, "pw_vac");
        Combat.afterSkill(p, 26);
        ServerWorld w = p.getServerWorld();
        double reach = big ? 5.0 : 3.5;
        int[] at = {6, 14, 22};
        for (int k = 0; k < 3; k++) {
            final int kk = k;
            Tasks.later(at[k], () -> {
                if (!alive(p)) return;
                vacuum(w, p, reach, kk == 1 ? -45 : 45, kk == 1);
                for (int j = 0; j < (big ? 4 : 3); j++) {
                    final int jj = j;
                    Tasks.later(j * 2, () -> {
                        if (!alive(p)) return;
                        boolean last = kk == 2 && jj == (big ? 3 : 2);
                        for (LivingEntity t : Combat.targets(p, reach, 70, big ? 6 : 4)) {
                            if (Combat.hit(p, t, last ? VAC_END : VAC) == Combat.Result.MISS) continue;
                            Vec3d c = t.getBoundingBox().getCenter();
                            Fx.swirl(w, c, 1, 0.8f);
                            w.spawnParticles(ParticleTypes.SWEEP_ATTACK, c.x, c.y, c.z, 1, 0.2, 0.2, 0.2, 0);
                            if (last) windHit(w, c, 1.1f, Fx.Sfx.ZUBA);
                        }
                    });
                }
            });
        }
        if (Awaken.active(p)) Tasks.later(22, () -> {   // 각성: 작은 회오리 셋이 앞으로 굴러감
            if (!alive(p)) return;
            Vec3d fw = Combat.horizontal(p.getRotationVec(1f));
            for (int k = -1; k <= 1; k++) rollingTwister(p, front(p, 1.0, 0).add(rotY(fw, k * 0.35).multiply(0.1)), rotY(fw, k * 0.35), 8);
        });
        return true;
    }

    private static final Hit TWISTER = new Hit(0.4f, Hit.Kind.AIR, 18, 0.1, 0.5, 4, false, false);

    /** 작은 회오리가 1틱에 0.6칸씩 굴러가며 닿은 상대를 띄움 */
    private static void rollingTwister(ServerPlayerEntity p, Vec3d start, Vec3d dir, int len) {
        ServerWorld w = p.getServerWorld();
        Set<Integer> got = new HashSet<>();
        for (int i = 1; i <= len * 2; i++) {
            final int k = i;
            Tasks.later(i, () -> {
                if (!alive(p)) return;
                Vec3d q = start.add(dir.multiply(k * 0.6));
                helix(w, q, new Vec3d(0, 1, 0), 1.6, 0.45, 2, k * 0.8, 8, WIND);
                if (k % 3 == 0) w.spawnParticles(ParticleTypes.SMALL_GUST, q.x, q.y + 0.6, q.z, 1, 0.2, 0.2, 0.2, 0);
                for (LivingEntity t : around(p, q.add(0, 0.8, 0), 1.1)) if (got.add(t.getId())) Combat.hit(p, t, TWISTER);
            });
        }
    }

    private static boolean vacQ(ServerPlayerEntity p, boolean big) {
        Motion.play(p, "pw_vac_q");
        Combat.afterSkill(p, 8);
        ServerWorld w = p.getServerWorld();
        Tasks.later(4, () -> {
            if (!alive(p)) return;
            vacuum(w, p, big ? 4.0 : 3.0, 45, false);
            for (LivingEntity t : Combat.targets(p, big ? 4.0 : 3.0, 60, 3))
                if (Combat.hit(p, t, VAC_Q) != Combat.Result.MISS) Fx.swirl(w, t.getBoundingBox().getCenter(), 2, 0.9f);
        });
        return true;
    }

    private static final Hit VAC_DOWN = new Hit(0.9f, Hit.Kind.DOWN, 20, 0, -0.7, 8, false, true);

    private static boolean vacAir(ServerPlayerEntity p, boolean big) {
        Motion.play(p, "pw_vac_air");
        Combat.afterSkill(p, 12);
        ServerWorld w = p.getServerWorld();
        Tasks.later(6, () -> {
            if (!alive(p)) return;
            Vec3d c = front(p, 1.6, -1.2);
            helix(w, p.getPos().add(0, 0.8, 0), c.subtract(p.getPos().add(0, 0.8, 0)), 2.8, 0.7, 3, 0, 10, WIND);
            Fx.slash(w, p, 3.0, 80, false, GLASS);
            for (LivingEntity t : around(p, c, big ? 3.2 : 2.4)) if (Combat.hit(p, t, VAC_DOWN) != Combat.Result.MISS) windHit(w, t.getBoundingBox().getCenter(), 0.9f, null);
        });
        return true;
    }

    // ② 바람의 프로텍터 (참고 아이즈 오브 헤븐 1:20~1:24): 4 웅크림 → 9 관에서 바람을 뿜으며 몸을 펴고 투명 (120틱)
    private static boolean protect(ServerPlayerEntity p) {
        Motion.play(p, "pw_protect");
        Combat.afterSkill(p, 18);
        ServerWorld w = p.getServerWorld();
        say(p, S_PROTECT);
        Tasks.later(9, () -> {
            if (!alive(p)) return;
            for (int k = 0; k < 8; k++) {   // 몸의 관 여덟 개에서 바람이 뿜어져 나옴
                double a = Math.PI * 2 * k / 8;
                Vec3d o = p.getPos().add(Math.cos(a) * 0.3, 1.1 + (k % 2) * 0.3, Math.sin(a) * 0.3);
                helix(w, o, new Vec3d(Math.cos(a), 0.3, Math.sin(a)), 1.6, 0.15, 2, a, 6, WIND);
            }
            w.spawnParticles(ParticleTypes.GUST_EMITTER_SMALL, p.getX(), p.getY() + 1, p.getZ(), 1, 0, 0, 0, 0);
            w.spawnParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + 1, p.getZ(), 30, 0.6, 0.8, 0.6, 0.1);
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_BREEZE_IDLE_GROUND, SoundCategory.PLAYERS, 1.4f, 0.7f);
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.INVISIBILITY, 120, 0, false, false));
            HIDE_UNTIL.put(p.getUuid(), Combat.now() + 120);
            for (MobEntity m : w.getEntitiesByClass(MobEntity.class, p.getBoundingBox().expand(24), m -> m.getTarget() == p)) m.setTarget(null);
            JobSkills.bar(p, "바람이 빛을 굴절시킨다 — 6초 투명 (첫 타격 +30%)", Formatting.AQUA);
        });
        return true;
    }

    // 평타 중·공중: 바람을 타고 옆(움직이는 쪽, 없으면 왼쪽)으로 미끄러지듯 빠짐 (0.3초 무적)
    private static boolean slip(ServerPlayerEntity p) {
        Motion.play(p, "pw_slip");
        Combat.afterSkill(p, 4);
        Combat.invuln(p, 6);
        say(p, S_SLIP);
        ServerWorld w = p.getServerWorld();
        Vec3d fw = Combat.horizontal(p.getRotationVec(1f));
        Vec3d side = p.sidewaysSpeed < 0 ? new Vec3d(-fw.z, 0, fw.x) : new Vec3d(fw.z, 0, -fw.x);
        if (p.forwardSpeed < 0) side = side.add(fw.multiply(-0.6)).normalize();
        final Vec3d dir = side;
        for (int i = 1; i <= 4; i++) Tasks.later(i, () -> {
            if (!alive(p)) return;
            Combat.move(p, new Vec3d(dir.x * 1.1, Math.max(0, p.getVelocity().y), dir.z * 1.1));
            w.spawnParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + 0.8, p.getZ(), 4, 0.2, 0.4, 0.2, 0.02);
            w.spawnParticles(ParticleTypes.SMALL_GUST, p.getX(), p.getY() + 0.8, p.getZ(), 1, 0.2, 0.3, 0.2, 0);
        });
        w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_BREEZE_JUMP, SoundCategory.PLAYERS, 1.0f, 1.2f);
        return true;
    }

    // 각성 ② 바람 감지: 16틱 동안 받아냄 (5칸 안, 앞뒤 모두) → 상대 등 뒤로 돌아 후려침
    private static final Hit SENSE_HIT = new Hit(1.4f, Hit.Kind.KNOCK, 26, 1.2, 0.4, 0, true, false).noParry();

    private static boolean sense(ServerPlayerEntity p) {
        Motion.play(p, "pw_sense");
        Combat.afterSkill(p, 26);
        ServerWorld w = p.getServerWorld();
        say(p, S_SENSE);
        for (int i = 3; i < 20; i += 3) Tasks.later(i, () -> { if (alive(p)) helix(w, p.getPos().add(0, 1.9, 0), new Vec3d(0, 1, 0), 0.4, 0.5, 1, Combat.now() * 0.5, 12, WIND); });
        Tasks.later(3, () -> {
            if (!alive(p)) return;
            Combat.counterWindow(p, 16, false, 5.0, atk -> {
                if (!alive(p)) return;
                Vec3d back = atk.getPos().add(Combat.horizontal(atk.getRotationVec(1f)).multiply(-1.3));
                w.spawnParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + 1, p.getZ(), 20, 0.3, 0.6, 0.3, 0.05);
                face(p, back, atk.getPos());
                Motion.play(p, "pw_sense_hit");
                say(p, S_SENSE_HIT);
                var cf = Combat.of(p);
                cf.skillStart = Combat.now(); cf.skillMotion = "pw_sense_hit"; cf.skillBusyUntil = Combat.now() + 16;
                Combat.afterSkill(p, 14);
                Combat.invuln(p, 8);
                Fx.flash(p, 0.6f);
                Tasks.later(4, () -> {
                    if (!alive(p) || !atk.isAlive()) return;
                    if (Combat.hit(p, atk, SENSE_HIT) != Combat.Result.MISS) windHit(w, atk.getBoundingBox().getCenter(), 1.3f, Fx.Sfx.BAKIN);
                });
            });
        });
        return true;
    }

    /** to 자리로 옮기고 look 쪽을 봄 */
    private static void face(ServerPlayerEntity p, Vec3d to, Vec3d look) {
        Vec3d d = look.subtract(to);
        float yaw = (float) (MathHelper.atan2(d.z, d.x) * 180 / Math.PI) - 90f;
        p.networkHandler.requestTeleport(to.x, to.y, to.z, yaw, p.getPitch());
    }

    // ③ 그림자 일격 (참고 '그림자에 들어간 네 잘못이다' 0:30.1~0:30.5): 4~8 납작 엎드림 (맞아도 덜 밀림) → 10 바람을 감아 올려침 (앞 3칸)
    private static final Hit SHADOW = new Hit(1.2f, Hit.Kind.AIR, 26, 0.2, 1.1, 10, false, false);

    private static boolean shadow(ServerPlayerEntity p, boolean big) { return shadowAt(p, "pw_shadow", 10, big); }
    private static boolean shadowQ(ServerPlayerEntity p, boolean big) { return shadowAt(p, "pw_shadow_q", 5, big); }

    private static boolean shadowAt(ServerPlayerEntity p, String motion, int hit, boolean big) {
        Motion.play(p, motion);
        Combat.afterSkill(p, hit + 8);
        ServerWorld w = p.getServerWorld();
        if (hit > 6) Tasks.later(4, () -> { if (alive(p)) { Fx.smoke(w, p.getPos(), 6, 0.6); w.spawnParticles(ParticleTypes.SMALL_GUST, p.getX(), p.getY() + 0.2, p.getZ(), 2, 0.4, 0, 0.4, 0); } });
        Tasks.later(hit, () -> {
            if (!alive(p)) return;
            Vec3d c = front(p, 1.4, 0);
            helix(w, c, new Vec3d(0, 1, 0), big ? 4.0 : 3.0, big ? 1.0 : 0.6, 3, 0, 12, WIND);
            w.spawnParticles(ParticleTypes.GUST, c.x, c.y + 1, c.z, 1, 0, 0, 0, 0);
            List<LivingEntity> ts = big ? around(p, c.add(0, 0.8, 0), 3.5) : Combat.targets(p, 3.0, 60, 3);
            for (LivingEntity t : ts) if (Combat.hit(p, t, SHADOW) != Combat.Result.MISS) windHit(w, t.getBoundingBox().getCenter(), 1.1f, Fx.Sfx.DOGON);
            if (big) for (int i = 2; i <= 30; i += 2) {   // 각성: 회오리 기둥이 1.5초 남아 들어온 상대를 띄움
                final int k = i;
                Tasks.later(i, () -> {
                    if (!alive(p)) return;
                    helix(w, c, new Vec3d(0, 1, 0), 4.0, 0.9, 3, k * 0.6, 10, WIND);
                    if (k % 6 == 0) for (LivingEntity t : around(p, c.add(0, 1, 0), 1.6)) Combat.hit(p, t, TWISTER);
                });
            }
        });
        return true;
    }

    // 공중: 아래로 내리꽂아 착지하는 순간 올려침
    private static boolean shadowAir(ServerPlayerEntity p, boolean big) {
        Motion.play(p, "pw_slam");
        Combat.afterSkill(p, 12);
        boolean[] done = {false};
        for (int i = 1; i <= 16; i++) {
            final int k = i;
            Tasks.later(i, () -> {
                if (!alive(p) || done[0]) return;
                if (!p.isOnGround() && k < 16) { Combat.move(p, new Vec3d(p.getVelocity().x * 0.5, -1.5, p.getVelocity().z * 0.5)); return; }
                done[0] = true;
                shadowQ(p, big);
            });
        }
        return true;
    }

    // ④ 비틀어 치기 (참고 '힘껏 뒤로 젖힌다' 0:16.4~0:18.1): 6 몸을 뒤로 비틂 → 10 온몸을 던져 내지름 (2칸 쏠림) → 22 돌며 가로로 후려침 (가드 깨기)
    private static final Hit TWIST1 = new Hit(1.0f, Hit.Kind.HIT, 24, 0.3, 0, 12, false, false);
    private static final Hit TWIST2 = new Hit(1.3f, Hit.Kind.KNOCK, 26, 1.4, 0.4, 14, true, false);

    private static boolean twist(ServerPlayerEntity p) {
        Motion.play(p, "pw_twist");
        Combat.afterSkill(p, 26);
        ServerWorld w = p.getServerWorld();
        Tasks.later(6, () -> { if (alive(p)) { helix(w, p.getPos().add(0, 1.2, 0), new Vec3d(0, 0, 1), 0.1, 0.8, 1, 0, 16, WIND); w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_BREEZE_CHARGE, SoundCategory.PLAYERS, 1.0f, 0.8f); } });
        lungePunch(p, 10, TWIST1);
        Tasks.later(22, () -> {
            if (!alive(p)) return;
            say(p, S_TWIST_END);
            Fx.slash(w, p, 3.0, 0, false, GLASS);
            for (LivingEntity t : Combat.targets(p, 3.2, 80, 4))
                if (Combat.hit(p, t, TWIST2) != Combat.Result.MISS) windHit(w, t.getBoundingBox().getCenter(), 1.4f, Fx.Sfx.DOGOOON);
        });
        return true;
    }

    private static void lungePunch(ServerPlayerEntity p, int at, Hit hit) {
        ServerWorld w = p.getServerWorld();
        Tasks.later(at - 1, () -> { if (alive(p)) { Vec3d fw = Combat.horizontal(p.getRotationVec(1f)); Combat.move(p, new Vec3d(fw.x * 1.1, p.getVelocity().y, fw.z * 1.1)); } });
        Tasks.later(at, () -> {
            if (!alive(p)) return;
            Vec3d fw = Combat.horizontal(p.getRotationVec(1f));
            helix(w, front(p, 0.6, 1.35), fw, 2.6, 0.35, 3, 0, 8, WIND);
            for (LivingEntity t : Combat.targets(p, 3.0, 40, 2))
                if (Combat.hit(p, t, hit) != Combat.Result.MISS) windHit(w, t.getBoundingBox().getCenter(), 1.1f, Fx.Sfx.DOGO);
        });
    }

    private static boolean twistQ(ServerPlayerEntity p) {
        Motion.play(p, "pw_twist_q");
        Combat.afterSkill(p, 9);
        lungePunch(p, 5, TWIST1);
        return true;
    }

    private static final Hit TWIST_AIR = new Hit(1.1f, Hit.Kind.DOWN, 22, 0, -0.9, 10, false, true);

    private static boolean twistAir(ServerPlayerEntity p, boolean big) {
        Motion.play(p, "pw_twist_air");
        Combat.afterSkill(p, 12);
        ServerWorld w = p.getServerWorld();
        Tasks.later(5, () -> { if (alive(p)) { Vec3d fw = Combat.horizontal(p.getRotationVec(1f)); Combat.move(p, new Vec3d(fw.x * 0.9, -1.0, fw.z * 0.9)); } });
        Tasks.later(6, () -> {
            if (!alive(p)) return;
            Vec3d c = front(p, 1.4, -0.6);
            helix(w, p.getPos().add(0, 1, 0), c.subtract(p.getPos().add(0, 1, 0)), 2.4, 0.4, 3, 0, 8, WIND);
            for (LivingEntity t : around(p, c, big ? 2.8 : 2.0)) if (Combat.hit(p, t, TWIST_AIR) != Combat.Result.MISS) windHit(w, t.getBoundingBox().getCenter(), 1.0f, Fx.Sfx.ZUDON);
        });
        return true;
    }

    // ⑤ 하늘 내려찍기 (참고 '목숨을 내놓을 자격은 있겠구나' 0:21~0:25): 6 높이 뛰어 상대 위로 → 19 내리찍음 → 다시 튀어 올라 → 34 한 번 더
    private static final Hit LEAP1 = new Hit(1.1f, Hit.Kind.DOWN, 24, 0, -0.6, 12, false, true);
    private static final Hit LEAP2 = new Hit(1.3f, Hit.Kind.DOWN, 28, 0, -0.9, 16, true, true);
    private static final Hit LEAP_WAVE = new Hit(0.6f, Hit.Kind.AIR, 18, 0.8, 0.5, 6, false, false);

    private static boolean leap(ServerPlayerEntity p, boolean big) {
        Motion.play(p, "pw_leap");
        Combat.afterSkill(p, 38);
        ServerWorld w = p.getServerWorld();
        LivingEntity aim = PillarHeat.near(p, 9);
        Tasks.later(6, () -> {
            if (!alive(p)) return;
            Vec3d to = aim != null && aim.isAlive() ? aim.getPos().subtract(p.getPos()) : Combat.horizontal(p.getRotationVec(1f)).multiply(5);
            double d = Math.min(9, Math.sqrt(to.x * to.x + to.z * to.z));
            Vec3d h = new Vec3d(to.x, 0, to.z).normalize().multiply(d / 12.0);
            Combat.move(p, new Vec3d(h.x, 0.95, h.z));
            w.spawnParticles(ParticleTypes.GUST, p.getX(), p.getY() + 0.2, p.getZ(), 1, 0, 0, 0, 0);
            Fx.smoke(w, p.getPos(), 8, 0.6);
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_BREEZE_JUMP, SoundCategory.PLAYERS, 1.4f, 0.7f);
        });
        Tasks.later(16, () -> { if (alive(p)) Combat.move(p, new Vec3d(p.getVelocity().x * 0.4, -1.6, p.getVelocity().z * 0.4)); });
        slamAt(p, 19, LEAP1, big, true);
        Tasks.later(34, () -> { if (alive(p)) Combat.move(p, new Vec3d(0, -1.6, 0)); });
        slamAt(p, 34, LEAP2, big, false);
        return true;
    }

    private static boolean leapAir(ServerPlayerEntity p, boolean big) {
        Motion.play(p, "pw_leap_air");
        Combat.afterSkill(p, 26);
        Tasks.later(4, () -> { if (alive(p)) Combat.move(p, new Vec3d(p.getVelocity().x * 0.4, -1.6, p.getVelocity().z * 0.4)); });
        slamAt(p, 6, LEAP1, big, true);
        Tasks.later(19, () -> { if (alive(p)) Combat.move(p, new Vec3d(0, -1.6, 0)); });
        slamAt(p, 20, LEAP2, big, false);
        return true;
    }

    private static void slamAt(ServerPlayerEntity p, int at, Hit hit, boolean big, boolean bounce) {
        ServerWorld w = p.getServerWorld();
        Tasks.later(at, () -> {
            if (!alive(p)) return;
            Vec3d c = front(p, 1.0, 0);
            boolean any = false;
            for (LivingEntity t : around(p, c.add(0, 0.6, 0), 2.0)) {
                if (Combat.hit(p, t, hit) == Combat.Result.MISS) continue;
                any = true;
                windHit(w, t.getBoundingBox().getCenter(), 1.2f, bounce ? Fx.Sfx.DOGON : Fx.Sfx.DOGOOON);
            }
            if (big) {   // 각성: 착지마다 바람 충격
                Fx.ring3d(w, p.getPos().add(0, 0.2, 0), new Vec3d(0, 1, 0), 0.4, 3.5, 0.3f, 8, GLASS);
                for (LivingEntity t : around(p, p.getPos().add(0, 0.5, 0), 3.0)) Combat.hit(p, t, LEAP_WAVE);
            }
            Fx.debris(w, p.getPos(), 8, 0.9);
            w.spawnParticles(ParticleTypes.GUST, c.x, c.y + 0.4, c.z, 1, 0, 0, 0, 0);
            Fx.shakeAround(w, c, 6, 0.5f);
            final double up = any ? 0.85 : 0.6;
            if (bounce) Tasks.later(1, () -> { if (alive(p)) Combat.move(p, new Vec3d(0, up, 0)); });
        });
    }

    // ⑥ 바람의 유법 (참고 아이즈 오브 헤븐 1:12~1:16): 10 팔을 펴며 바람을 두름 — 200틱
    private static boolean windMode(ServerPlayerEntity p) {
        Motion.play(p, "pw_mode");
        Combat.afterSkill(p, 20);
        ServerWorld w = p.getServerWorld();
        say(p, S_MODE);
        Tasks.later(10, () -> {
            if (!alive(p)) return;
            MODE_UNTIL.put(p.getUuid(), Combat.now() + 200);
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 200, 1, false, false));
            for (int k = 0; k < 3; k++) helix(w, p.getPos(), new Vec3d(0, 1, 0), 2.4, 0.9 + k * 0.2, 3, k * 2.1, 16, WIND);
            w.spawnParticles(ParticleTypes.GUST_EMITTER_SMALL, p.getX(), p.getY() + 1, p.getZ(), 1, 0, 0, 0, 0);
            Fx.ring3d(w, p.getPos().add(0, 0.2, 0), new Vec3d(0, 1, 0), 0.4, 4.0, 0.3f, 8, GLASS);
            for (LivingEntity t : around(p, p.getPos().add(0, 0.8, 0), 3.0)) {   // 둘레를 밀쳐냄
                Vec3d d = t.getPos().subtract(p.getPos()); d = new Vec3d(d.x, 0, d.z).normalize();
                Combat.move(t, new Vec3d(d.x * 1.0, 0.35, d.z * 1.0));
            }
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_BREEZE_WIND_BURST.value(), SoundCategory.PLAYERS, 1.6f, 0.6f);
            JobSkills.bar(p, "바람의 유법 — 10초 동안 피해 +20% · 빨라짐 · 평타에 칼바람", Formatting.AQUA);
        });
        return true;
    }

    // ⑦ 신사폭풍 (참고 '투기! 신사폭풍!!' 1:02.4~1:06.5): 12~30 두 팔을 반대로 돌림 → 32 가로 회오리 발사 → 34~56 2틱마다 갈아냄 → 56 날려보냄
    private static final Hit SAND = new Hit(0.3f, Hit.Kind.AIR, 18, 0.15, 0.25, 3, false, false);
    private static final Hit SAND_END = new Hit(1.4f, Hit.Kind.KNOCK, 30, 1.6, 0.7, 10, true, false);

    private static boolean sand(ServerPlayerEntity p, boolean big) {
        Motion.play(p, "pw_sand");
        Combat.afterSkill(p, 60);
        root(p, 1, 60);
        ServerWorld w = p.getServerWorld();
        double len = big ? 16 : 12, r = big ? 2.0 : 1.4;
        for (int i = 12; i < 31; i += 3) {
            final int k = i;
            Tasks.later(i, () -> {   // 두 팔에 회오리가 감겨 돎
                if (!alive(p)) return;
                Vec3d fw = Combat.horizontal(p.getRotationVec(1f)), rt = new Vec3d(-fw.z, 0, fw.x);
                helix(w, front(p, 0.5, 1.35).add(rt.multiply(0.3)), fw, 0.8, 0.3, 2, k, 8, WIND);
                helix(w, front(p, 0.5, 1.35).add(rt.multiply(-0.3)), fw, 0.8, 0.3, 2, -k, 8, WIND);
                w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_BREEZE_IDLE_AIR, SoundCategory.PLAYERS, 1.0f, 0.8f + k * 0.02f);
            });
        }
        Tasks.later(32, () -> { if (alive(p)) { Fx.shakeAround(w, p.getPos(), 10, 0.7f); w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_BREEZE_WIND_BURST.value(), SoundCategory.PLAYERS, 2.0f, 0.5f); } });
        for (int i = 32; i <= 56; i += 2) {
            final int k = i;
            Tasks.later(i, () -> {
                if (!alive(p)) return;
                Vec3d fw = Combat.horizontal(p.getRotationVec(1f));
                double grow = Math.min(len, (k - 30) * 1.8);
                Vec3d o = front(p, 0.8, 1.2);
                tornado(w, o, fw, grow, r, k * 0.7);
                boolean last = k == 56;
                for (LivingEntity t : inTube(p, o, fw, grow, r + 0.6)) {
                    if (Combat.hit(p, t, last ? SAND_END : SAND) == Combat.Result.MISS) continue;
                    if (!last) {   // 회오리 속에 묶여 앞으로 조금씩 끌려감
                        Vec3d c = t.getBoundingBox().getCenter();
                        w.spawnParticles(ParticleTypes.CLOUD, c.x, c.y, c.z, 3, 0.3, 0.3, 0.3, 0.05);
                    } else windHit(w, t.getBoundingBox().getCenter(), 1.5f, Fx.Sfx.DOGOOON);
                }
                if (k % 6 == 0) w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_BREEZE_SHOOT, SoundCategory.PLAYERS, 1.2f, 0.6f);
            });
        }
        return true;
    }

    /** o 에서 dir 로 len, 반지름 r 인 원통 안의 상대 */
    private static List<LivingEntity> inTube(ServerPlayerEntity p, Vec3d o, Vec3d dir, double len, double r) {
        Vec3d d = dir.normalize(), end = o.add(d.multiply(len));
        List<LivingEntity> out = new ArrayList<>();
        Box box = new Box(o, end).expand(r);
        for (Entity x : p.getServerWorld().getOtherEntities(p, box, x -> Targets.canHit(p, x))) {
            if (!(x instanceof LivingEntity le)) continue;
            Vec3d c = le.getBoundingBox().getCenter().subtract(o);
            double along = c.dotProduct(d);
            if (along < -0.5 || along > len + 0.5) continue;
            if (c.subtract(d.multiply(along)).length() <= r) out.add(le);
        }
        return out;
    }

    private static boolean sandQ(ServerPlayerEntity p, boolean big) {
        Motion.play(p, "pw_sand_q");
        Combat.afterSkill(p, 12);
        ServerWorld w = p.getServerWorld();
        for (int i = 5; i <= 13; i += 2) {
            final int k = i;
            Tasks.later(i, () -> {
                if (!alive(p)) return;
                Vec3d fw = Combat.horizontal(p.getRotationVec(1f)), o = front(p, 0.7, 1.3);
                tornado(w, o, fw, big ? 6 : 4.5, 0.8, k);
                for (LivingEntity t : inTube(p, o, fw, big ? 6 : 4.5, 1.3)) Combat.hit(p, t, k == 13 ? SAND_END : SAND);
            });
        }
        return true;
    }

    private static boolean sandAir(ServerPlayerEntity p, boolean big) {
        Motion.play(p, "pw_sand_air");
        Combat.afterSkill(p, 16);
        ServerWorld w = p.getServerWorld();
        for (int i = 8; i <= 16; i += 2) {
            final int k = i;
            Tasks.later(i, () -> {
                if (!alive(p)) return;
                Combat.move(p, new Vec3d(0, Math.max(-0.1, p.getVelocity().y), 0));   // 쏘는 동안 공중에 머묾
                Vec3d dir = Combat.horizontal(p.getRotationVec(1f)).add(0, -0.7, 0).normalize(), o = p.getPos().add(0, 1.0, 0);
                tornado(w, o, dir, big ? 9 : 7, 1.0, k);
                for (LivingEntity t : inTube(p, o, dir, big ? 9 : 7, 1.6)) Combat.hit(p, t, k == 16 ? VAC_DOWN : SAND);
            });
        }
        return true;
    }

    // 각성 ④ 바람 드릴 (참고 0:45.6~0:46.4 'ドルルル'): 6 왼손바닥으로 바람 고리 (6칸, 묶음) → 15 오른주먹에 회오리를 감아 찔러 넣음 (15~23 연타 → 날림)
    private static final Hit RING = new Hit(0.5f, Hit.Kind.HIT, 20, -0.4, 0, 4, false, false);
    private static final Hit DRILL = new Hit(0.3f, Hit.Kind.HIT, 16, 0.05, 0, 3, false, false);
    private static final Hit DRILL_END = new Hit(1.2f, Hit.Kind.KNOCK, 26, 1.4, 0.4, 12, true, false);

    private static boolean drill(ServerPlayerEntity p) {
        Motion.play(p, "pw_drill");
        Combat.afterSkill(p, 24);
        ServerWorld w = p.getServerWorld();
        Tasks.later(6, () -> {
            if (!alive(p)) return;
            Vec3d fw = Combat.horizontal(p.getRotationVec(1f));
            for (int s = 1; s <= 6; s++) {
                final int ss = s;
                Tasks.later(s / 2, () -> Fx.ring3d(w, front(p, ss, 1.3), fw, 0.2, 0.7, 0.15f, 4, GLASS));
            }
            for (LivingEntity t : inTube(p, front(p, 0.5, 1.2), fw, 6, 1.0)) Combat.hit(p, t, RING);   // 끌어당겨 묶음
        });
        Tasks.later(14, () -> { if (alive(p)) { Vec3d fw = Combat.horizontal(p.getRotationVec(1f)); Combat.move(p, new Vec3d(fw.x * 0.9, p.getVelocity().y, fw.z * 0.9)); } });
        for (int i = 15; i <= 23; i += 2) {
            final int k = i;
            Tasks.later(i, () -> {
                if (!alive(p)) return;
                Vec3d fw = Combat.horizontal(p.getRotationVec(1f));
                helix(w, front(p, 0.6, 1.35), fw, 2.4, 0.35, 4, k * 0.9, 8, WIND);
                for (LivingEntity t : Combat.targets(p, 3.0, 35, 2)) {
                    if (Combat.hit(p, t, k == 23 ? DRILL_END : DRILL) == Combat.Result.MISS) continue;
                    Vec3d c = t.getBoundingBox().getCenter();
                    if (k == 23) windHit(w, c, 1.4f, Fx.Sfx.BAKIN);
                    else { Fx.impactFlash(w, c, 0.7f); if (k == 15) Fx.word(w, c.add(0, 1, 0), Fx.Sfx.DODODO, 1.2f); }
                }
            });
        }
        return true;
    }

    // 각성 ⑥ 바람 습격: 모습을 지우고 (2) 12칸 안 상대 등 뒤에 나타나 (6) 후려침 (10)
    private static boolean ambush(ServerPlayerEntity p) {
        LivingEntity t = PillarHeat.near(p, 12);
        if (t == null) { JobSkills.bar(p, "앞에 대상이 없습니다 (12칸)", Formatting.GRAY); return false; }
        ServerWorld w = p.getServerWorld();
        Combat.afterSkill(p, 20);
        Combat.invuln(p, 8);
        say(p, S_AMBUSH);
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.INVISIBILITY, 8, 0, false, false));
        w.spawnParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + 1, p.getZ(), 24, 0.3, 0.6, 0.3, 0.06);
        w.spawnParticles(ParticleTypes.GUST, p.getX(), p.getY() + 1, p.getZ(), 1, 0, 0, 0, 0);
        Tasks.later(6, () -> {
            if (!alive(p) || !t.isAlive()) return;
            Vec3d back = t.getPos().add(Combat.horizontal(t.getRotationVec(1f)).multiply(-1.3));
            face(p, back, t.getPos());
            p.removeStatusEffect(StatusEffects.INVISIBILITY);
            Motion.play(p, "pw_sense_hit");
            var cf = Combat.of(p);
            cf.skillStart = Combat.now(); cf.skillMotion = "pw_sense_hit"; cf.skillBusyUntil = Combat.now() + 16;
            w.spawnParticles(ParticleTypes.CLOUD, back.x, back.y + 1, back.z, 20, 0.3, 0.6, 0.3, 0.05);
        });
        Tasks.later(10, () -> {
            if (!alive(p) || !t.isAlive()) return;
            if (Combat.hit(p, t, SENSE_HIT) != Combat.Result.MISS) windHit(w, t.getBoundingBox().getCenter(), 1.4f, Fx.Sfx.BAKIN);
        });
        return true;
    }

    // ═════════ 각성 '바람만을 느껴 사물을 보겠다' (64틱, 참고 0:42.8~0:45.2 · 아이즈 오브 헤븐 0:58~1:02) ═════════
    //   4 손가락을 두 눈에 → 10 찔러 넣으며 고개가 젖혀짐 (피) → 26 손을 떼고 감긴 눈으로 고개를 듦 → 30~44 머리 뿔에 바람이 감겨 돎 →
    //   46 위로 뿜어 오름: 내 화면이 바람 시야로 바뀜 → 54 자세
    static final int AWAKEN_SCENE = 64;
    private static final DustParticleEffect BLOOD = new DustParticleEffect(new Vector3f(0.6f, 0.0f, 0.02f), 0.9f);

    static void onAwaken(ServerPlayerEntity p, PlayerData.Entry e) {
        ServerWorld w = p.getServerWorld();
        Motion.play(p, "pw_awaken");
        if (ServerPlayNetworking.canSend(p, CutscenePayload.ID)) ServerPlayNetworking.send(p, new CutscenePayload("pw_awaken", p.getId(), -1));
        Combat.invuln(p, AWAKEN_SCENE);
        Combat.afterSkill(p, AWAKEN_SCENE - 6);
        Awaken.extend(p, AWAKEN_SCENE);
        VampireJob.addBlood(e, 20);
        Tasks.later(4, () -> { if (alive(p)) AlchemySounds.voice(p, S_AWAKEN, List.of(p)); });
        // 대사 둘은 겹치지 않게 이어서: 첫 대사 48틱 (4~52) → 둘째 70틱 (56~126, 컷신이 끝나도 이어짐)
        Tasks.later(56, () -> { if (alive(p)) AlchemySounds.voice(p, S_AWAKEN2, List.of(p)); });
        Tasks.later(10, () -> {
            if (!alive(p)) return;
            Vec3d eye = p.getEyePos().add(Combat.horizontal(p.getRotationVec(1f)).multiply(0.25));
            w.spawnParticles(BLOOD, eye.x, eye.y, eye.z, 14, 0.12, 0.05, 0.12, 0);
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PLAYER_HURT, SoundCategory.PLAYERS, 0.8f, 0.6f);
        });
        for (int t = 12; t < 26; t += 3) Tasks.later(t, () -> {
            if (!alive(p)) return;
            Vec3d eye = p.getEyePos().add(Combat.horizontal(p.getRotationVec(1f)).multiply(0.25));
            w.spawnParticles(BLOOD, eye.x, eye.y - 0.1, eye.z, 3, 0.08, 0.02, 0.08, 0);
        });
        for (int t = 28; t < 46; t += 2) {
            final int tt = t;
            Tasks.later(t, () -> { if (alive(p)) helix(w, p.getPos().add(0, 1.6, 0), new Vec3d(0, 1, 0), 0.8, 0.55 - (tt - 28) * 0.015, 2, tt * 0.7, 10, WIND); });
        }
        Tasks.later(46, () -> {
            if (!alive(p)) return;
            Vec3d top = p.getPos().add(0, 2.1, 0);
            helix(w, top, new Vec3d(0, 1, 0), 4.0, 0.3, 4, 0, 10, WIND);
            w.spawnParticles(ParticleTypes.GUST_EMITTER_LARGE, top.x, top.y, top.z, 1, 0, 0, 0, 0);
            Fx.ring3d(w, p.getPos().add(0, 0.2, 0), new Vec3d(0, 1, 0), 0.4, 5.0, 0.3f, 10, GLASS);
            Fx.shakeAround(w, p.getPos(), 10, 0.6f);
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_BREEZE_WIND_BURST.value(), SoundCategory.PLAYERS, 2.0f, 0.5f);
            sight(p, true);
        });
        Tasks.later(AWAKEN_SCENE - 4, () -> { if (alive(p) && Awaken.active(p)) JobSkills.bar(p, "🌀 바람만을 느껴 사물을 본다 — 주위 생물이 윤곽으로 보임 · 피해 +10% · 평타에 칼바람 · 기술이 바뀜  (G = 최종 유법)", Formatting.AQUA); });
    }

    // ═════════ 각성기 '최종 유법' (170틱, 참고 '파이널 모드(최종유법)' 1:19.4~1:27.6 — 같은 시간·흐름) ═════════
    //   0~16 머리 장식을 쥠 → 16~36 만화 칸 속 얼굴, 뿔이 솟음 → 40 고개를 젖힘 → 46 고개를 내리꽂으며 뿔에서 실 같은 바람 칼날 → 54 꽂힘 →
    //   56~118 고개로 8 자를 그리며 3틱마다 벰 → 118~128 외침 → 132·146 크게 두 번 → 150~162 2틱마다 → 164 회오리로 날림 (둘레 6칸)
    static final int ULT_SCENE = 170;
    private static final Hit ULT_BEAM = new Hit(1.0f, Hit.Kind.HIT, 200, 0, 0, 0, true, false).noParry();
    private static final Hit ULT_CUT = new Hit(0.15f, Hit.Kind.HIT, 80, 0, 0, 0, true, false).noParry();
    private static final Hit ULT_BIG = new Hit(0.6f, Hit.Kind.HIT, 80, 0, 0, 0, true, false).noParry();
    private static final Hit ULT_END = new Hit(4.0f, Hit.Kind.KNOCK, 40, 1.8, 0.9, 0, true, true).noParry();

    static boolean ultimate(ServerPlayerEntity p, PlayerData.Entry e) {
        ServerWorld w = p.getServerWorld();
        LivingEntity t = PillarHeat.near(p, 9);
        if (t == null) { JobSkills.bar(p, "앞에 대상이 없습니다 (9칸)", Formatting.GRAY); return false; }
        Motion.play(p, "pw_ult");
        Combat.invuln(p, ULT_SCENE);
        Combat.afterSkill(p, ULT_SCENE - 6);
        Awaken.extend(p, ULT_SCENE);
        List<ServerPlayerEntity> viewers = new ArrayList<>(List.of(p));
        if (t instanceof ServerPlayerEntity sp) viewers.add(sp);
        CutscenePayload pl = new CutscenePayload("pw_ult", p.getId(), t.getId());
        for (ServerPlayerEntity v : viewers) if (ServerPlayNetworking.canSend(v, CutscenePayload.ID)) ServerPlayNetworking.send(v, pl);
        // 대사는 서로 겹치지 않게 (길이: 혼설삽 50틱 · 외침 58틱 · 승부는 났다 28틱). '나의 바람의 최종 유법'은 혼설삽과 같은 말이라 뺌
        Tasks.later(16, () -> { if (alive(p)) AlchemySounds.voice(p, S_ULT2, viewers); });     // 최종 유법 「혼설삽」 (만화 칸, 16~66)
        Tasks.later(120, () -> { if (alive(p)) AlchemySounds.voice(p, S_ULT3, viewers); });    // 쉬이아아아— (120~178)
        Tasks.later(182, () -> { if (alive(p)) AlchemySounds.voice(p, S_ULT4, viewers); });    // 승부는 났다 (외침이 끝난 뒤)
        PillarHeat.root(p, 1, ULT_SCENE - 10);
        Vec3d hold = t.getPos();
        for (int i = 0; i < 164; i++) Tasks.later(i, () -> {   // 상대를 제자리에 묶어 둠
            if (!t.isAlive()) return;
            t.requestTeleport(hold.x, t.getY() < hold.y ? hold.y : t.getY(), hold.z);
            t.setVelocity(0, Math.min(0, t.getVelocity().y), 0);
            t.velocityModified = true;
        });
        // 0~44 머리 둘레에 바람이 모임 → 뿔에서 위로
        for (int i = 2; i < 44; i += 3) {
            final int k = i;
            Tasks.later(i, () -> { if (alive(p)) helix(w, p.getPos().add(0, 1.7, 0), new Vec3d(0, 1, 0), 0.6, 0.6 - k * 0.008, 2, k * 0.6, 10, WIND); });
        }
        Tasks.later(30, () -> { if (alive(p)) w.spawnParticles(ParticleTypes.END_ROD, p.getX(), p.getY() + 2.2, p.getZ(), 10, 0.05, 0.4, 0.05, 0.02); });
        // 46 뿔에서 실 같은 바람 칼날 → 54 꽂힘
        Tasks.later(46, () -> { if (alive(p)) AlchemySounds.play(p, S_ULT_SE, 1.8f); });
        for (int i = 46; i <= 54; i += 2) {
            final int k = i;
            Tasks.later(i, () -> {
                if (!alive(p) || !t.isAlive()) return;
                Vec3d horn = horn(p), c = t.getBoundingBox().getCenter();
                beam(w, horn, horn.lerp(c, (k - 44) / 10.0), WIND_GOLD);
            });
        }
        Tasks.later(54, () -> {
            if (!alive(p) || !t.isAlive()) return;
            Combat.hit(p, t, ULT_BEAM);
            Vec3d c = t.getBoundingBox().getCenter();
            Fx.impactFlash(w, c, 1.4f);
            Fx.word(w, c.add(0, 1.2, 0), Fx.Sfx.ZUBA, 1.4f);
            w.playSound(null, t.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 1.4f, 0.6f);
        });
        // 56~118 고개로 8 자를 그리며 3틱마다 벰
        for (int i = 56; i < 118; i += 3) ultCut(p, t, i, ULT_CUT, 1.0, false);
        // 118~128 외침: 머리에서 바람이 터짐
        Tasks.later(120, () -> {
            if (!alive(p)) return;
            Vec3d top = horn(p);
            Fx.rings(w, top, Combat.horizontal(p.getRotationVec(1f)), 2.0f, 2);
            w.spawnParticles(ParticleTypes.GUST_EMITTER_SMALL, top.x, top.y, top.z, 1, 0, 0, 0, 0);
        });
        // 132·146 크게 두 번
        ultCut(p, t, 132, ULT_BIG, 2.0, true);
        ultCut(p, t, 146, ULT_BIG, 2.0, true);
        // 150~162 2틱마다
        for (int i = 150; i < 163; i += 2) ultCut(p, t, i, ULT_CUT, 1.2, false);
        // 164 회오리로 날림 (둘레 6칸)
        Tasks.later(164, () -> {
            if (!alive(p)) return;
            Vec3d c = t.isAlive() ? t.getBoundingBox().getCenter() : hold.add(0, 1, 0);
            for (LivingEntity x : around(p, c, 6)) Combat.hit(p, x, ULT_END);
            for (int k = 0; k < 3; k++) helix(w, c.add(0, -1, 0), new Vec3d(0, 1, 0), 6.0, 1.2 + k * 0.6, 4, k * 2.1, 14, WIND);
            w.spawnParticles(ParticleTypes.GUST_EMITTER_LARGE, c.x, c.y, c.z, 2, 0.5, 0.5, 0.5, 0);
            w.spawnParticles(ParticleTypes.CLOUD, c.x, c.y, c.z, 100, 2.5, 1.5, 2.5, 0.15);
            Fx.ring3d(w, c.add(0, -0.8, 0), new Vec3d(0, 1, 0), 0.5, 7.0, 0.35f, 12, GLASS);
            Fx.debris(w, c.add(0, -1, 0), 24, 2.5);
            Fx.word(w, c.add(0, 2.2, 0), Fx.Sfx.DOGOOON, 2.0f);
            Fx.shakeAround(w, c, 18, 1.1f);
            w.playSound(null, BlockPos.ofFloored(c), SoundEvents.ENTITY_BREEZE_WIND_BURST.value(), SoundCategory.PLAYERS, 2.0f, 0.4f);
            AlchemySounds.play(p, S_ULT_SE, 1.8f);
            if (t.isAlive()) VampireJob.drink(p, bbc.job.Jobs.entry(p), t);
        });
        return true;
    }

    private static Vec3d horn(ServerPlayerEntity p) { return p.getEyePos().add(0, 0.35, 0).add(Combat.horizontal(p.getRotationVec(1f)).multiply(0.2)); }

    private static void beam(ServerWorld w, Vec3d a, Vec3d b, DustParticleEffect dust) {
        int n = (int) Math.max(4, a.distanceTo(b) * 6);
        for (int i = 0; i <= n; i++) { Vec3d q = a.lerp(b, i / (double) n); w.spawnParticles(dust, q.x, q.y, q.z, 1, 0, 0, 0, 0); }
    }

    /** 뿔에서 이어진 바람 칼날이 상대 둘레로 8 자를 그리며 지나감 (at 틱에 한 번 벰) */
    private static void ultCut(ServerPlayerEntity p, LivingEntity t, int at, Hit hit, double size, boolean big) {
        ServerWorld w = p.getServerWorld();
        Tasks.later(at, () -> {
            if (!alive(p) || !t.isAlive()) return;
            Vec3d c = t.getBoundingBox().getCenter(), to = c.subtract(p.getEyePos());
            Vec3d side = new Vec3d(-to.z, 0, to.x).normalize();
            double ph = at * 0.45;
            for (int i = 0; i < 24; i++) {   // 렘니스케이트 (8 자) 한 바퀴의 일부
                double s = ph + i * 0.12, den = 1 + Math.sin(s) * Math.sin(s);
                Vec3d q = c.add(side.multiply(1.4 * size * Math.cos(s) / den)).add(0, 1.0 * size * Math.sin(s) * Math.cos(s) / den, 0);
                w.spawnParticles(i % 3 == 0 ? WIND_GOLD : WIND, q.x, q.y, q.z, 1, 0, 0, 0, 0);
            }
            beam(w, horn(p), c.add(side.multiply(1.2 * size * Math.cos(ph))), WIND);
            Combat.hit(p, t, hit);
            w.spawnParticles(ParticleTypes.SWEEP_ATTACK, c.x, c.y, c.z, big ? 3 : 1, 0.3, 0.3, 0.3, 0);
            if (big) { Fx.impactFlash(w, c, 1.3f); Fx.word(w, c.add(0, 1.2, 0), Fx.Sfx.ZAN, 1.6f); Fx.shakeAround(w, c, 8, 0.6f); }
            if (at % 9 == 0) w.playSound(null, t.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 0.9f, 1.2f + (at % 4) * 0.1f);
        });
    }

    // ═════════ 도움 ═════════
    static boolean alive(ServerPlayerEntity p) { return PillarHeat.alive(p); }
    static Vec3d front(ServerPlayerEntity p, double d, double up) { return PillarHeat.front(p, d, up); }
    static void root(ServerPlayerEntity p, int from, int ticks) { PillarHeat.root(p, from, ticks); }
    static Vec3d rotY(Vec3d v, double a) { return PillarHeat.rotY(v, a); }
}
