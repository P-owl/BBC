package bbc.mixin;

import bbc.client.BbcClient;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 전투 모드에서 좌클릭을 평타로 바꾼다 (클라이언트).
 *  - 누르는 순간: 바닐라 공격 대신 평타 패킷
 *  - 누르고 있는 동안: 블록 캐기 대신 평타를 계속 보냄 (서버가 간격을 맞춤)
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    @Inject(method = "doAttack", at = @At("HEAD"), cancellable = true)
    private void bbc$doAttack(CallbackInfoReturnable<Boolean> cir) {
        if (BbcClient.combat) {
            BbcClient.m1(true);
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "handleBlockBreaking", at = @At("HEAD"), cancellable = true)
    private void bbc$handleBlockBreaking(boolean breaking, CallbackInfo ci) {
        if (BbcClient.combat) {
            if (breaking) BbcClient.m1(false);
            ci.cancel();
        }
    }
}
