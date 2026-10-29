package bus.muhyeon.duel.mixin;

import bus.muhyeon.duel.RaidBoss;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 레이드 보스는 이미 배틀 중이어도 다른 플레이어가 도전할 수 있게 (공유 레이드) */
@Mixin(value = PokemonEntity.class, remap = false)
public abstract class RaidChallengeMixin {
    @Inject(method = "canBattle", at = @At("HEAD"), cancellable = true, remap = false)
    private void duel$raidCanBattle(PlayerEntity player, CallbackInfoReturnable<Boolean> cir) {
        PokemonEntity self = (PokemonEntity) (Object) this;
        if (!RaidBoss.isBoss(self.getPokemon())) return;
        if (player.getWorld().isClient()) {
            bus.muhyeon.duel.DuelMod.LOG.info("[raid] client canBattle -> true ({})", player.getName().getString());
            cir.setReturnValue(true); // 최종 판단은 서버
            return;
        }
        boolean ok = RaidBoss.canChallenge(self, player.getUuid());
        bus.muhyeon.duel.DuelMod.LOG.info("[raid] server canBattle {} -> {}", player.getName().getString(), ok);
        cir.setReturnValue(ok);
    }
}
