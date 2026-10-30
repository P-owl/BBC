package bbc.mixin;

import bbc.client.BbcClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 평타 중엔 바닐라 점프를 끈다. 점프 키 자체는 그대로 눌려 있어서 평타 입력(스페이스+평타) 판정에는 쓰인다 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void bbc$noJump(boolean slowDown, float slowDownFactor, CallbackInfo ci) {
        if (BbcClient.blockJump()) ((Input) (Object) this).jumping = false;
    }
}
