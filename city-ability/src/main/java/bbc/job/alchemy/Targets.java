package bbc.job.alchemy;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * 연성 기술이 맞힐 수 있는 대상.
 *  - 몹: 전부 (내가 길들인 동물·갑옷 거치대 제외)
 *  - 플레이어: 서버 PvP 설정을 따름
 */
public final class Targets {
    private Targets() {}

    public static boolean canHit(ServerPlayerEntity caster, Entity e) {
        if (!(e instanceof LivingEntity) || !e.isAlive() || e == caster) return false;
        if (e.getType() == EntityType.ARMOR_STAND) return false;
        if (e instanceof TameableEntity te && caster.getUuid().equals(te.getOwnerUuid())) return false;
        return !pvpBlocked(caster, e);
    }

    /** 플레이어인데 PvP 가 꺼져 있어서 효과를 못 주는 대상 */
    public static boolean pvpBlocked(ServerPlayerEntity caster, Entity e) {
        return e instanceof ServerPlayerEntity && caster.getServer() != null && !caster.getServer().isPvpEnabled();
    }
}
