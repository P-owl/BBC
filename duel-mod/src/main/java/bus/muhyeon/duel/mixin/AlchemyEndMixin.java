package bus.muhyeon.duel.mixin;

import bus.muhyeon.duel.AlchemyState;
import com.cobblemon.mod.common.api.battles.interpreter.BattleMessage;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.battles.interpreter.instructions.EndInstruction;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 무기 연성 소모(-end ... move: Weapon Forge) → 꼬리 기본 형태로 */
@Mixin(value = EndInstruction.class, remap = false)
public class AlchemyEndMixin {
    @Shadow @Final private BattleMessage message;

    @Inject(method = "invoke", at = @At("HEAD"), remap = false)
    private void duel$onEnd(PokemonBattle battle, CallbackInfo ci) {
        String raw = message.getRawMessage();
        if (raw == null || !raw.contains("Weapon Forge")) return;
        BattlePokemon bp = message.battlePokemon(0, battle);
        if (bp != null) AlchemyState.setForged(bp.getEffectedPokemon(), false);
    }
}
