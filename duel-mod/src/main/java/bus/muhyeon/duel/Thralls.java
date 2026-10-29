package bus.muhyeon.duel;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
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
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 하인: 뱀파이어가 흡혈·육아로 부리는 야생 포켓몬·주민.
 * 주인을 따라다니고, 주인이 최근(8초 안)에 때린 상대에게 달려가 공격한다. 시간이 다 되면 풀려남.
 * 하인인 동안은 포획할 수 없다.
 */
public final class Thralls {
    private Thralls() {}

    record Thrall(ServerWorld world, UUID entity, UUID owner, long until, float damage, Text oldName, boolean owned) {}

    private static final List<Thrall> LIST = new ArrayList<>();
    /** 주인 → (최근 때린 상대, 시각) */
    private static final Map<UUID, UUID> LAST_TARGET = new HashMap<>();
    private static final Map<UUID, Long> LAST_TIME = new HashMap<>();
    private static final Map<UUID, Long> NEXT_HIT = new HashMap<>();
    private static final DustParticleEffect RED = new DustParticleEffect(new Vector3f(0.7f, 0.0f, 0.05f), 1.0f);
    private static int clock = 0;

    public static int count(UUID owner) {
        int n = 0;
        for (Thrall t : LIST) if (t.owner.equals(owner)) n++;
        return n;
    }

    public static boolean isThrall(Entity e) {
        for (Thrall t : LIST) if (t.entity.equals(e.getUuid())) return true;
        return false;
    }

    /** 하인으로 만들기. max = 동시에 부릴 수 있는 수 (넘치면 가장 오래된 하인을 풀어 줌) */
    public static boolean bind(ServerPlayerEntity owner, LivingEntity target, int seconds, float damage, int max, String title) {
        return bind(owner, target, seconds, damage, max, title, false);
    }

    /** owned = 주인의 포켓몬(경호원). 풀려나면 볼로 돌아감 */
    public static boolean bind(ServerPlayerEntity owner, LivingEntity target, int seconds, float damage, int max, String title, boolean owned) {
        if (isThrall(target)) return false;
        while (count(owner.getUuid()) >= max) {
            for (Thrall t : LIST) if (t.owner.equals(owner.getUuid())) { release(t); LIST.remove(t); break; }
        }
        LIST.add(new Thrall(owner.getServerWorld(), target.getUuid(), owner.getUuid(), System.currentTimeMillis() + seconds * 1000L, damage, target.getCustomName(), owned));
        target.setCustomName(Text.literal(title).formatted(owned ? Formatting.GOLD : Formatting.DARK_RED));
        target.setCustomNameVisible(true);
        target.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, seconds * 20, 1, false, false));
        ServerWorld w = owner.getServerWorld();
        w.spawnParticles(RED, target.getX(), target.getY() + target.getHeight() / 2, target.getZ(), 40, 0.4, 0.6, 0.4, 0);
        w.playSound(null, target.getBlockPos(), SoundEvents.ENTITY_ZOMBIE_VILLAGER_CONVERTED, SoundCategory.PLAYERS, 0.8f, 0.6f);
        return true;
    }

    private static void release(Thrall t) {
        Entity e = t.world.getEntity(t.entity);
        if (e == null) return;
        e.setCustomName(t.oldName);
        e.setCustomNameVisible(t.oldName != null);
        if (e instanceof MobEntity m) m.getNavigation().stop();
        if (t.owned && e instanceof PokemonEntity pe) { pe.getPokemon().recall(); return; }
        t.world.spawnParticles(ParticleTypes.SMOKE, e.getX(), e.getY() + 0.5, e.getZ(), 15, 0.3, 0.3, 0.3, 0.02);
    }

    public static void releaseAll(UUID owner) {
        for (Iterator<Thrall> it = LIST.iterator(); it.hasNext(); ) {
            Thrall t = it.next();
            if (t.owner.equals(owner)) { release(t); it.remove(); }
        }
    }

    public static void register() {
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            if (source.getAttacker() instanceof ServerPlayerEntity atk && entity != atk && !isThrall(entity)) {
                LAST_TARGET.put(atk.getUuid(), entity.getUuid());
                LAST_TIME.put(atk.getUuid(), System.currentTimeMillis());
            }
        });
        CobblemonEvents.POKEMON_CATCH_RATE.subscribe(Priority.LOW, ev -> {
            if (isThrall(ev.getPokemonEntity())) ev.setCatchRate(0f);
            return kotlin.Unit.INSTANCE;
        });
        ServerTickEvents.END_SERVER_TICK.register(Thralls::tick);
    }

    private static void tick(MinecraftServer server) {
        if (++clock % 5 != 0 || LIST.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Iterator<Thrall> it = LIST.iterator(); it.hasNext(); ) {
            Thrall t = it.next();
            Entity e = t.world.getEntity(t.entity);
            ServerPlayerEntity owner = server.getPlayerManager().getPlayer(t.owner);
            if (e == null || !e.isAlive() || owner == null || owner.getServerWorld() != t.world || t.until < now
                    || (e instanceof PokemonEntity pe && (pe.isBattling() || (!t.owned && pe.getOwnerUuid() != null)))) {
                release(t);
                it.remove();
                continue;
            }
            if (!(e instanceof MobEntity mob)) continue;
            if (clock % 20 == 0) t.world.spawnParticles(RED, e.getX(), e.getY() + e.getHeight() + 0.2, e.getZ(), 2, 0.1, 0.1, 0.1, 0);
            // 공격할 상대
            Entity target = null;
            UUID tid = LAST_TARGET.get(t.owner);
            Long tt = LAST_TIME.get(t.owner);
            if (tid != null && tt != null && now - tt < 8000) {
                Entity c = t.world.getEntity(tid);
                if (c != null && c.isAlive() && c != e && c.squaredDistanceTo(owner) < 16 * 16 && Targets.canHit(owner, c)) target = c;
            }
            if (target != null) {
                mob.getNavigation().startMovingTo(target.getX(), target.getY(), target.getZ(), 1.4);
                Long next = NEXT_HIT.get(t.entity);
                if (e.squaredDistanceTo(target) < 3.0 * 3.0 && (next == null || next < now)) {
                    NEXT_HIT.put(t.entity, now + 1500);
                    mob.swingHand(net.minecraft.util.Hand.MAIN_HAND);
                    Targets.damage(owner, target, t.damage);
                    t.world.spawnParticles(ParticleTypes.CRIT, target.getX(), target.getY() + 1, target.getZ(), 10, 0.3, 0.4, 0.3, 0.2);
                    t.world.playSound(null, target.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.PLAYERS, 0.8f, 0.8f);
                }
            } else if (e.squaredDistanceTo(owner) > 4 * 4) {
                if (e.squaredDistanceTo(owner) > 32 * 32) e.requestTeleport(owner.getX(), owner.getY(), owner.getZ());
                else mob.getNavigation().startMovingTo(owner.getX(), owner.getY(), owner.getZ(), 1.2);
            }
        }
    }
}
