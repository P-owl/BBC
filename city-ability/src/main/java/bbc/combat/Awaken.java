package bbc.combat;

import bbc.core.Tasks;
import bbc.job.JobSkills;
import bbc.job.JobType;
import bbc.job.Jobs;
import bbc.net.AwakenPayload;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

/**
 * 각성 (기획안 1부 ‘각성 시스템’ 공통 규칙). 플레이어·몬스터 공통.
 *  - 게이지 0~100: 때릴 때 피해 × DEAL, 맞을 때 피해 × TAKE 만큼 참 (맞는 쪽이 더 많이 → 역전 수단)
 *  - 100에서 G → 45초 각성. 순간 주위 4칸 밀어내기 + 1초 무적. 콤보를 맞는 중엔 못 함 (회피기가 그 역할)
 *  - 각성 중 G → 각성기 (각성 한 번에 1번, 쓰면 각성 10초 짧아짐). 직업마다 다르고, 없으면 공통 각성기 ‘난무’
 *  - 각성 중 공통 강화: 평타 피해 ×1.25. 직업별 강화·교체는 JobType.onAwaken 에서
 */
public final class Awaken {
    private Awaken() {}

    public static final double MAX = 100;
    public static final double DEAL = 2.0, TAKE = 3.2;          // 피해 1당 게이지 (평타 1대 ≈ 때리면 2, 맞으면 3.2)
    public static final int DURATION = 900, ULT_COST = 200, PUSH_RADIUS = 4, INVULN = 20;
    public static final float M1_BONUS = 1.25f;

    public static boolean active(LivingEntity e) {
        Fighter f = Combat.peek(e);
        return f != null && Combat.now < f.awakenUntil;
    }

    public static double gauge(LivingEntity e) {
        Fighter f = Combat.peek(e);
        return f == null ? 0 : f.awakenGauge;
    }

    /** Combat.hit 이 피해를 넣은 뒤 부름 */
    static void onHit(LivingEntity atk, LivingEntity victim, float dmg) {
        add(atk, dmg * DEAL);
        add(victim, dmg * TAKE);
    }

    public static void add(LivingEntity e, double amt) {
        Fighter f = Combat.of(e);
        if (Combat.now < f.awakenUntil) return;
        f.awakenGauge = Math.min(MAX, f.awakenGauge + amt);
    }

    /** G 키 (몬스터 AI 도 같은 걸 부름) */
    public static void press(LivingEntity e) {
        Fighter f = Combat.of(e);
        if (Combat.now < f.awakenUntil) { ultimate(e, f); return; }
        if (f.awakenGauge < MAX) {
            if (e instanceof ServerPlayerEntity p) JobSkills.bar(p, "각성 게이지 " + (int) f.awakenGauge + "%", Formatting.GRAY);
            return;
        }
        if (f.stunned()) return; // 콤보 맞는 중엔 못 함
        f.awakenUntil = Combat.now + DURATION;
        f.ultUsed = false;
        f.invulnUntil = Math.max(f.invulnUntil, Combat.now + INVULN);
        ServerWorld w = (ServerWorld) e.getWorld();
        for (Entity en : w.getOtherEntities(e, e.getBoundingBox().expand(PUSH_RADIUS), x -> x instanceof LivingEntity && x.isAlive() && !(x instanceof ArmorStandEntity))) {
            Vec3d d = Combat.horizontal(en.getPos().subtract(e.getPos()));
            Combat.move((LivingEntity) en, new Vec3d(d.x * 1.1, 0.45, d.z * 1.1));
        }
        Fx.wave(w, e.getPos().add(0, 0.1, 0), 0.5, 5.0, Fx.GOLD, 9);
        Fx.wave(w, e.getPos().add(0, 1.0, 0), 0.5, 3.5, Fx.GOLD, 7);
        Fx.flash(e, 0.55f);
        Fx.shakeAround(w, e.getPos(), 10, 0.7f);
        Fx.word(w, e.getPos().add(0, 2.6, 0), Fx.Sfx.GOGO, 1.5f);   // ゴゴゴゴ
        Fx.rings(w, e.getPos().add(0, 0.2, 0), new Vec3d(0, 1, 0), 5f, 3);
        Fx.lines(e, 1.0f);
        w.spawnParticles(ParticleTypes.FLASH, e.getX(), e.getY() + 1, e.getZ(), 1, 0, 0, 0, 0);
        w.spawnParticles(ParticleTypes.END_ROD, e.getX(), e.getY() + 1, e.getZ(), 40, 0.4, 0.8, 0.4, 0.25);
        w.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.ITEM_TOTEM_USE, SoundCategory.PLAYERS, 0.8f, 1.2f);
        Motion.play(e, "awaken");
        if (e instanceof ServerPlayerEntity p) {
            JobType job = Jobs.type(Jobs.entry(p));
            if (job != null) job.onAwaken(p, Jobs.entry(p));
            JobSkills.bar(p, "각성! (45초)  G = 각성기", Formatting.GOLD);
        }
    }

    private static void ultimate(LivingEntity e, Fighter f) {
        if (f.ultUsed || f.stunned()) return;
        boolean done = false;
        if (e instanceof ServerPlayerEntity p) {
            JobType job = Jobs.type(Jobs.entry(p));
            if (job != null) done = job.ultimate(p, Jobs.entry(p));
        }
        if (!done) done = flurry(e);
        if (!done) return;
        f.ultUsed = true;
        f.awakenUntil = Math.max(Combat.now + 20, f.awakenUntil - ULT_COST);
    }

    // ═════════ 공통 각성기: 난무 ═════════
    /** 앞의 상대를 0.1초마다 8번 연타하고, 마지막에 패링 불가 일격으로 날려 보냄 */
    static final Hit FLURRY = new Hit(0.8f, Hit.Kind.HIT, 12, 0.05, 0, 8, false, false);
    static final Hit FLURRY_END = new Hit(4f, Hit.Kind.KNOCK, 20, 1.6, 0.5, 40, true, false).noParry();

    public static boolean flurry(LivingEntity e) {
        if (Combat.targets(e, 3.4, 60, 1).isEmpty()) {
            if (e instanceof ServerPlayerEntity p) JobSkills.bar(p, "앞에 대상이 없습니다", Formatting.GRAY);
            return false;
        }
        Fighter f = Combat.of(e);
        int tok = ++f.token;
        f.m1ReadyAt = Combat.now + 30;
        f.recoveryUntil = f.m1ReadyAt;
        ServerWorld w = (ServerWorld) e.getWorld();
        w.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.ENTITY_WARDEN_SONIC_CHARGE, SoundCategory.PLAYERS, 0.7f, 1.8f);
        Motion.play(e, "barrage");
        Tasks.later(3, () -> { if (f.token == tok && e.isAlive()) Fx.word(w, e.getPos().add(Combat.horizontal(e.getRotationVec(1f)).multiply(1.5)).add(0, 2.4, 0), Fx.Sfx.DODODO, 1.3f); });   // 2틱마다 좌우 번갈아 지르는 전용 모션 (평타 모션을 2틱마다 다시 틀면 예비 동작만 보여서 소극적으로 보였음)
        DustParticleEffect fist = new DustParticleEffect(new Vector3f(1f, 0.95f, 0.85f), 1.3f);
        for (int i = 0; i < 8; i++) {
            int n = i;
            Tasks.later(2 + i * 2, () -> {
                if (f.token != tok || f.stunned() || !e.isAlive()) return;
                // 이 사이 앞으로 조금씩 밀고 들어감
                Vec3d fw = Combat.horizontal(e.getRotationVec(1f));
                Combat.move(e, new Vec3d(fw.x * 0.12, e.getVelocity().y, fw.z * 0.12));
                for (LivingEntity t : Combat.targets(e, 3.2, 60, 2)) {
                    if (Combat.hit(e, t, FLURRY) != Combat.Result.HIT) continue;
                    // 주먹 잔상: 내 어깨 → 상대 몸통으로 하얀 줄기 여러 갈래 + 맞은 자리 작은 충격파
                    Vec3d from = e.getPos().add(0, 1.35, 0), to = t.getBoundingBox().getCenter();
                    for (int k = 0; k < 3; k++) {
                        Vec3d jit = new Vec3d((e.getRandom().nextDouble() - 0.5) * 0.7, (e.getRandom().nextDouble() - 0.5) * 0.6, (e.getRandom().nextDouble() - 0.5) * 0.7);
                        for (double d = 0.3; d <= 1.0; d += 0.14) {
                            Vec3d pt = from.lerp(to.add(jit), d);
                            w.spawnParticles(fist, pt.x, pt.y, pt.z, 1, 0, 0, 0, 0);
                        }
                    }
                    w.spawnParticles(ParticleTypes.CRIT, to.x, to.y, to.z, 10, 0.3, 0.3, 0.3, 0.4);
                    w.spawnParticles(ParticleTypes.SWEEP_ATTACK, to.x, to.y, to.z, 1, 0.2, 0.2, 0.2, 0);
                    if (n % 2 == 1) Fx.wave(w, to, 0.2, 1.1, Fx.WHITE, 3);
                    Fx.burst(w, to, to.subtract(from).normalize(), 0.8f);
                    Fx.shake(e, 0.15f);
                }
                w.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.PLAYERS, 0.9f, 0.8f + e.getRandom().nextFloat() * 0.6f);
            });
        }
        Tasks.later(18, () -> { if (f.token == tok && !f.stunned() && e.isAlive()) Motion.play(e, "barrage_end"); });
        Tasks.later(22, () -> {
            if (f.token != tok || f.stunned() || !e.isAlive()) return;
            for (LivingEntity t : Combat.targets(e, 3.6, 60, 2)) {
                if (Combat.hit(e, t, FLURRY_END) != Combat.Result.HIT) continue;
                Vec3d c = t.getBoundingBox().getCenter();
                w.spawnParticles(ParticleTypes.SONIC_BOOM, c.x, c.y, c.z, 1, 0, 0, 0, 0);
                Combat.hitStop(f, Combat.of(t), 4);
                Fx.wave(w, c, 0.4, 3.0, Fx.WHITE, 7);
                Fx.word(w, c.add(0, 1.4, 0), Fx.Sfx.DOGOOON, 1.6f);
                Fx.burst(w, c, c.subtract(e.getBoundingBox().getCenter()).normalize(), 2.6f);
                Fx.impactFlash(w, c, 1.6f);
                Fx.rings(w, c, c.subtract(e.getBoundingBox().getCenter()), 5f, 3);
                Fx.smoke(w, t.getPos(), 26, 1.8);
                Fx.lines(t, 1.2f); Fx.lines(e, 1.0f);
                Fx.streak(t, 10);
                if (t.isOnGround()) Fx.debris(w, t.getPos(), 10, 1.8);
                Fx.shake(t, 1.1f);
                w.spawnParticles(ParticleTypes.EXPLOSION, c.x, c.y, c.z, 3, 0.4, 0.4, 0.4, 0);
                w.spawnParticles(ParticleTypes.CLOUD, c.x, c.y, c.z, 30, 0.2, 0.2, 0.2, 0.35);
            }
            w.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 0.8f, 1.2f);
            Fx.shake(e, 0.9f); Fx.flash(e, 0.35f);
            w.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.PLAYERS, 0.5f, 1.6f);
        });
        return true;
    }

    // ═════════ 틱: 오라, 끝, HUD ═════════

    public static void register() { ServerTickEvents.END_SERVER_TICK.register(Awaken::tick); }

    private static void tick(MinecraftServer server) {
        long now = Combat.now;
        for (ServerWorld w : server.getWorlds()) {
            for (ServerPlayerEntity p : w.getPlayers()) sendHud(p, now);
        }
        Combat.forEachFighter(f -> {
            if (f.awakenUntil == 0) return;
            LivingEntity e = f.e;
            if (now < f.awakenUntil) {
                if (now % 3 == 0) {
                    ServerWorld w = (ServerWorld) e.getWorld();
                    w.spawnParticles(new DustParticleEffect(new Vector3f(1f, 0.82f, 0.25f), 1.1f), e.getX(), e.getY() + 0.9, e.getZ(), 4, 0.35, 0.6, 0.35, 0);
                    if (now % 9 == 0) w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, e.getX(), e.getY() + 1.2, e.getZ(), 2, 0.3, 0.5, 0.3, 0.1);
                }
            } else { // 끝
                f.awakenUntil = 0;
                f.awakenGauge = 0;
                if (e instanceof ServerPlayerEntity p) {
                    JobType job = Jobs.type(Jobs.entry(p));
                    if (job != null) job.onAwakenEnd(p, Jobs.entry(p));
                    JobSkills.bar(p, "각성이 끝났다", Formatting.GRAY);
                }
            }
        });
    }

    private static void sendHud(ServerPlayerEntity p, long now) {
        if (!CombatMode.active(p) || now % 2 != 0) return;
        if (!ServerPlayNetworking.canSend(p, AwakenPayload.ID)) return;
        Fighter f = Combat.peek(p);
        int g = f == null ? 0 : (int) f.awakenGauge;
        int left = f == null || now >= f.awakenUntil ? 0 : (int) (f.awakenUntil - now);
        ServerPlayNetworking.send(p, new AwakenPayload(g, left, f != null && left > 0 && !f.ultUsed,
                (int) bbc.job.Stamina.get(p), (int) bbc.job.Stamina.max(p)));
    }
}
