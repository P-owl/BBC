package bus.muhyeon.duel.mixin;

import bus.muhyeon.duel.AlchemyState;
import com.cobblemon.mod.common.api.battles.interpreter.BattleMessage;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.battles.interpreter.instructions.StartInstruction;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 무기 연성 시작(-start ... move: Weapon Forge) → 꼬리 칼날 표시 */
@Mixin(value = StartInstruction.class, remap = false)
public class AlchemyStartMixin {
    @Shadow @Final private BattleMessage message;

    @Inject(method = "invoke", at = @At("HEAD"), cancellable = true, remap = false)
    private void duel$onStart(PokemonBattle battle, CallbackInfo ci) {
        String raw = message.getRawMessage();
        if (raw == null) return;
        if (raw.contains("alchemy:raiddmg") || raw.contains("alchemy:raidheal")) {
            int amount = 0;
            for (String part : raw.split("\\|")) {
                String t = part.trim();
                if (t.matches("\\d+")) amount = Integer.parseInt(t);
            }
            bus.muhyeon.duel.RaidBoss.onReport(battle, amount, raw.contains("alchemy:raidheal"));
            ci.cancel();
            return;
        }
        if (raw.contains("alchemy:sacrifice")) {
            BattlePokemon bp = message.battlePokemon(0, battle);
            if (bp != null) {
                bp.getEffectedPokemon().setCurrentHealth(0);
                battle.broadcastChatMessage(net.minecraft.text.Text.translatable("duel.battle.sacrifice", bp.getName())
                        .formatted(net.minecraft.util.Formatting.DARK_RED));
            }
            ci.cancel();
            return;
        }
        if (raw.contains("alchemy:blade")) {
            BattlePokemon bp = message.battlePokemon(0, battle);
            if (bp != null) AlchemyState.blade(bp.getEffectedPokemon());
            ci.cancel();
            return;
        }
        if (!raw.contains("Weapon Forge")) return;
        BattlePokemon bp = message.battlePokemon(0, battle);
        if (bp != null) AlchemyState.setForged(bp.getEffectedPokemon(), true);
    }
}
