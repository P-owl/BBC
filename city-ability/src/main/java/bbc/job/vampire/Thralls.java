package bbc.job.vampire;

import bbc.combat.Combat;
import bbc.combat.Hit;
import bbc.job.alchemy.Targets;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * 하인: 뱀파이어가 흡혈·육아로 부리는 몬스터·주민 (무현버스의 야생 포켓몬 대신).
 * 주인을 따라다니고, 주인이 최근(8초 안)에 때린 상대에게 달려가 공격한다 (주인의 공격으로 들어감). 시간이 다 되면 풀려남.
 * 하인인 동안엔 태그 bbc_thrall 이 붙어 몬스터 전투 AI 가 쉬고, 주인의 기술에 맞지 않음.
 */
public final class Thralls {
    private Thralls() {}

    public static final String TAG = "bbc_thrall";
    record Thrall(ServerWorld world, UUID entity, UUID owner, long untilTick, Text oldName) {}

    private static final List<Thrall> LIST = new ArrayList<>();
    private static final java.util.Map<UUID, Long> NEXT_HIT = new java.util.HashMap<>();
    private static final DustParticleEffect RED = new DustParticleEffect(new Vector3f(0.7f, 0.0f, 0.05f), 1.0f);
    private static final Hit STRIKE = new Hit(0.6f, Hit.Kind.HIT, 10, 0.3, 0, 8, false, false);
    private static long clock = 0;

    public static int count(UUID owner) {
        int n = 0;
        for (Thrall t : LIST) if (t.owner.equals(owner)) n++;
        return n;
    }

    public static boolean isThrall(Entity e) { return e != null && e.getCommandTags().contains(TAG); }

    /** caster 의 하인인가 (자기 하인은 자기 기술에 안 맞음) */
    public static boolean ownedBy(Entity e, ServerPlayerEntity caster) {
        if (!isThrall(e)) return false;
        for (Thrall t : LIST) if (t.entity.equals(e.getUuid())) return t.owner.equals(caster.getUuid());
        return false;
    }

    /** 하인으로 만들기. max = 동시에 부릴 수 있는 수 (넘치면 가장 오래된 하인을 풀어 줌) */
    public static boolean bind(ServerPlayerEntity owner, LivingEntity target, int seconds, int max, String title) {
        if (isThrall(target) || target instanceof ServerPlayerEntity) return false;
        while (count(owner.getUuid()) >= max) {
            for (Thrall t : LIST) if (t.owner.equals(owner.getUuid())) { release(t); LIST.remove(t); break; }
        }
        LIST.add(new Thrall(owner.getServerWorld(), target.getUuid(), owner.getUuid(), clock + seconds * 20L, target.getCustomName()));
        target.addCommandTag(TAG);
        target.setCustomName(Text.literal(title).formatted(Formatting.DARK_RED));
        target.setCustomNameVisible(true);
        target.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, seconds * 20, 1, false, false));
        if (target instanceof MobEntity m) m.setTarget(null);
        ServerWorld w = owner.getServerWorld();
        w.spawnParticles(RED, target.getX(), target.getY() + target.getHeight() / 2, target.getZ(), 40, 0.4, 0.6, 0.4, 0);
        w.playSound(null, target.getBlockPos(), SoundEvents.ENTITY_ZOMBIE_VILLAGER_CONVERTED, SoundCategory.PLAYERS, 0.8f, 0.6f);
        return true;
    }

    private static void release(Thrall t) {
        Entity e = t.world.getEntity(t.entity);
        if (e == null) return;
        e.removeCommandTag(TAG);
        e.setCustomName(t.oldName);
        e.setCustomNameVisible(t.oldName != null);
        if (e instanceof MobEntity m) { m.getNavigation().stop(); m.setTarget(null); }
        t.world.spawnParticles(ParticleTypes.SMOKE, e.getX(), e.getY() + 0.5, e.getZ(), 15, 0.3, 0.3, 0.3, 0.02);
    }

    public static void releaseAll(UUID owner) {
        for (Iterator<Thrall> it = LIST.iterator(); it.hasNext(); ) {
            Thrall t = it.next();
            if (t.owner.equals(owner)) { release(t); it.remove(); }
        }
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(Thralls::tick);
    }

    private static void tick(MinecraftServer server) {
        clock++;
        if (clock % 5 != 0 || LIST.isEmpty()) return;
        for (Iterator<Thrall> it = LIST.iterator(); it.hasNext(); ) {
            Thrall t = it.next();
            Entity e = t.world.getEntity(t.entity);
            ServerPlayerEntity owner = server.getPlayerManager().getPlayer(t.owner);
            if (e == null || !e.isAlive() || owner == null || owner.getServerWorld() != t.world || t.untilTick < clock) {
                release(t);
                it.remove();
                continue;
            }
            if (!(e instanceof MobEntity mob)) continue;
            if (mob.getTarget() == owner) mob.setTarget(null);   // 주인은 공격하지 않음
            if (clock % 20 == 0) t.world.spawnParticles(RED, e.getX(), e.getY() + e.getHeight() + 0.2, e.getZ(), 2, 0.1, 0.1, 0.1, 0);
            // 공격할 상대: 주인이 최근(8초 안)에 때린 상대
            Entity target = null;
            var f = Combat.peek(owner);
            if (f != null && f.lastTarget != null && Combat.now() - f.lastTargetAt < 160) {
                Entity c = t.world.getEntity(f.lastTarget);
                if (c != null && c.isAlive() && c != e && c.squaredDistanceTo(owner) < 16 * 16 && Targets.canHit(owner, c) && !ownedBy(c, owner)) target = c;
            }
            if (target != null) {
                mob.getNavigation().startMovingTo(target.getX(), target.getY(), target.getZ(), 1.4);
                Long next = NEXT_HIT.get(t.entity);
                if (e.squaredDistanceTo(target) < 3.0 * 3.0 && (next == null || next < clock)) {
                    NEXT_HIT.put(t.entity, clock + 30);
                    mob.swingHand(net.minecraft.util.Hand.MAIN_HAND);
                    Combat.hit(owner, (LivingEntity) target, STRIKE);
                    t.world.playSound(null, target.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.PLAYERS, 0.8f, 0.8f);
                }
            } else if (e.squaredDistanceTo(owner) > 4 * 4) {
                if (e.squaredDistanceTo(owner) > 32 * 32) e.requestTeleport(owner.getX(), owner.getY(), owner.getZ());
                else mob.getNavigation().startMovingTo(owner.getX(), owner.getY(), owner.getZ(), 1.2);
            }
        }
    }
}
