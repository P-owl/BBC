package bbc.mixin;

import bbc.client.BbcClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 컷신 동안엔 움직임 입력을 막는다. 평타 중엔 바닐라 점프를 끈다. 점프 키 자체는 그대로 눌려 있어서 평타 입력(스페이스+평타) 판정에는 쓰인다 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void bbc$noJump(boolean slowDown, float slowDownFactor, CallbackInfo ci) {
        Input in = (Input) (Object) this;
        if (BbcClient.blockJump()) in.jumping = false;
        if (bbc.client.Cutscene.locked()) {   // 컷신 동안 움직이지 않음
            in.movementForward = 0; in.movementSideways = 0;
            in.pressingForward = in.pressingBack = in.pressingLeft = in.pressingRight = false;
            in.jumping = false; in.sneaking = false;
        }
    }
}
