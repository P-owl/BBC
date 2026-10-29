package bus.muhyeon.duel.mixin;

import bus.muhyeon.duel.AlchemyState;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.pokemon.Pokemon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 배틀이 끝나면 코블몬이 바로 포켓몬을 회수하는데,
 * 연금술사/현자 피카츄가 살아 있으면 승리 포즈를 보여준 뒤 회수한다.
 */
@Mixin(value = PokemonBattle.class, remap = false)
public class BattleEndRecallMixin {
    @Redirect(method = "end", remap = false,
            at = @At(value = "INVOKE", target = "Lcom/cobblemon/mod/common/pokemon/Pokemon;tryRecallWithAnimation()V", remap = false))
    private void duel$delayRecall(Pokemon pokemon) {
        if (!AlchemyState.victoryPose(pokemon)) pokemon.tryRecallWithAnimation();
    }
}
