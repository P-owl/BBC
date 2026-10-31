package bbc.job.alchemy;

import bbc.core.PlayerData;
import bbc.job.Jobs;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.UUID;

/**
 * 현자의 돌 ⑤ 연성 골렘 (연금술사 전용, 되돌릴 수 없음). 무현버스의 연금술사 피카츄 동료를 대신함.
 * 철 골렘을 바탕으로: 주인을 따라다니고(16칸 넘게 멀어지면 옆으로 옴), 주인을 때린 상대·주인이 때린 상대를 공격.
 * 주인이 나가면 사라졌다가 다시 들어오면 옆에 나타남. 죽으면 5분 뒤 다시 연성됨.
 */
public final class AlchemyGolem {
    private AlchemyGolem() {}

    private static final String FLAG = "flag.golem", ID = "al.golem_id", DEAD_AT = "al.golem_dead";
    private static final long RESPAWN_MS = 5 * 60 * 1000;

    public static boolean has(ServerPlayerEntity p) { return Jobs.entry(p).flag(FLAG); }

    public static void grant(ServerPlayerEntity p) {
        PlayerData.Entry e = Jobs.entry(p);
        e.flag(FLAG, true);
        Jobs.dirty(p);
        spawn(p, e);
    }

    private static IronGolemEntity find(ServerPlayerEntity p, PlayerData.Entry e) {
        String id = e.str(ID);
        if (id.isEmpty()) return null;
        try {
            Entity en = p.getServerWorld().getEntity(UUID.fromString(id));
            return en instanceof IronGolemEntity g && g.isAlive() ? g : null;
        } catch (IllegalArgumentException ex) { return null; }
    }

    private static void spawn(ServerPlayerEntity p, PlayerData.Entry e) {
        ServerWorld w = p.getServerWorld();
        IronGolemEntity g = EntityType.IRON_GOLEM.create(w);
        if (g == null) return;
        BlockPos at = p.getBlockPos().offset(p.getHorizontalFacing().getOpposite(), 2);
        g.refreshPositionAndAngles(at.getX() + 0.5, p.getY(), at.getZ() + 0.5, p.getYaw(), 0);
        g.setPlayerCreated(true);
        g.setCustomName(Text.literal(p.getName().getString() + "의 연성 골렘").formatted(Formatting.GOLD));
        g.setCustomNameVisible(true);
        g.addCommandTag("bbc_golem");
        g.addCommandTag("bbc_golem:" + p.getUuid());
        w.spawnEntity(g);
        e.str(ID, g.getUuidAsString());
        Alchemy.fx(w, g.getBlockPos(), false);
    }

    private static void despawn(ServerPlayerEntity p) {
        PlayerData.Entry e = Jobs.entry(p);
        IronGolemEntity g = find(p, e);
        if (g != null) g.discard();
    }

    public static void register() {
        ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> { if (has(h.getPlayer())) despawn(h.getPlayer()); });
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (s.getTicks() % 20 != 0) return;
            for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
                PlayerData.Entry e = Jobs.entry(p);
                if (!e.flag(FLAG) || !Alch.ALCHEMIST.equals(e.job) || p.isSpectator() || !p.isAlive()) continue;
                IronGolemEntity g = find(p, e);
                if (g == null) {
                    long dead = (long) e.num(DEAD_AT);
                    if (!e.str(ID).isEmpty() && dead == 0) { e.num(DEAD_AT, System.currentTimeMillis()); continue; }   // 방금 죽음
                    if (dead > 0 && System.currentTimeMillis() - dead < RESPAWN_MS) continue;
                    e.num(DEAD_AT, 0);
                    spawn(p, e);
                    continue;
                }
                if (g.squaredDistanceTo(p) > 16 * 16) {
                    BlockPos at = p.getBlockPos().offset(p.getHorizontalFacing().getOpposite(), 2);
                    g.requestTeleport(at.getX() + 0.5, p.getY(), at.getZ() + 0.5);
                }
                LivingEntity foe = p.getAttacker() != null && p.getAttacker() != g ? p.getAttacker() : p.getAttacking();
                if (foe != null && foe.isAlive() && foe != g && Targets.canHit(p, foe) && foe.squaredDistanceTo(g) < 24 * 24) g.setTarget(foe);
                else if (g.getTarget() == p) g.setTarget(null);
                else if (g.getTarget() == null && g.squaredDistanceTo(p) > 5 * 5) g.getNavigation().startMovingTo(p, 1.0);
            }
        });
    }
}
