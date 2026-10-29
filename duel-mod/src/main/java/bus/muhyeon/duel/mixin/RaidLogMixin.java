package bus.muhyeon.duel.mixin;

import bus.muhyeon.duel.DuelMod;
import bus.muhyeon.duel.RaidBoss;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.battles.ShowdownInterpreter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 레이드 배틀의 쇼다운 원문을 로그에 남김 (멈춤 원인 추적용) */
@Mixin(value = ShowdownInterpreter.class, remap = false)
public class RaidLogMixin {
    @Inject(method = "interpret", at = @At("HEAD"), remap = false)
    private void duel$logRaid(PokemonBattle battle, String message, CallbackInfo ci) {
        try {
            if (battle != null && RaidBoss.isRaidBattle(battle)) {
                DuelMod.LOG.info("[raid-sd] {}", message.replace("\n", " ⏎ "));
            }
        } catch (Throwable ignored) {}
    }
}
