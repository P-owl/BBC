package bbc.combat;

import bbc.core.Tasks;
import bbc.job.JobSkills;
import bbc.job.Stamina;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;

/**
 * 꾹 누르는 기술 (채널링). 누르고 있는 동안 스태미나를 쓰며 계속 공격하고, 떼거나 아래 제한에 걸리면 끝난다.
 *
 *  밸런스 제한
 *   - 최대 시간 (휠윈드 3초, 연속 찌르기 2.5초)
 *   - 틱마다 스태미나 소모, 바닥나면 끝
 *   - 같은 상대는 한 번 돌릴 때 최대 N번만 맞음 (무한 경직 방지) → 넘으면 밀려남
 *   - 쓰는 동안 느리게 움직임, 평타·다른 기술 불가, 공중에선 시작 못 함
 *   - 맞아서 경직되면 바로 끊김 (패링도 됨)
 *   - 재사용 대기는 끝난 뒤부터, 오래 쓸수록 길어짐
 */
public final class Channel {
    private Channel() {}

    public enum Kind {
        /** 대검(도끼) 휠윈드: 제자리에서 돌며 주위 전부를 4틱마다 벰 */
        WHIRL("휠윈드", 4, 60, 3.0, 6, 6, -0.55),
        /** 창 연속 찌르기: 앞으로 4틱마다 찌름 */
        RAPID("연속 찌르기", 4, 50, 2.5, 7, 5, -0.75);

        final String name;
        final int interval, maxTicks;
        final double drain;      // interval 마다 스태미나
        final int perTargetCap;  // 한 상대가 맞는 최대 횟수
        final int baseCooldown;  // 초
        public final double speed;
        Kind(String name, int interval, int maxTicks, double drain, int cap, int cd, double speed) {
            this.name = name; this.interval = interval; this.maxTicks = maxTicks; this.drain = drain;
            this.perTargetCap = cap; this.baseCooldown = cd; this.speed = speed;
        }
    }

    static final Hit WHIRL_HIT = new Hit(1.0f, Hit.Kind.HIT, 9, 0.18, 0.05, 10, false, false);
    static final Hit WHIRL_END = new Hit(1.6f, Hit.Kind.KNOCK, 12, 0.9, 0.3, 20, false, false);
    static final Hit RAPID_HIT = new Hit(0.9f, Hit.Kind.HIT, 10, 0.12, 0, 10, false, false);
    static final Hit PUSH_OUT = new Hit(0.5f, Hit.Kind.KNOCK, 10, 0.8, 0.25, 5, false, false);

    /** 시작. key = 누른 숫자 칸(떼기를 알아보려고), cdKey = 무기 재사용 표 열쇠 */
    public static boolean start(LivingEntity e, Kind k, int key, String cdKey) {
        Fighter f = Combat.of(e);
        if (f.channel != null || f.stunned()) return false;
        if (!e.isOnGround()) { if (e instanceof ServerPlayerEntity p) JobSkills.bar(p, k.name + "은 땅에서만", Formatting.GRAY); return false; }
        f.channel = k;
        f.channelKey = key;
        f.channelCdKey = cdKey;
        f.channelStart = Combat.now;
        f.channelNext = Combat.now + 2;
        f.channelHits.clear();
        f.m1Index = 0;
        Motion.play(e, k == Kind.WHIRL ? "gs_whirl" : "sp_rapid");
        ((ServerWorld) e.getWorld()).playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.ITEM_TRIDENT_RIPTIDE_1.value(), SoundCategory.PLAYERS, 0.7f, 1.4f);
        return true;
    }

    /** 숫자키를 뗌 */
    public static void release(LivingEntity e, int key) {
        Fighter f = Combat.peek(e);
        if (f != null && f.channel != null && f.channelKey == key) end(e, f, true);
    }

    public static void register() { ServerTickEvents.END_SERVER_TICK.register(Channel::tick); }

    private static void tick(MinecraftServer server) {
        Combat.forEachFighter(f -> {
            if (f.channel == null) return;
            LivingEntity e = f.e;
            long now = Combat.now;
            if (!e.isAlive() || f.stunned()) { end(e, f, false); return; }
            f.m1ReadyAt = Math.max(f.m1ReadyAt, now + 2);   // 쓰는 동안 평타 막음
            f.recoveryUntil = f.m1ReadyAt;
            if (now - f.channelStart >= f.channel.maxTicks) { end(e, f, true); return; }
            if (now < f.channelNext) return;
            f.channelNext = now + f.channel.interval;
            if (e instanceof ServerPlayerEntity p && !Stamina.spend(p, f.channel.drain)) {
                JobSkills.bar(p, "스태미나가 바닥났다", Formatting.RED);
                end(e, f, true);
                return;
            }
            switch (f.channel) {
                case WHIRL -> whirl(e, f);
                case RAPID -> rapid(e, f);
            }
        });
    }

    private static void whirl(LivingEntity e, Fighter f) {
        ServerWorld w = (ServerWorld) e.getWorld();
        for (Entity en : w.getOtherEntities(e, e.getBoundingBox().expand(3.0, 1.0, 3.0), x -> x instanceof LivingEntity && x.isAlive() && !(x instanceof ArmorStandEntity))) {
            LivingEntity t = (LivingEntity) en;
            if (t.squaredDistanceTo(e) > 3.2 * 3.2) continue;
            hitCapped(e, f, t, WHIRL_HIT);
        }
        Fx.wave(w, e.getPos().add(0, 1.0, 0), 1.8, 2.8, Fx.WHITE, 4);
        w.spawnParticles(ParticleTypes.SWEEP_ATTACK, e.getX(), e.getY() + 1, e.getZ(), 4, 1.4, 0.2, 1.4, 0);
        w.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 0.7f, 0.7f + e.getRandom().nextFloat() * 0.3f);
    }

    private static void rapid(LivingEntity e, Fighter f) {
        ServerWorld w = (ServerWorld) e.getWorld();
        Motion.play(e, "sp_rapid");
        for (LivingEntity t : Combat.targets(e, 4.4, 22, 2)) hitCapped(e, f, t, RAPID_HIT);
        Vec3d fw = Combat.horizontal(e.getRotationVec(1f));
        Vec3d side = new Vec3d(-fw.z, 0, fw.x);
        double j = (e.getRandom().nextDouble() - 0.5) * 0.5;
        Vec3d at = e.getPos().add(0, 1.1 + (e.getRandom().nextDouble() - 0.5) * 0.4, 0).add(fw.multiply(2.4)).add(side.multiply(j));
        Fx.plate(w, at, fw, new Vec3d(0, 1, 0), side, 3.2f, 0.05f, 0.14f, Fx.WHITE, 3);   // 찌르기 줄기
        w.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.ITEM_TRIDENT_THROW.value(), SoundCategory.PLAYERS, 0.5f, 1.3f + e.getRandom().nextFloat() * 0.3f);
    }

    private static void hitCapped(LivingEntity e, Fighter f, LivingEntity t, Hit h) {
        int n = f.channelHits.getOrDefault(t.getUuid(), 0);
        if (n >= f.channel.perTargetCap) return;
        f.channelHits.put(t.getUuid(), n + 1);
        Combat.hit(e, t, n + 1 >= f.channel.perTargetCap ? PUSH_OUT : h); // 마지막 한 대는 밀어냄 → 계속 가둬 두지 못함
    }

    private static void end(LivingEntity e, Fighter f, boolean finisher) {
        Kind k = f.channel;
        f.channel = null;
        long used = Combat.now - f.channelStart;
        Motion.play(e, "stop");
        if (finisher && k == Kind.WHIRL && e.isAlive()) { // 마지막 한 바퀴로 밀어냄
            ServerWorld w = (ServerWorld) e.getWorld();
            for (Entity en : w.getOtherEntities(e, e.getBoundingBox().expand(3.0, 1.0, 3.0), x -> x instanceof LivingEntity && x.isAlive() && !(x instanceof ArmorStandEntity)))
                if (en.squaredDistanceTo(e) < 3.2 * 3.2) Combat.hit(e, (LivingEntity) en, WHIRL_END);
            Fx.wave(w, e.getPos().add(0, 0.3, 0), 1.0, 4.0, Fx.WHITE, 7);
            Fx.shake(e, 0.4f);
            Fx.word(w, e.getPos().add(0, 2.4, 0), Fx.Sfx.BUON, 1.5f);
        }
        // 재사용: 기본 + 쓴 시간 1초당 1초
        long cd = Combat.now + (k.baseCooldown + used / 20) * 20L;
        if (f.channelCdKey != null) f.weaponCd.put(f.channelCdKey, cd);
        f.m1ReadyAt = Combat.now + 6;
        f.recoveryUntil = f.m1ReadyAt;
    }
}
