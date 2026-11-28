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
 * 기둥 속 사내 · 빛 루트 (뱀파이어 3단계, 참고: 올스타 배틀 R '빛의 기둥 속 사내' 플레이 영상 — 기술·자세 원칙만, 이름은 우리 것).
 *
 * 두 아래팔(과 다리)에서 돋는 빛나는 칼날로 베는 검사. 빛의 유법(모드) 중엔 칼날이 빛나 피해 +20%·평타에 빛의 호가 한 번 더.
 * 기술 (비용 = 피, 7개 중 5개 장착):
 *   ① 빛 칼날 베기 — 대각선으로 내리벰 → 반대로 올려벰 · 평타 중: 한 번 · 공중: 내리벰 (다운)
 *   ② 칼날 받아치기 — 칼날을 눕혀 0.8초 받아냄 → 맞으면 상대를 꿰뚫듯 지나가 등 뒤에서 벰 · 평타 중·공중: 뒤로 빠짐
 *   ③ 발의 칼날 — 낮게 웅크려 몸을 돌리며 칼날 돋은 다리로 쓸어 띄움 · 평타 중: 바로 · 공중: 내려찍는 발
 *   ④ 두 동강 — 파고들어 멱살 → 두 번 벰 → 하늘로 올렸다 내리그어 날림 (잡기) · 평타 중: 바로 잡음 · 공중: 내리꽂으며
 *   ⑤ 내리꽂기 — 높이 뛰어 두 칼날로 내리꽂아 땅을 가름 (반지름 2.5 다운) · 공중: 바로
 *   ⑥ 빛의 유법 — 10초 모드
 *   ⑦ 빛 칼날 질주 — 8칸을 내달리며 지나치는 모두를 벰 → 끝에서 크게 가로 벰 · 평타 중: 4칸 · 공중: 비스듬히 아래로
 * 각성 '빛 칼날' (64틱): 손을 펴 얼굴 앞에 → 아래팔에서 칼날이 번쩍 돋음. 카드가 통째로 바뀜 (빛의 반사 = 둘레 눈멀게)
 * 각성기 '섬광 난도' (100틱 ≈ 참고 영상 5초): 칼날을 핥고 → 파고들며 거대한 빛의 호로 좌우 연속 → 낮게 꿰뚫고 지나가 칼날을 하늘로 → 등 뒤에서 상대가 늦게 베임
 * 햇빛: 열 루트와 같이 석화
 */
public final class PillarLight {
    private PillarLight() {}

    public static final String ROUTE = "light";
    public static final String EQV = "p3l";

    // ───────── 소리 (이벤트만 모드에 등록, 소리 파일은 따로 주는 리소스팩 'BBC 기둥 속 사내 · 빛 사운드') ─────────
    static final SoundEvent S_CUT = snd("cut"), S_CUT_Q = snd("cut_q"), S_AIR = snd("air"), S_COUNTER = snd("counter"), S_COUNTER_HIT = snd("counter_hit"),
            S_SLIP = snd("slip"), S_LEG = snd("leg"), S_GRAB = snd("grab"), S_GRAB_HIT = snd("grab_hit"), S_GRAB_END = snd("grab_end"),
            S_DROP = snd("drop"), S_MODE = snd("mode"), S_MODE_END = snd("mode_end"), S_RUSH = snd("rush"), S_RUSH_Q = snd("rush_q"),
            S_FLASH = snd("flash"), S_AWAKEN = snd("awaken"), S_ULT1 = snd("ult1"), S_ULT2 = snd("ult2"), S_ULT3 = snd("ult3"),
            S_PETRIFY = snd("petrify"), S_M1 = snd("m1"), S_M1_HEAVY = snd("m1_heavy"), S_M1_AIR = snd("m1_air"),
            S_DASH = snd("dash"), S_EVADE = snd("evade"), S_HURT = snd("hurt"), S_HURT_BIG = snd("hurt_big"), S_DOWN = snd("down"),
            S_GETUP = snd("getup"), S_GUARD_BREAK = snd("guard_break");

    private static SoundEvent snd(String path) {
        Identifier id = Identifier.of(Bbc.MOD_ID, "pillar_light." + path);
        return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id));
    }
    private static void say(ServerPlayerEntity p, SoundEvent s) { AlchemySounds.play(p, s, 1.6f); }

    // ───────── 효과음 (직접 합성한 소리, 모드에 들어 있음 — tools/make_light_se.py) ─────────
    static final SoundEvent SE_SHING = snd2("shing"), SE_SWISH = snd2("swish"), SE_CUT = snd2("cut"), SE_FLASH = snd2("flash"), SE_BIG = snd2("big"), SE_SHINE = snd2("shine");
    private static SoundEvent snd2(String path) {
        Identifier id = Identifier.of(Bbc.MOD_ID, "pillar_light_se." + path);
        return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id));
    }
    static void se(ServerWorld w, Vec3d at, SoundEvent s, float vol, float pitch) {
        w.playSound(null, at.x, at.y, at.z, s, SoundCategory.PLAYERS, vol, pitch * (0.95f + w.random.nextFloat() * 0.1f));
    }

    // ───────── 평타: 오른 가로베기 → 왼 되베기 → 내리베기 → 발의 칼날 → 두 팔 X 펼쳐 베기 (막타 선딜 6) ─────────
    static final bbc.combat.Weapon.M1Profile M1 = new bbc.combat.Weapon.M1Profile(5, 4, 2, 1.1f, 0.3, "pl_", 6);

    static final JobSkills.Skill[] LIST = {
            new JobSkills.Skill("빛 칼날 베기", "베기", 3, 15, 7, "아래팔 칼날로 대각선 내리벰 → 반대로 올려벰 · 평타 중: 한 번 · 공중: 내리벰 (다운) · 유법 중: 빛의 호가 더 멀리"),
            new JobSkills.Skill("칼날 받아치기", "받기", 3, 15, 12, "칼날을 눕혀 0.8초 받아냄 — 맞으면 상대를 꿰뚫듯 지나가 등 뒤에서 벰 · 평타 중·공중: 뒤로 빠짐"),
            new JobSkills.Skill("발의 칼날", "발칼", 3, 15, 9, "낮게 웅크려 몸을 돌리며 칼날 돋은 다리로 쓸어 띄움 (둘레 3칸) · 평타 중: 바로 · 공중: 내려찍는 발 (다운)"),
            new JobSkills.Skill("두 동강", "동강", 3, 20, 12, "낮게 파고들어 멱살 → 두 번 벰 → 칼날을 하늘로 올렸다 내리그어 날림 (잡기 · 가드 무시) · 평타 중: 바로 잡음 · 공중: 내리꽂으며"),
            new JobSkills.Skill("내리꽂기", "꽂기", 3, 20, 14, "높이 뛰어 두 칼날로 내리꽂아 땅을 가름 (반지름 2.5칸 다운) · 공중: 바로"),
            new JobSkills.Skill("빛의 유법", "유법", 3, 20, 30, "칼날이 빛남 — 10초 동안 주는 피해 +20% · 평타에 빛의 호 · 칼날 베기가 길어짐"),
            new JobSkills.Skill("빛 칼날 질주", "질주", 3, 30, 16, "8칸을 내달리며 지나치는 모두를 벰 → 끝에서 크게 가로 벰 (날림) · 평타 중: 4칸 · 공중: 비스듬히 아래로"),
    };

    /** 각성 중 기술 카드 */
    static final JobSkills.Skill[] AWAKE = {
            new JobSkills.Skill("섬광 베기", "섬광", 3, 15, 5, "베기 둘 + 빛의 호가 앞으로 6칸 날아감 · 평타 중: 한 번 · 공중: 내리벰"),
            new JobSkills.Skill("섬광 받아치기", "받기", 3, 15, 10, "받아치면 꿰뚫고 지나가며 상대가 2초 눈이 멂 · 평타 중·공중: 뒤로 빠짐"),
            new JobSkills.Skill("발의 칼날 · 회전", "발칼", 3, 15, 7, "세 바퀴 돌며 쓸어 띄움 (둘레 3.5칸) · 공중: 내려찍는 발"),
            new JobSkills.Skill("여섯 동강", "동강", 3, 20, 10, "멱살 → 다섯 번 벰 → 내리그어 날림 · 공중: 내리꽂으며"),
            new JobSkills.Skill("내리꽂기 · 빛기둥", "꽂기", 3, 20, 12, "내리꽂은 자리에 빛 칼날이 원으로 솟음 (반지름 3.5칸)"),
            new JobSkills.Skill("빛의 반사", "반사", 3, 20, 14, "두 칼날로 빛을 모아 번쩍 — 8칸 안 모두 눈이 멀고 0.6초 굳음"),
            new JobSkills.Skill("빛 칼날 대질주", "질주", 3, 30, 12, "12칸을 세 번 오가며 벰 → 마지막에 크게 날림 · 평타 중: 4칸 · 공중: 비스듬히 아래로"),
    };

    static JobSkills.Skill[] list(ServerPlayerEntity p) { return Awaken.active(p) ? AWAKE : LIST; }

    /** 클래스 로딩 = 소리 이벤트 등록 (모드 초기화에서 불러야 함 — 레지스트리가 얼기 전에) */
    public static void register() {}

    // ───────── 상태 ─────────
    private static final Map<UUID, Long> MODE_UNTIL = new HashMap<>();

    public static boolean is(PlayerData.Entry e) { return VampireJob.stage(e) == 3 && ROUTE.equals(e.str(VampireJob.P + "route")); }
    static boolean mode(ServerPlayerEntity p) { Long u = MODE_UNTIL.get(p.getUuid()); return u != null && Combat.now() < u; }

    /** 주는 피해 배수: 유법 +20%, 각성 +10% */
    public static float mul(ServerPlayerEntity p) {
        float m = 1f;
        if (mode(p)) m += 0.2f;
        if (Awaken.active(p)) m += 0.1f;
        return m;
    }

    public static String hud(ServerPlayerEntity p) {
        return (mode(p) ? " · 빛의 유법" : "") + (PillarHeat.petrified(p) ? " · 석화" : "");
    }

    static void clear(UUID id) { MODE_UNTIL.remove(id); LAST_VOICE.remove(id); }

    private static final Map<UUID, Long> LAST_VOICE = new HashMap<>();
    /** 전투 동작 목소리 (대시 · 회피 · 맞음 · 크게 맞음 · 쓰러짐 · 일어남 · 가드 깨짐). 기술 대사와 안 겹치게 맞음은 1.5초에 한 번, 일부만 */
    static void onAction(ServerPlayerEntity p, String kind) {
        long now = Combat.now();
        Long last = LAST_VOICE.get(p.getUuid());
        boolean busy = last != null && now - last < 30;
        SoundEvent s = switch (kind) {
            case "dash" -> p.getRandom().nextFloat() < 0.35f ? S_DASH : null;
            case "evade" -> S_EVADE;
            case "hurt" -> !busy && p.getRandom().nextFloat() < 0.4f ? S_HURT : null;
            case "hurt_big" -> busy && last != null && now - last < 8 ? null : S_HURT_BIG;
            case "down" -> S_DOWN;
            case "getup" -> S_GETUP;
            case "guard_break" -> S_GUARD_BREAK;
            default -> null; };
        if (s == null) return;
        if (kind.equals("dash") || kind.equals("hurt")) { if (busy) return; }
        LAST_VOICE.put(p.getUuid(), now);
        AlchemySounds.play(p, s, 1.3f);
    }

    /** 매초: 햇빛 석화 · 유법·각성 중 칼날 빛 */
    static void second(ServerPlayerEntity p, boolean sunlit) {
        ServerWorld w = p.getServerWorld();
        PillarHeat.sun(p, sunlit, S_PETRIFY);
        if (mode(p) || Awaken.active(p)) { glow(w, p); Motion.play(p, "#shine:on"); }
        else if (MODE_UNTIL.remove(p.getUuid()) != null) {
            Motion.play(p, "#shine:off");
            say(p, S_MODE_END);
            JobSkills.bar(p, "빛의 유법이 풀렸다", Formatting.GRAY);
        }
    }

    // ═════════ 연출 도구 ═════════
    static final DustParticleEffect LIGHT = new DustParticleEffect(new Vector3f(0.9f, 0.96f, 1f), 1.0f);
    static final DustParticleEffect LIGHT_GOLD = new DustParticleEffect(new Vector3f(0.55f, 0.78f, 1f), 1.2f);   // 푸른 빛 (이름은 예전 그대로)
    /** 프리즘 반짝 (분홍 · 노랑 · 하늘 · 보라) */
    static final DustParticleEffect[] PRISM = {
            new DustParticleEffect(new Vector3f(1f, 0.55f, 0.85f), 0.8f), new DustParticleEffect(new Vector3f(1f, 0.95f, 0.45f), 0.8f),
            new DustParticleEffect(new Vector3f(0.45f, 0.95f, 1f), 0.8f), new DustParticleEffect(new Vector3f(0.7f, 0.55f, 1f), 0.8f)};
    private static final BlockState GLASS = Fx.WHITE, GOLD = net.minecraft.block.Blocks.LIGHT_BLUE_STAINED_GLASS.getDefaultState();

    /** 반짝 (키랏): 네 갈래 빛줄기 별 + 가운데 섬광 + 무지개 알갱이 */
    static void glint(ServerWorld w, Vec3d c, float size) {
        double r = 0.55 * size;
        for (int k = 0; k < 4; k++) {
            double a = Math.PI / 4 * k + Math.PI / 8;
            Vec3d d = new Vec3d(Math.cos(a), Math.sin(a), 0);
            int n = (int) (6 * size);
            for (int i = 1; i <= n; i++) { Vec3d q = c.add(d.multiply(r * i / n * (k % 2 == 0 ? 1.0 : 0.55))); Vec3d q2 = c.subtract(d.multiply(r * i / n * (k % 2 == 0 ? 1.0 : 0.55)));
                w.spawnParticles(LIGHT, q.x, q.y, q.z, 1, 0, 0, 0, 0); w.spawnParticles(LIGHT, q2.x, q2.y, q2.z, 1, 0, 0, 0, 0); }
        }
        w.spawnParticles(ParticleTypes.END_ROD, c.x, c.y, c.z, (int) (4 * size), 0.05, 0.05, 0.05, 0.06);
        w.spawnParticles(ParticleTypes.FLASH, c.x, c.y, c.z, 1, 0, 0, 0, 0);
        for (DustParticleEffect d : PRISM) w.spawnParticles(d, c.x, c.y, c.z, (int) (2 * size), 0.3 * size, 0.3 * size, 0.3 * size, 0);
    }

    /** 두 아래팔 칼날이 빛남 (손목 둘레에 빛 알갱이) */
    static void glow(ServerWorld w, ServerPlayerEntity p) {
        Vec3d fw = Combat.horizontal(p.getRotationVec(1f)), rt = new Vec3d(-fw.z, 0, fw.x);
        for (int s = -1; s <= 1; s += 2) {
            Vec3d q = p.getPos().add(0, 1.0, 0).add(rt.multiply(0.4 * s)).add(fw.multiply(0.2));
            w.spawnParticles(ParticleTypes.END_ROD, q.x, q.y, q.z, 1, 0.05, 0.15, 0.05, 0.005);
            w.spawnParticles(LIGHT, q.x, q.y, q.z, 2, 0.05, 0.2, 0.05, 0);
        }
    }

    /** 빛의 칼날 호 (흰 + 금 테두리 + 반짝) */
    static void blade(ServerWorld w, ServerPlayerEntity p, double radius, double tilt, boolean reverse) {
        Fx.slash(w, p, radius, tilt, reverse, GLASS);
        Fx.slash(w, p, radius * 1.08, tilt, reverse, GOLD);
        arcSparkle(w, p, radius * 1.12, tilt, reverse);
        se(w, p.getPos(), SE_SWISH, 1.1f, 1.0f);
    }

    /** 베인 자리: 흰 섬광 + 빛 칼자국 + 반짝(키랏) + 무지개 알갱이 */
    static void cutHit(ServerWorld w, Vec3d c, float size, Fx.Sfx word) {
        Fx.impactFlash(w, c, 0.9f * size);
        Fx.inkSlashes(w, c, 2, 0.8f * size);
        glint(w, c.add(0.15, 0.25, 0), 0.8f * size);
        w.spawnParticles(ParticleTypes.END_ROD, c.x, c.y, c.z, (int) (6 * size), 0.15, 0.25, 0.15, 0.12);
        w.spawnParticles(LIGHT_GOLD, c.x, c.y, c.z, (int) (8 * size), 0.3 * size, 0.3 * size, 0.3 * size, 0);
        if (word != null) Fx.word(w, c.add(0, 1.0, 0), word, 1.0f + 0.2f * size);
        se(w, c, SE_CUT, 1.0f + 0.2f * size, 1.0f);
    }

    /** 칼날 호를 따라 빛이 흐르며 무지개 알갱이가 흩어지고, 끝에서 반짝 */
    static void arcSparkle(ServerWorld w, ServerPlayerEntity p, double radius, double tilt, boolean reverse) {
        Vec3d f = Combat.horizontal(p.getRotationVec(1f)), r = new Vec3d(-f.z, 0, f.x);
        double t = Math.toRadians(tilt);
        Vec3d side = r.multiply(Math.cos(t)).add(0, Math.sin(t), 0), o = p.getPos().add(0, 1.15, 0);
        int n = 12;
        for (int i = 0; i < n; i++) {
            final int k = i;
            Tasks.later(i / 4, () -> {
                double u = (reverse ? n - 1 - k : k) / (double) (n - 1), a = Math.PI * (u - 0.5) * 0.95;
                Vec3d q = o.add(f.multiply(Math.cos(a) * radius)).add(side.multiply(-Math.sin(a) * radius));
                w.spawnParticles(PRISM[k % PRISM.length], q.x, q.y, q.z, 1, 0.05, 0.05, 0.05, 0);
                w.spawnParticles(ParticleTypes.END_ROD, q.x, q.y, q.z, 1, 0.02, 0.02, 0.02, 0.01);
            });
        }
        Tasks.later(3, () -> {
            double a = Math.PI * ((reverse ? 0 : 1) - 0.5) * 0.95;
            glint(w, o.add(f.multiply(Math.cos(a) * radius)).add(side.multiply(-Math.sin(a) * radius)), 0.7f);
        });
    }

    /** a 에서 b 까지 빛 선 */
    static void ray(ServerWorld w, Vec3d a, Vec3d b) {
        int n = (int) Math.max(4, a.distanceTo(b) * 5);
        for (int i = 0; i <= n; i++) { Vec3d q = a.lerp(b, i / (double) n); w.spawnParticles(i % 4 == 0 ? LIGHT_GOLD : LIGHT, q.x, q.y, q.z, 1, 0, 0, 0, 0); }
    }

    private static List<LivingEntity> around(ServerPlayerEntity p, Vec3d c, double r) {
        List<LivingEntity> out = new ArrayList<>();
        for (Entity x : p.getServerWorld().getOtherEntities(p, new Box(c, c).expand(r), x -> Targets.canHit(p, x)))
            if (x instanceof LivingEntity le && le.squaredDistanceTo(c) <= r * r) out.add(le);
        return out;
    }

    /** 눈이 멂: 플레이어는 실명, 몹은 놓침 + 느려짐 */
    private static void blind(LivingEntity t, int ticks) {
        t.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, ticks, 0, false, true));
        t.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, ticks, 1, false, true));
        if (t instanceof MobEntity m) m.setTarget(null);
    }

    // ═════════ 평타 연출 ═════════
    private static final Hit LIGHT_CUT = new Hit(0.3f, Hit.Kind.HIT, 8, 0.2, 0, 2, false, false);

    static void m1Fx(ServerPlayerEntity p, String motion) {
        ServerWorld w = p.getServerWorld();
        Vec3d fw = Combat.horizontal(p.getRotationVec(1f));
        var f = Combat.of(p);
        LivingEntity t = f.lastTargetAt == Combat.now() && f.lastTarget != null && w.getEntity(f.lastTarget) instanceof LivingEntity le ? le : null;
        Vec3d c = t != null ? t.getBoundingBox().getCenter() : p.getPos().add(fw.multiply(1.4)).add(0, 1.1, 0);
        SoundEvent v = switch (motion) {
            case "pl_1", "pl_2", "pl_3", "pl_4" -> p.getRandom().nextFloat() < 0.55f ? S_M1 : null;
            case "pl_5", "pl_rise" -> S_M1_HEAVY;
            case "pl_a1", "pl_a2", "pl_a3" -> p.getRandom().nextFloat() < 0.6f ? S_M1_AIR : null;
            case "pl_slam" -> S_AIR;
            default -> null; };
        if (v != null) AlchemySounds.play(p, v, 1.3f);
        switch (motion) {   // 칼날 궤적 (헛쳐도)
            case "pl_1" -> Fx.slash(w, p, 2.0, -10, false, GLASS);
            case "pl_2" -> Fx.slash(w, p, 2.0, 10, true, GLASS);
            case "pl_3" -> Fx.slash(w, p, 2.0, 70, false, GLASS);
            case "pl_4" -> Fx.slash(w, p, 1.8, -5, false, GLASS);
            case "pl_5" -> { Fx.slash(w, p, 2.4, 35, false, GLASS); Fx.slash(w, p, 2.4, -35, true, GOLD); if (t != null) cutHit(w, c, 1.3f, Fx.Sfx.ZAN); }
            case "pl_rise" -> { Fx.slash(w, p, 2.2, 90, true, GLASS); if (t != null) cutHit(w, c, 1.1f, null); }
            case "pl_a1", "pl_a2" -> Fx.slash(w, p, 1.8, 0, motion.equals("pl_a2"), GLASS);
            case "pl_a3", "pl_slam" -> Fx.slash(w, p, 2.0, 80, false, GLASS);
            default -> { }
        }
        if (t != null && !motion.equals("pl_5") && !motion.equals("pl_rise")) {
            w.spawnParticles(ParticleTypes.END_ROD, c.x, c.y, c.z, 3, 0.1, 0.2, 0.1, 0.08);
            w.spawnParticles(ParticleTypes.CRIT, c.x, c.y, c.z, 4, 0.2, 0.2, 0.2, 0.2);
        }
        if (t != null && (mode(p) || Awaken.active(p))) {   // 유법·각성: 평타에 빛의 호가 한 번 더
            final LivingEntity tt = t;
            Tasks.later(2, () -> {
                if (!alive(p) || !tt.isAlive()) return;
                Combat.hit(p, tt, LIGHT_CUT);
                Fx.slash(w, p, 2.4, (Combat.now() % 2 == 0) ? 40 : -40, Combat.now() % 2 == 0, GOLD);
                w.spawnParticles(ParticleTypes.END_ROD, tt.getX(), tt.getY() + 1, tt.getZ(), 4, 0.2, 0.3, 0.2, 0.06);
            });
        }
    }

    // ═════════ 기술 ═════════
    static boolean use(ServerPlayerEntity p, PlayerData.Entry e, int idx) {
        boolean air = !p.isOnGround(), chain = Combat.inM1Chain(p), aw = Awaken.active(p), md = mode(p);
        boolean ok = switch (idx) {
            case 0 -> air ? cutAir(p) : chain ? cutQ(p) : cut(p, aw, md);
            case 1 -> air || chain ? slip(p) : counter(p, aw);
            case 2 -> air ? legAir(p) : chain ? legQ(p) : leg(p, aw);
            case 3 -> air ? grabAir(p, aw) : chain ? grabNear(p, aw) : grab(p, aw);
            case 4 -> air ? dropAir(p, aw) : drop(p, aw);
            case 5 -> aw ? flash(p) : lightMode(p);
            case 6 -> air ? rushAir(p) : chain ? rush(p, 4, false, "pl_rush_q") : rush(p, aw ? 12 : 8, aw, "pl_rush");
            default -> false;
        };
        if (ok) {
            SoundEvent s = switch (idx) {   // ②④⑥ 일부는 기술 안에서
                case 0 -> air ? S_AIR : chain ? S_CUT_Q : S_CUT;
                case 2 -> air ? S_AIR : S_LEG;
                case 4 -> air ? S_AIR : S_DROP;
                case 6 -> air ? S_AIR : chain ? S_RUSH_Q : S_RUSH;
                default -> null; };
            if (s != null) say(p, s);
        }
        return ok;
    }

    // ① 빛 칼날 베기: 5 대각선 내리벰 → 11 반대로 올려벰 (앞 3.5칸 부채꼴, 유법 4.5) · 각성: 끝에 빛의 호가 6칸 날아감
    private static final Hit CUT1 = new Hit(0.9f, Hit.Kind.HIT, 18, 0.15, 0, 8, false, false);
    private static final Hit CUT2 = new Hit(1.1f, Hit.Kind.KNOCK, 22, 0.9, 0.3, 10, false, false);
    private static final Hit WAVE = new Hit(0.7f, Hit.Kind.HIT, 16, 0.4, 0, 6, false, false);

    private static boolean cut(ServerPlayerEntity p, boolean aw, boolean md) {
        Motion.play(p, "pl_cut");
        Combat.afterSkill(p, 20);
        ServerWorld w = p.getServerWorld();
        double reach = md || aw ? 4.5 : 3.5;
        Tasks.later(4, () -> { if (alive(p)) Combat.move(p, Combat.horizontal(p.getRotationVec(1f)).multiply(0.5)); });
        int[] at = {5, 11};
        for (int k = 0; k < 2; k++) {
            final int kk = k;
            Tasks.later(at[k], () -> {
                if (!alive(p)) return;
                blade(w, p, reach, kk == 0 ? 50 : -40, kk == 1);
                for (LivingEntity t : Combat.targets(p, reach, 70, 5))
                    if (Combat.hit(p, t, kk == 0 ? CUT1 : CUT2) != Combat.Result.MISS) cutHit(w, t.getBoundingBox().getCenter(), kk == 0 ? 0.9f : 1.2f, kk == 1 ? Fx.Sfx.ZAN : null);
            });
        }
        if (aw) Tasks.later(12, () -> { if (alive(p)) flyingArc(p, 6); });
        return true;
    }

    /** 빛의 호가 앞으로 날아감 (1틱 1칸, 닿은 상대마다 한 번) */
    private static void flyingArc(ServerPlayerEntity p, int len) {
        ServerWorld w = p.getServerWorld();
        Vec3d fw = Combat.horizontal(p.getRotationVec(1f)), rt = new Vec3d(-fw.z, 0, fw.x), o = p.getPos().add(0, 1.0, 0);
        Set<Integer> got = new HashSet<>();
        for (int i = 1; i <= len; i++) {
            final int k = i;
            Tasks.later(i, () -> {
                if (!alive(p)) return;
                Vec3d c = o.add(fw.multiply(k));
                for (int j = -4; j <= 4; j++) { Vec3d q = c.add(rt.multiply(j * 0.25)).add(fw.multiply(-Math.abs(j) * 0.08)); w.spawnParticles(j % 2 == 0 ? LIGHT_GOLD : LIGHT, q.x, q.y + j * 0.05, q.z, 1, 0, 0, 0, 0); }
                for (LivingEntity t : around(p, c, 1.3)) if (got.add(t.getId()) && Combat.hit(p, t, WAVE) != Combat.Result.MISS) cutHit(w, t.getBoundingBox().getCenter(), 0.8f, null);
            });
        }
    }

    private static boolean cutQ(ServerPlayerEntity p) {
        Motion.play(p, "pl_cut_q");
        Combat.afterSkill(p, 8);
        ServerWorld w = p.getServerWorld();
        Tasks.later(4, () -> {
            if (!alive(p)) return;
            blade(w, p, 3.2, 50, false);
            for (LivingEntity t : Combat.targets(p, 3.2, 60, 3)) if (Combat.hit(p, t, CUT1) != Combat.Result.MISS) cutHit(w, t.getBoundingBox().getCenter(), 1f, null);
        });
        return true;
    }

    private static final Hit CUT_DOWN = new Hit(1.0f, Hit.Kind.DOWN, 20, 0, -0.7, 8, false, true);

    private static boolean cutAir(ServerPlayerEntity p) {
        Motion.play(p, "pl_cut_air");
        Combat.afterSkill(p, 12);
        ServerWorld w = p.getServerWorld();
        Tasks.later(5, () -> {
            if (!alive(p)) return;
            blade(w, p, 3.0, 80, false);
            Vec3d c = front(p, 1.4, -0.8);
            for (LivingEntity t : around(p, c, 2.6)) if (Combat.hit(p, t, CUT_DOWN) != Combat.Result.MISS) cutHit(w, t.getBoundingBox().getCenter(), 1f, Fx.Sfx.ZUBA);
        });
        return true;
    }

    // ② 칼날 받아치기: 3~19 받아냄 (앞 5칸) → 맞으면 상대를 꿰뚫듯 지나가 등 뒤에 서서 벰 (4) · 각성: 2초 눈이 멂
    private static final Hit COUNTER_HIT = new Hit(1.5f, Hit.Kind.KNOCK, 26, 1.0, 0.4, 0, true, false).noParry();

    private static boolean counter(ServerPlayerEntity p, boolean aw) {
        Motion.play(p, "pl_counter");
        Combat.afterSkill(p, 22);
        ServerWorld w = p.getServerWorld();
        say(p, S_COUNTER);
        for (int i = 3; i < 19; i += 4) Tasks.later(i, () -> { if (alive(p)) glow(w, p); });
        Tasks.later(3, () -> {
            if (!alive(p)) return;
            Combat.counterWindow(p, 16, false, 5.0, atk -> {
                if (!alive(p)) return;
                Vec3d from = p.getPos();
                Vec3d back = atk.getPos().add(atk.getPos().subtract(p.getPos()).multiply(1, 0, 1).normalize().multiply(1.4));
                face(p, back, atk.getPos());
                ray(w, from.add(0, 1, 0), back.add(0, 1, 0));
                Motion.play(p, "pl_counter_hit");
                say(p, S_COUNTER_HIT);
                var cf = Combat.of(p);
                cf.skillStart = Combat.now(); cf.skillMotion = "pl_counter_hit"; cf.skillBusyUntil = Combat.now() + 14;
                Combat.afterSkill(p, 12);
                Combat.invuln(p, 8);
                Fx.flash(p, 0.6f);
                Tasks.later(4, () -> {
                    if (!alive(p) || !atk.isAlive()) return;
                    Fx.slash(w, p, 2.6, 20, false, GLASS);
                    if (Combat.hit(p, atk, COUNTER_HIT) == Combat.Result.MISS) return;
                    cutHit(w, atk.getBoundingBox().getCenter(), 1.4f, Fx.Sfx.ZAN);
                    if (aw) blind(atk, 40);
                });
            });
        });
        return true;
    }

    /** 평타 중·공중: 칼날을 세운 채 뒤로 훌쩍 빠짐 (0.3초 무적) */
    private static boolean slip(ServerPlayerEntity p) {
        Motion.play(p, "pl_slip");
        Combat.afterSkill(p, 4);
        Combat.invuln(p, 6);
        say(p, S_SLIP);
        ServerWorld w = p.getServerWorld();
        Vec3d fw = Combat.horizontal(p.getRotationVec(1f));
        Vec3d dir = p.sidewaysSpeed != 0 ? (p.sidewaysSpeed < 0 ? new Vec3d(-fw.z, 0, fw.x) : new Vec3d(fw.z, 0, -fw.x)).add(fw.multiply(-0.5)).normalize() : fw.multiply(-1);
        for (int i = 1; i <= 4; i++) Tasks.later(i, () -> {
            if (!alive(p)) return;
            Combat.move(p, new Vec3d(dir.x * 0.9, Math.min(0.1, p.getVelocity().y), dir.z * 0.9));
            w.spawnParticles(LIGHT, p.getX(), p.getY() + 1, p.getZ(), 3, 0.2, 0.4, 0.2, 0);
        });
        return true;
    }

    // ③ 발의 칼날: 4 웅크려 손을 짚음 → 8 몸을 돌리며 다리 칼날로 둘레를 쓸어 띄움 (둘레 3칸) · 각성: 8·12·16 세 바퀴 (3.5칸)
    private static final Hit LEG = new Hit(1.1f, Hit.Kind.AIR, 24, 0.3, 0.9, 8, false, false);

    private static boolean leg(ServerPlayerEntity p, boolean aw) {
        Motion.play(p, "pl_leg");
        Combat.afterSkill(p, aw ? 22 : 16);
        ServerWorld w = p.getServerWorld();
        double r = aw ? 3.5 : 3.0;
        for (int k = 0; k < (aw ? 3 : 1); k++) {
            final int kk = k;
            Tasks.later(8 + k * 4, () -> {
                if (!alive(p)) return;
                Fx.ring3d(w, p.getPos().add(0, 0.35, 0), new Vec3d(0, 1, 0), 0.6, r, 0.25f, 6, GLASS);
                for (int i = 0; i < 16; i++) { double a = Math.PI * 2 * i / 16 + kk; w.spawnParticles(LIGHT, p.getX() + Math.cos(a) * r * 0.8, p.getY() + 0.4, p.getZ() + Math.sin(a) * r * 0.8, 1, 0, 0, 0, 0); }
                se(w, p.getPos(), SE_SWISH, 1.2f, 0.85f);
                for (LivingEntity t : around(p, p.getPos().add(0, 0.6, 0), r))
                    if (Combat.hit(p, t, kk == (aw ? 2 : 0) ? LEG : LEG.withDamage(0.5f).withStun(16)) != Combat.Result.MISS) cutHit(w, t.getBoundingBox().getCenter(), 1f, kk == 0 ? Fx.Sfx.ZUBA : null);
            });
        }
        return true;
    }

    private static boolean legQ(ServerPlayerEntity p) {
        Motion.play(p, "pl_leg_q");
        Combat.afterSkill(p, 8);
        ServerWorld w = p.getServerWorld();
        Tasks.later(4, () -> {
            if (!alive(p)) return;
            Fx.slash(w, p, 2.6, -8, false, GLASS);
            for (LivingEntity t : Combat.targets(p, 3.0, 80, 3)) if (Combat.hit(p, t, LEG) != Combat.Result.MISS) cutHit(w, t.getBoundingBox().getCenter(), 1f, null);
        });
        return true;
    }

    private static final Hit LEG_DOWN = new Hit(1.1f, Hit.Kind.DOWN, 22, 0, -0.9, 10, false, true);

    private static boolean legAir(ServerPlayerEntity p) {
        Motion.play(p, "pl_leg_air");
        Combat.afterSkill(p, 12);
        ServerWorld w = p.getServerWorld();
        Tasks.later(5, () -> {
            if (!alive(p)) return;
            Fx.slash(w, p, 2.4, 90, false, GLASS);
            for (LivingEntity t : around(p, front(p, 1.2, -0.6), 2.2)) if (Combat.hit(p, t, LEG_DOWN) != Combat.Result.MISS) cutHit(w, t.getBoundingBox().getCenter(), 1f, Fx.Sfx.ZUBA);
        });
        return true;
    }

    // ④ 두 동강: 2~7 낮게 파고들며 (1.4칸/틱) 닿으면 멱살 → 6·12 벰 → 20 하늘에서 내리그어 날림 · 각성: 4·7·10·13·16 다섯 번
    private static final Hit GRAB = new Hit(0.4f, Hit.Kind.HIT, 34, 0, 0, 0, true, false).noParry();
    private static final Hit GRAB_CUT = new Hit(0.7f, Hit.Kind.HIT, 30, 0, 0, 0, true, false).noParry();
    private static final Hit GRAB_END = new Hit(1.8f, Hit.Kind.KNOCK, 30, 1.5, 0.5, 0, true, false).noParry();

    private static boolean grab(ServerPlayerEntity p, boolean aw) {
        Motion.play(p, "pl_grab");
        Combat.afterSkill(p, 14);
        long t0 = Combat.now();
        ServerWorld w = p.getServerWorld();
        say(p, S_GRAB);
        LivingEntity[] got = {null};
        for (int i = 2; i <= 7; i++) Tasks.later(i, () -> {
            if (!alive(p) || got[0] != null || !"pl_grab".equals(Motion.startedSince(p, t0))) return;
            Vec3d f = Combat.horizontal(p.getRotationVec(1f));
            Combat.move(p, new Vec3d(f.x * 1.4, Math.min(0, p.getVelocity().y), f.z * 1.4));
            w.spawnParticles(LIGHT, p.getX(), p.getY() + 0.8, p.getZ(), 4, 0.2, 0.3, 0.2, 0);
            LivingEntity t = Combat.targets(p, 2.2, 50, 1).stream().findFirst().orElse(null);
            if (t == null) return;
            got[0] = t;
            Combat.move(p, new Vec3d(0, Math.min(0, p.getVelocity().y), 0));
            if (Combat.hit(p, t, GRAB) != Combat.Result.HIT) return;
            seize(p, w, t, aw);
        });
        return true;
    }

    private static boolean grabNear(ServerPlayerEntity p, boolean aw) {
        Motion.play(p, "pl_grab");
        Combat.afterSkill(p, 8);
        long t0 = Combat.now();
        ServerWorld w = p.getServerWorld();
        say(p, S_GRAB);
        Tasks.later(4, () -> {
            if (!alive(p) || !"pl_grab".equals(Motion.startedSince(p, t0))) return;
            LivingEntity t = Combat.targets(p, 3.0, 60, 1).stream().findFirst().orElse(null);
            if (t == null || Combat.hit(p, t, GRAB) != Combat.Result.HIT) return;
            seize(p, w, t, aw);
        });
        return true;
    }

    /** 멱살을 잡은 뒤: 앞 1.1칸에 붙들고 벰 → 날림 */
    private static void seize(ServerPlayerEntity p, ServerWorld w, LivingEntity t, boolean aw) {
        Motion.play(p, "pl_grab_hit");
        var cf = Combat.of(p);
        cf.skillStart = Combat.now(); cf.skillMotion = "pl_grab_hit"; cf.skillBusyUntil = Combat.now() + 28;
        Combat.afterSkill(p, 26);
        Combat.invuln(p, 22);
        root(p, 1, 22);
        say(p, S_GRAB_HIT);
        Vec3d hold = front(p, 1.1, 0);
        for (int i = 0; i < 20; i++) Tasks.later(i, () -> {
            if (!t.isAlive()) return;
            t.requestTeleport(hold.x, hold.y, hold.z);
            t.setVelocity(0, 0, 0);
            t.velocityModified = true;
        });
        int[] cuts = aw ? new int[]{4, 7, 10, 13, 16} : new int[]{6, 12};
        for (int k = 0; k < cuts.length; k++) {
            final int kk = k;
            Tasks.later(cuts[k], () -> {
                if (!alive(p) || !t.isAlive()) return;
                Fx.slash(w, p, 1.8, kk % 2 == 0 ? 30 : -30, kk % 2 == 1, GLASS);
                if (Combat.hit(p, t, GRAB_CUT) != Combat.Result.MISS) cutHit(w, t.getBoundingBox().getCenter(), 1f, kk == 0 ? Fx.Sfx.ZUBA : null);
            });
        }
        Tasks.later(16, () -> { if (alive(p)) { w.spawnParticles(ParticleTypes.END_ROD, p.getX(), p.getY() + 2.4, p.getZ(), 10, 0.1, 0.5, 0.1, 0.05); se(w, p.getPos(), SE_SHING, 1.4f, 1.1f); glint(w, p.getPos().add(0, 2.5, 0), 1.2f); } });
        Tasks.later(20, () -> {
            if (!alive(p) || !t.isAlive()) return;
            say(p, S_GRAB_END);
            Fx.slash(w, p, 2.6, 90, false, GLASS);
            Fx.slash(w, p, 2.8, 90, false, GOLD);
            if (Combat.hit(p, t, GRAB_END) == Combat.Result.MISS) return;
            Vec3d c = t.getBoundingBox().getCenter();
            cutHit(w, c, 1.6f, Fx.Sfx.ZAN);
            Fx.shakeAround(w, c, 8, 0.6f);
        });
    }

    private static boolean grabAir(ServerPlayerEntity p, boolean aw) {
        Motion.play(p, "pl_grab_air");
        Combat.afterSkill(p, 12);
        long t0 = Combat.now();
        ServerWorld w = p.getServerWorld();
        say(p, S_GRAB);
        LivingEntity[] got = {null};
        for (int i = 2; i <= 12; i++) Tasks.later(i, () -> {
            if (!alive(p) || got[0] != null || !"pl_grab_air".equals(Motion.startedSince(p, t0))) return;
            Vec3d f = Combat.horizontal(p.getRotationVec(1f));
            Combat.move(p, new Vec3d(f.x * 0.9, -1.0, f.z * 0.9));
            w.spawnParticles(LIGHT, p.getX(), p.getY() + 0.8, p.getZ(), 3, 0.2, 0.3, 0.2, 0);
            LivingEntity t = Combat.targets(p, 2.4, 70, 1).stream().findFirst().orElse(null);
            if (t == null) return;
            got[0] = t;
            if (Combat.hit(p, t, GRAB) == Combat.Result.HIT) Tasks.later(2, () -> { if (alive(p) && t.isAlive()) seize(p, w, t, aw); });
        });
        return true;
    }

    // ⑤ 내리꽂기: 2 웅크림 → 5 높이 뜀 → 14 내리꽂아 땅을 가름 (반지름 2.5, 다운) · 각성: 빛 칼날이 원으로 솟음 (3.5)
    private static final Hit DROP = new Hit(1.5f, Hit.Kind.DOWN, 28, 0, -0.9, 14, true, true);
    private static final Hit DROP_RING = new Hit(0.8f, Hit.Kind.AIR, 20, 0.4, 0.9, 6, false, false);

    private static boolean drop(ServerPlayerEntity p, boolean aw) {
        Motion.play(p, "pl_drop");
        Combat.afterSkill(p, 22);
        Tasks.later(4, () -> { if (alive(p)) { Vec3d f = Combat.horizontal(p.getRotationVec(1f)); Combat.move(p, new Vec3d(f.x * 0.45, 0.9, f.z * 0.45)); } });
        Tasks.later(11, () -> { if (alive(p)) Combat.move(p, new Vec3d(0, -1.4, 0)); });
        slamAt(p, 14, aw);
        return true;
    }

    private static boolean dropAir(ServerPlayerEntity p, boolean aw) {
        Motion.play(p, "pl_drop_air");
        Combat.afterSkill(p, 12);
        Tasks.later(3, () -> { if (alive(p)) Combat.move(p, new Vec3d(0, -1.6, 0)); });
        slamAt(p, 6, aw);
        return true;
    }

    private static void slamAt(ServerPlayerEntity p, int at, boolean aw) {
        ServerWorld w = p.getServerWorld();
        Tasks.later(at, () -> {
            if (!alive(p)) return;
            Vec3d g = front(p, 1.0, 0);
            Fx.slash(w, p, 2.6, 90, false, GLASS);
            Fx.ring3d(w, g.add(0, 0.15, 0), new Vec3d(0, 1, 0), 0.4, 3.0, 0.3f, 8, GLASS);
            Fx.debris(w, g, 14, 1.2);
            Fx.word(w, g.add(0, 1.6, 0), Fx.Sfx.ZUDON, 1.4f);
            Fx.shakeAround(w, g, 8, 0.6f);
            w.playSound(null, BlockPos.ofFloored(g), SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 0.8f, 1.3f);
            for (LivingEntity t : around(p, g.add(0, 0.5, 0), 2.5)) if (Combat.hit(p, t, DROP) != Combat.Result.MISS) cutHit(w, t.getBoundingBox().getCenter(), 1.3f, Fx.Sfx.ZAN);
            if (!aw) return;
            Tasks.later(4, () -> {   // 각성: 둘레에 빛 칼날이 원으로 솟음
                if (!alive(p)) return;
                for (int i = 0; i < 12; i++) {
                    double a = Math.PI * 2 * i / 12;
                    Vec3d q = g.add(Math.cos(a) * 3.0, 0, Math.sin(a) * 3.0);
                    for (int h = 0; h < 6; h++) w.spawnParticles(h % 2 == 0 ? LIGHT_GOLD : LIGHT, q.x, q.y + h * 0.3, q.z, 1, 0, 0, 0, 0);
                    w.spawnParticles(ParticleTypes.END_ROD, q.x, q.y + 0.5, q.z, 2, 0.05, 0.4, 0.05, 0.05);
                }
                se(w, g, SE_FLASH, 1.6f, 1.2f);
                for (LivingEntity t : around(p, g.add(0, 0.5, 0), 3.5)) if (Combat.hit(p, t, DROP_RING) != Combat.Result.MISS) cutHit(w, t.getBoundingBox().getCenter(), 1f, null);
            });
        });
    }

    // ⑥ 빛의 유법: 10 팔을 펼치며 칼날이 빛남 — 200틱
    private static boolean lightMode(ServerPlayerEntity p) {
        Motion.play(p, "pl_mode");
        Combat.afterSkill(p, 18);
        ServerWorld w = p.getServerWorld();
        say(p, S_MODE);
        Tasks.later(10, () -> {
            if (!alive(p)) return;
            MODE_UNTIL.put(p.getUuid(), Combat.now() + 200);
            Motion.play(p, "#shine:on");
            se(w, p.getPos(), SE_SHINE, 1.4f, 1.0f);
            se(w, p.getPos(), SE_SHING, 1.2f, 1.0f);
            for (int s2 = -1; s2 <= 1; s2 += 2) glint(w, front(p, 0.1, 1.3).add(new Vec3d(-Combat.horizontal(p.getRotationVec(1f)).z, 0, Combat.horizontal(p.getRotationVec(1f)).x).multiply(0.9 * s2)), 1.0f);
            Fx.slash(w, p, 2.2, 0, false, GOLD);
            Fx.slash(w, p, 2.2, 0, true, GLASS);
            w.spawnParticles(ParticleTypes.END_ROD, p.getX(), p.getY() + 1.2, p.getZ(), 30, 0.6, 0.6, 0.6, 0.1);
            Fx.ring3d(w, p.getPos().add(0, 0.2, 0), new Vec3d(0, 1, 0), 0.4, 4.0, 0.3f, 8, GOLD);

            JobSkills.bar(p, "빛의 유법 — 10초 동안 피해 +20% · 평타에 빛의 호 · 칼날 베기가 길어짐", Formatting.YELLOW);
        });
        return true;
    }

    // 각성 ⑥ 빛의 반사: 4 두 칼날을 머리 위로 모음 → 8 번쩍, 8칸 안 모두 눈이 멀고 0.6초 굳음
    private static final Hit FLASH = new Hit(0.3f, Hit.Kind.HIT, 12, 0, 0, 0, false, false).noParry();

    private static boolean flash(ServerPlayerEntity p) {
        Motion.play(p, "pl_flash");
        Combat.afterSkill(p, 16);
        ServerWorld w = p.getServerWorld();
        say(p, S_FLASH);
        Tasks.later(4, () -> { if (alive(p)) w.spawnParticles(ParticleTypes.END_ROD, p.getX(), p.getY() + 2.6, p.getZ(), 16, 0.1, 0.1, 0.1, 0.02); });
        Tasks.later(8, () -> {
            if (!alive(p)) return;
            Vec3d top = p.getPos().add(0, 2.5, 0);
            w.spawnParticles(ParticleTypes.FLASH, top.x, top.y, top.z, 2, 0, 0, 0, 0);
            w.spawnParticles(ParticleTypes.END_ROD, top.x, top.y, top.z, 60, 0.2, 0.2, 0.2, 0.4);
            Fx.ring3d(w, p.getPos().add(0, 1.2, 0), new Vec3d(0, 1, 0), 0.5, 8.0, 0.25f, 10, GOLD);
            se(w, top, SE_FLASH, 2.0f, 1.0f);
            glint(w, top, 2.4f);
            for (int i = 0; i < 24; i++) { double a = Math.PI * 2 * i / 24; Vec3d d = new Vec3d(Math.cos(a), Math.sin(a) * 0.35, Math.sin(a));
                ray(w, top, top.add(d.multiply(4.5))); }
            for (LivingEntity t : around(p, p.getPos().add(0, 1, 0), 8.0)) {
                if (Combat.hit(p, t, FLASH) == Combat.Result.MISS) continue;
                blind(t, 50);
                Fx.flash(t, 1.0f);
            }
        });
        return true;
    }

    // ⑦ 빛 칼날 질주: 4 웅크려 칼날을 젖힘 → 6~12 땅을 박차 내달림 (지나치는 모두 벰) → 14 크게 가로 벰 (날림) · 각성: 12칸, 세 번 오감
    private static final Hit RUSH = new Hit(0.6f, Hit.Kind.HIT, 18, 0.1, 0.2, 6, false, false);
    private static final Hit RUSH_END = new Hit(1.4f, Hit.Kind.KNOCK, 28, 1.4, 0.5, 10, true, false);

    private static boolean rush(ServerPlayerEntity p, int len, boolean aw, String mo) {
        Motion.play(p, mo);
        boolean q = mo.equals("pl_rush_q");
        int go = q ? 2 : 6, dur = q ? 3 : 6;
        Combat.afterSkill(p, q ? 10 : aw ? 34 : 22);
        Combat.invuln(p, go + dur * (aw ? 3 : 1) + 2);
        ServerWorld w = p.getServerWorld();
        Set<Integer> got = new HashSet<>();
        double step = len / (double) dur;
        int passes = aw ? 3 : 1;
        for (int pass = 0; pass < passes; pass++) {
            final int pp = pass;
            for (int i = 0; i < dur; i++) {
                final int k = i;
                Tasks.later(go + pass * dur + i, () -> {
                    if (!alive(p)) return;
                    if (k == 0) got.clear();
                    Vec3d f = Combat.horizontal(p.getRotationVec(1f)).multiply(pp % 2 == 0 ? 1 : -1);
                    Vec3d from = p.getPos().add(0, 1, 0);
                    Combat.move(p, new Vec3d(f.x * step, Math.min(0, p.getVelocity().y), f.z * step));
                    ray(w, from, from.add(f.multiply(step)));
                    w.spawnParticles(ParticleTypes.END_ROD, from.x, from.y, from.z, 2, 0.2, 0.3, 0.2, 0.02);
                    for (LivingEntity t : around(p, from.add(f.multiply(step * 0.5)), 1.6))
                        if (got.add(t.getId()) && Combat.hit(p, t, RUSH) != Combat.Result.MISS) cutHit(w, t.getBoundingBox().getCenter(), 0.9f, null);
                });
            }
        }
        Tasks.later(go + dur * passes, () -> { if (alive(p)) Combat.move(p, new Vec3d(0, Math.min(0, p.getVelocity().y), 0)); });
        Tasks.later(go + dur * passes + 2, () -> {
            if (!alive(p)) return;
            blade(w, p, 3.6, 0, false);
            for (LivingEntity t : around(p, p.getPos().add(0, 1, 0), 3.2)) if (Combat.hit(p, t, RUSH_END) != Combat.Result.MISS) cutHit(w, t.getBoundingBox().getCenter(), 1.4f, Fx.Sfx.ZAN);
        });
        for (int pp2 = 0; pp2 < passes; pp2++) Tasks.later(go + pp2 * dur, () -> { if (alive(p)) { se(w, p.getPos(), SE_BIG, 1.3f, 1.2f); w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_BREEZE_JUMP, SoundCategory.PLAYERS, 1.0f, 1.2f); } });
        return true;
    }

    private static boolean rushAir(ServerPlayerEntity p) {
        Motion.play(p, "pl_rush_air");
        Combat.afterSkill(p, 12);
        ServerWorld w = p.getServerWorld();
        Set<Integer> got = new HashSet<>();
        for (int i = 2; i <= 6; i++) Tasks.later(i, () -> {
            if (!alive(p)) return;
            Vec3d f = Combat.horizontal(p.getRotationVec(1f));
            Vec3d from = p.getPos().add(0, 1, 0);
            Combat.move(p, new Vec3d(f.x * 1.2, -0.7, f.z * 1.2));
            ray(w, from, from.add(f.multiply(1.2)).add(0, -0.7, 0));
            for (LivingEntity t : around(p, from, 1.8)) if (got.add(t.getId()) && Combat.hit(p, t, CUT_DOWN) != Combat.Result.MISS) cutHit(w, t.getBoundingBox().getCenter(), 1f, Fx.Sfx.ZUBA);
        });
        return true;
    }

    /** to 자리로 옮기고 look 쪽을 봄 */
    private static void face(ServerPlayerEntity p, Vec3d to, Vec3d look) {
        Vec3d d = look.subtract(to);
        float yaw = (float) (MathHelper.atan2(d.z, d.x) * 180 / Math.PI) - 90f;
        p.networkHandler.requestTeleport(to.x, to.y, to.z, yaw, p.getPitch());
    }

    // ═════════ 각성 '빛 칼날' (64틱, 참고 2:10~2:12.5 등장 · 3:39 칼날 클로즈업) ═════════
    //   6~24 고개를 숙이고 오른손을 펴 얼굴 앞에 → 27 두 팔을 아래로 내뻗으며 아래팔에서 칼날이 번쩍 돋음 → 36~50 칼날을 세워 얼굴 앞에 비춰 봄 → 56 자세
    static final int AWAKEN_SCENE = 64;

    static void onAwaken(ServerPlayerEntity p, PlayerData.Entry e) {
        ServerWorld w = p.getServerWorld();
        Motion.play(p, "pl_awaken");
        if (ServerPlayNetworking.canSend(p, CutscenePayload.ID)) ServerPlayNetworking.send(p, new CutscenePayload("pl_awaken", p.getId(), -1));
        Combat.invuln(p, AWAKEN_SCENE);
        Combat.afterSkill(p, AWAKEN_SCENE - 6);
        Awaken.extend(p, AWAKEN_SCENE);
        VampireJob.addBlood(e, 20);
        Tasks.later(4, () -> { if (alive(p)) AlchemySounds.voice(p, S_AWAKEN, List.of(p)); });   // 나의 유법은 빛… (108틱, 컷신이 끝나도 이어짐)
        Tasks.later(27, () -> {
            if (!alive(p)) return;
            Vec3d fw = Combat.horizontal(p.getRotationVec(1f)), rt = new Vec3d(-fw.z, 0, fw.x);
            for (int s = -1; s <= 1; s += 2) {
                Vec3d a = p.getPos().add(0, 1.0, 0).add(rt.multiply(0.45 * s));
                ray(w, a, a.add(rt.multiply(0.6 * s)).add(0, -0.4, 0));
                w.spawnParticles(ParticleTypes.END_ROD, a.x, a.y, a.z, 12, 0.1, 0.2, 0.1, 0.12);
            }
            w.spawnParticles(ParticleTypes.FLASH, p.getX(), p.getY() + 1.2, p.getZ(), 1, 0, 0, 0, 0);
            Fx.ring3d(w, p.getPos().add(0, 0.2, 0), new Vec3d(0, 1, 0), 0.4, 5.0, 0.3f, 10, GOLD);
            Fx.word(w, p.getPos().add(0, 2.4, 0), Fx.Sfx.PIKIN, 1.6f);
            Fx.shakeAround(w, p.getPos(), 10, 0.5f);
            se(w, p.getPos(), SE_SHING, 2.0f, 0.9f);
            se(w, p.getPos(), SE_FLASH, 1.4f, 1.1f);
            for (int s2 = -1; s2 <= 1; s2 += 2) glint(w, p.getPos().add(0, 1.0, 0).add(rt.multiply(0.9 * s2)), 1.4f);
            Motion.play(p, "#shine:on");
        });
        for (int t = 36; t < 52; t += 3) Tasks.later(t, () -> { if (alive(p)) glow(w, p); });
        Tasks.later(AWAKEN_SCENE - 4, () -> { if (alive(p) && Awaken.active(p)) JobSkills.bar(p, "✦ 빛 칼날 — 피해 +10% · 평타에 빛의 호 · 기술이 바뀜  (G = 섬광 난도)", Formatting.YELLOW); });
    }

    // ═════════ 각성기 '섬광 난도' (100틱, 참고 ASBR 카즈 '모드「휘채활도」' 3:39.6~3:44.6 을 1틱 단위로 — 모션·카메라·판정이 같은 틱) ═════════
    //   상대를 정면 3칸에 세움 → 11~25 칼날을 핥음 → 28~31 2칸 앞으로 파고듦 → 32·36·40·44·48 거대한 빛의 호로 좌우 연속 →
    //   56 낮게 쓸어 벰 → 60~66 상대를 꿰뚫고 지나가 뒤 2칸에 섬 → 70 칼날을 하늘로 → 82·86·90 등 뒤에서 상대가 늦게 베임 → 94 날림
    static final int ULT_SCENE = 100;
    static final double ULT_DIST = 3.0;
    private static final Hit ULT_CUT = new Hit(0.3f, Hit.Kind.HIT, 80, 0, 0, 0, true, false).noParry();
    private static final Hit ULT_BIG = new Hit(0.8f, Hit.Kind.HIT, 80, 0, 0, 0, true, false).noParry();
    private static final Hit ULT_LATE = new Hit(0.6f, Hit.Kind.HIT, 60, 0, 0, 0, true, true).noParry();
    private static final Hit ULT_END = new Hit(3.6f, Hit.Kind.KNOCK, 40, 1.6, 0.8, 0, true, true).noParry();

    /** 시전자가 시작 자리에서 앞으로 나간 거리 (컷신 대본과 같은 값) */
    static double ultAdvance(int t) {
        if (t < 28) return 0;
        if (t < 31) return 2.0 * (t - 28) / 3.0;
        if (t < 60) return 2.0;
        if (t < 66) { double x = (t - 60) / 6.0; return 2.0 + 3.0 * (1 - (1 - x) * (1 - x)); }
        return 5.0;
    }

    static boolean ultimate(ServerPlayerEntity p, PlayerData.Entry e) {
        ServerWorld w = p.getServerWorld();
        LivingEntity t = PillarHeat.near(p, 9);
        if (t == null) { JobSkills.bar(p, "앞에 대상이 없습니다 (9칸)", Formatting.GRAY); return false; }
        Motion.play(p, "pl_ult");
        Combat.invuln(p, ULT_SCENE);
        Combat.afterSkill(p, ULT_SCENE - 6);
        Awaken.extend(p, ULT_SCENE);
        List<ServerPlayerEntity> viewers = new ArrayList<>(List.of(p));
        if (t instanceof ServerPlayerEntity sp) viewers.add(sp);
        CutscenePayload pl = new CutscenePayload("pl_ult", p.getId(), t.getId());
        for (ServerPlayerEntity v : viewers) if (ServerPlayNetworking.canSend(v, CutscenePayload.ID)) ServerPlayNetworking.send(v, pl);
        // 대사는 겹치지 않게: 모드 「빛 칼날」 (11~46) → 하찮군 (74~91) → 이기면 그만이다 (100~150, 컷신 뒤)
        Tasks.later(11, () -> { if (alive(p)) AlchemySounds.voice(p, S_ULT1, viewers); });
        Tasks.later(74, () -> { if (alive(p)) AlchemySounds.voice(p, S_ULT2, viewers); });
        Tasks.later(100, () -> { if (alive(p)) AlchemySounds.voice(p, S_ULT3, viewers); });
        // 상대를 정면 3칸에 세우고, 시전자는 대본대로 앞으로 나감 (막히면 그 자리)
        Vec3d fw = Combat.horizontal(p.getRotationVec(1f)).normalize();
        Vec3d start = p.getPos();
        float yaw = p.getYaw(), pitch = p.getPitch();
        Vec3d want = start.add(fw.multiply(ULT_DIST));
        Vec3d hold = w.isSpaceEmpty(t, t.getBoundingBox().offset(want.subtract(t.getPos()))) ? want : t.getPos();
        double[] reached = {0};
        for (int i = 0; i < 96; i++) {
            final int k = i;
            Tasks.later(i, () -> {
                if (t.isAlive()) {
                    t.requestTeleport(hold.x, hold.y, hold.z);
                    t.setVelocity(0, 0, 0);
                    t.velocityModified = true;
                }
                if (!alive(p)) return;
                double a = ultAdvance(k);
                if (a > reached[0]) {
                    Vec3d q = start.add(fw.multiply(a));
                    if (!w.isSpaceEmpty(p, p.getBoundingBox().offset(q.subtract(p.getPos())))) return;   // 벽이면 멈춤
                    reached[0] = a;
                }
                Vec3d q = start.add(fw.multiply(reached[0]));
                p.networkHandler.requestTeleport(q.x, q.y, q.z, yaw, pitch);
                p.setVelocity(0, 0, 0);
            });
        }
        // 6~25 칼날이 빛남 (핥을 때 반짝)
        for (int i = 6; i < 26; i += 3) Tasks.later(i, () -> { if (alive(p)) glow(w, p); });
        Tasks.later(14, () -> { if (alive(p)) { Vec3d m = p.getEyePos().add(fw.multiply(0.4)); w.spawnParticles(ParticleTypes.END_ROD, m.x, m.y - 0.15, m.z, 8, 0.25, 0.05, 0.25, 0.01);
            se(w, m, SE_SHING, 1.6f, 1.15f); glint(w, m.add(fw.multiply(0.2)), 1.0f); } });
        Tasks.later(28, () -> { if (alive(p)) { se(w, p.getPos(), SE_SWISH, 1.6f, 0.8f); w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_BREEZE_JUMP, SoundCategory.PLAYERS, 1.2f, 1.2f); } });
        // 32~48 거대한 빛의 호로 좌우 번갈아
        for (int k = 0; k < 5; k++) {
            final int kk = k;
            Tasks.later(32 + k * 4, () -> {
                if (!alive(p) || !t.isAlive()) return;
                bigArc(w, p, t, kk % 2 == 0);
                if (Combat.hit(p, t, ULT_CUT) != Combat.Result.MISS) cutHit(w, t.getBoundingBox().getCenter(), 1.2f, kk == 0 ? Fx.Sfx.ZAN : null);
            });
        }
        // 56 낮게 쓸어 벰
        Tasks.later(56, () -> {
            if (!alive(p) || !t.isAlive()) return;
            Fx.slash(w, p, 3.4, -5, false, GLASS);
            Fx.slash(w, p, 3.6, -5, false, GOLD);
            arcSparkle(w, p, 3.8, -5, false);
            se(w, p.getPos(), SE_BIG, 1.8f, 0.9f);
            if (Combat.hit(p, t, ULT_BIG) != Combat.Result.MISS) { cutHit(w, t.getBoundingBox().getCenter(), 1.5f, Fx.Sfx.ZUBA); Fx.shakeAround(w, t.getPos(), 8, 0.5f); }
        });
        // 60~66 꿰뚫고 지나감: 빛의 줄
        Tasks.later(60, () -> { if (alive(p)) ray(w, start.add(fw.multiply(2.0)).add(0, 0.8, 0), start.add(fw.multiply(5.0)).add(0, 0.8, 0)); });
        // 70 칼날을 하늘로 — 번쩍
        Tasks.later(72, () -> {
            if (!alive(p)) return;
            Vec3d top = p.getPos().add(0, 2.6, 0);
            w.spawnParticles(ParticleTypes.END_ROD, top.x, top.y, top.z, 20, 0.05, 0.3, 0.05, 0.08);
            se(w, top, SE_SHING, 2.0f, 1.0f);
            glint(w, top.add(0, 0.6, 0), 2.0f);
        });
        // 82·86·90 등 뒤에서 상대가 늦게 베임 → 94 날림
        for (int k = 0; k < 3; k++) {
            final int kk = k;
            Tasks.later(82 + k * 4, () -> {
                if (!alive(p) || !t.isAlive()) return;
                Vec3d c = t.getBoundingBox().getCenter();
                Fx.inkSlashes(w, c, 3, 1.4f);
                Fx.impactFlash(w, c, 1.2f);
                w.spawnParticles(LIGHT_GOLD, c.x, c.y, c.z, 20, 0.4, 0.6, 0.4, 0);
                Fx.word(w, c.add(0, 1.4, 0), kk == 0 ? Fx.Sfx.ZAN : Fx.Sfx.ZUBA, 1.5f);
                se(w, c, SE_CUT, 1.8f, 0.85f + kk * 0.1f);
                glint(w, c.add(0.2, 0.3, 0), 1.4f);
                Combat.hit(p, t, ULT_LATE);
            });
        }
        Tasks.later(94, () -> {
            if (!alive(p)) return;
            Vec3d c = t.isAlive() ? t.getBoundingBox().getCenter() : hold.add(0, 1, 0);
            for (LivingEntity x : around(p, c, 5)) Combat.hit(p, x, ULT_END);
            for (int i = 0; i < 6; i++) { double a = Math.PI * i / 6; Vec3d d = new Vec3d(Math.cos(a), Math.sin(a) * 0.6, Math.sin(a) * 0.3); ray(w, c.subtract(d.multiply(2.5)), c.add(d.multiply(2.5))); }
            w.spawnParticles(ParticleTypes.FLASH, c.x, c.y, c.z, 1, 0, 0, 0, 0);
            w.spawnParticles(ParticleTypes.END_ROD, c.x, c.y, c.z, 60, 0.6, 0.8, 0.6, 0.25);
            Fx.word(w, c.add(0, 2.0, 0), Fx.Sfx.DOGOOON, 2.0f);
            Fx.shakeAround(w, c, 16, 1.0f);
            se(w, c, SE_FLASH, 2.4f, 0.9f);
            glint(w, c, 3.0f);
            w.playSound(null, BlockPos.ofFloored(c), SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 1.0f, 1.4f);
            if (t.isAlive()) VampireJob.drink(p, bbc.job.Jobs.entry(p), t);
        });
        return true;
    }

    /** 상대를 가로지르는 거대한 빛의 호 (지름 6칸) */
    private static void bigArc(ServerWorld w, ServerPlayerEntity p, LivingEntity t, boolean right) {
        Vec3d c = t.getBoundingBox().getCenter(), to = c.subtract(p.getPos()).multiply(1, 0, 1).normalize(), rt = new Vec3d(-to.z, 0, to.x);
        double tilt = right ? 0.35 : -0.35;
        // 거대한 빛의 호: 흰 판 + 푸른 판 (칼자국) → 그 둘레로 흰·푸른·무지개 세 겹 빛줄기 → 양끝 반짝
        Fx.slash(w, p, 3.3, right ? 35 : -35, !right, GLASS);
        Fx.slash(w, p, 3.55, right ? 35 : -35, !right, GOLD);
        double[] radii = {2.8, 3.05, 3.3};
        for (int i = 0; i <= 36; i++) {
            double a = Math.PI * (i / 36.0 - 0.5) * 1.3;
            for (int b = 0; b < 3; b++) {
                double rr = radii[b];
                Vec3d q = c.subtract(to.multiply(1.2)).add(to.multiply(Math.cos(a) * rr)).add(rt.multiply(Math.sin(a) * rr * (right ? 1 : -1))).add(0, Math.sin(a) * rr * tilt, 0);
                w.spawnParticles(b == 0 ? LIGHT : b == 1 ? LIGHT_GOLD : PRISM[i % PRISM.length], q.x, q.y, q.z, b == 2 ? 1 : 2, 0.03, 0.03, 0.03, 0);
                if (b == 0 && i % 4 == 0) w.spawnParticles(ParticleTypes.END_ROD, q.x, q.y, q.z, 1, 0, 0, 0, 0.03);
            }
        }
        for (int e = -1; e <= 1; e += 2) {
            double a = Math.PI * 0.5 * 1.3 * e;
            glint(w, c.subtract(to.multiply(1.2)).add(to.multiply(Math.cos(a) * 3.1)).add(rt.multiply(Math.sin(a) * 3.1 * (right ? 1 : -1))).add(0, Math.sin(a) * 3.1 * tilt, 0), 1.1f);
        }
        se(w, c, SE_BIG, 1.6f, right ? 1.0f : 1.1f);
    }

    // ═════════ 도움 ═════════
    static boolean alive(ServerPlayerEntity p) { return PillarHeat.alive(p); }
    static Vec3d front(ServerPlayerEntity p, double d, double up) { return PillarHeat.front(p, d, up); }
    static void root(ServerPlayerEntity p, int from, int ticks) { PillarHeat.root(p, from, ticks); }
}
