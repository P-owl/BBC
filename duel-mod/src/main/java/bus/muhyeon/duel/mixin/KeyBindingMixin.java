package bus.muhyeon.duel.mixin;

import bus.muhyeon.duel.DuelClient;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * 전투 모드 단축키 우선 처리 (클라이언트).
 *  - 전투 모드 중: 기술 키와 같은 키의 다른 단축키(핫바 1~7 등) 입력을 막음
 *  - 평소: 기술 키가 다른 단축키 자리를 빼앗지 않게, 키 → 단축키 표에서 항상 다른 쪽을 남김
 */
@Mixin(KeyBinding.class)
public abstract class KeyBindingMixin {
    @Shadow @Final private static Map<String, KeyBinding> KEYS_BY_ID;
    @Shadow @Final private static Map<InputUtil.Key, KeyBinding> KEY_TO_BINDINGS;

    @Inject(method = "onKeyPressed", at = @At("HEAD"), cancellable = true)
    private static void duel$onKeyPressed(InputUtil.Key key, CallbackInfo ci) {
        if (DuelClient.blocks(key)) ci.cancel();
    }

    @Inject(method = "setKeyPressed", at = @At("HEAD"), cancellable = true)
    private static void duel$setKeyPressed(InputUtil.Key key, boolean pressed, CallbackInfo ci) {
        if (pressed && DuelClient.blocks(key)) ci.cancel();
    }

    @Inject(method = "updateKeysByCode", at = @At("TAIL"))
    private static void duel$keepOthers(CallbackInfo ci) {
        for (KeyBinding k : KEYS_BY_ID.values()) {
            if (k instanceof DuelClient.CombatKeyBinding) continue;
            KEY_TO_BINDINGS.put(KeyBindingHelper.getBoundKeyOf(k), k);
        }
    }
}
