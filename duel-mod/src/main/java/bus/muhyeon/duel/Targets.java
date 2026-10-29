package bus.muhyeon.duel;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.UUID;

/**
 * 연성 기술·피카츄 동료가 맞힐 수 있는 대상.
 *  - 몹: 전부 (내가 길들인 동물 제외)
 *  - 플레이어: 피해 판정이 서버 PvP 설정을 따름
 *  - 포켓몬: 야생은 전부, 남의 포켓몬은 PvP 가 켜져 있을 때만, 내 포켓몬·배틀 중인 포켓몬은 제외
 */
public final class Targets {
    private Targets() {}

    public static boolean canHit(ServerPlayerEntity caster, Entity e) {
        if (!(e instanceof LivingEntity) || !e.isAlive() || e == caster) return false;
        if (e.getType() == EntityType.ARMOR_STAND) return false;
        if (e instanceof PokemonEntity pe) {
            if (pe.isBattling()) return false;
            Pokemon mon = pe.getPokemon();
            UUID owner = mon == null ? null : mon.getOwnerUUID();
            if (owner == null) return true;                       // 야생
            if (owner.equals(caster.getUuid())) return false;    // 내 포켓몬
            return caster.getServer() != null && caster.getServer().isPvpEnabled();
        }
        if ("cobblemon".equals(Registries.ENTITY_TYPE.getId(e.getType()).getNamespace())) return false; // 볼·장식 등
        if (e instanceof TameableEntity te && caster.getUuid().equals(te.getOwnerUuid())) return false;
        return true;
    }

    /** 포켓몬은 엔티티 체력이 플레이어보다 훨씬 많아서 피해를 더 크게 */
    public static final float POKEMON_MULT = 2.5f;

    /** 연성 기술 피해 (시전자의 공격으로 처리 → PvP 설정·킬 판정 그대로) */
    public static boolean damage(ServerPlayerEntity caster, Entity t, float amount) {
        if (t instanceof PokemonEntity) {
            // 코블몬은 '플레이어가 준 피해'를 설정(playerDamagePokemon)으로 막으므로, 포켓몬에게는 주인 없는 마법 피해로 넣음
            return t.damage(t.getDamageSources().magic(), amount * POKEMON_MULT);
        }
        return t.damage(caster.getDamageSources().playerAttack(caster), amount);
    }

    /** 플레이어인데 PvP 가 꺼져 있어서 효과를 못 주는 대상 */
    public static boolean pvpBlocked(ServerPlayerEntity caster, Entity e) {
        return e instanceof ServerPlayerEntity && caster.getServer() != null && !caster.getServer().isPvpEnabled();
    }
}
