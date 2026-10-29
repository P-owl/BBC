package bus.muhyeon.duel.mixin;

import bus.muhyeon.duel.AutomailRender;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 진리의 통행료로 잃은 팔(오른팔)·다리(왼다리)를 모델에서 숨김 (클라이언트) */
@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerRendererMixin {
    @Inject(method = "setModelPose", at = @At("TAIL"))
    private void duel$hideLostParts(AbstractClientPlayerEntity player, CallbackInfo ci) {
        AutomailRender.Body b = AutomailRender.body(player.getUuid());
        if (b == null) return;
        PlayerEntityModel<AbstractClientPlayerEntity> m = ((PlayerEntityRenderer) (Object) this).getModel();
        if (b.armGone()) { m.rightArm.visible = false; m.rightSleeve.visible = false; }
        if (b.legGone()) { m.leftLeg.visible = false; m.leftPants.visible = false; }
    }

    /** 1인칭: 잃은 팔은 안 그리고, 오토메일이면 강철 팔을 그림 */
    @Inject(method = "renderArm", at = @At("HEAD"), cancellable = true)
    private void duel$firstPersonArm(MatrixStack matrices, VertexConsumerProvider vcp, int light, AbstractClientPlayerEntity player,
                                     ModelPart arm, ModelPart sleeve, CallbackInfo ci) {
        AutomailRender.Body b = AutomailRender.body(player.getUuid());
        if (b == null || !b.armGone()) return;
        PlayerEntityModel<AbstractClientPlayerEntity> m = ((PlayerEntityRenderer) (Object) this).getModel();
        if (arm != m.rightArm) return;
        ci.cancel();
        if (b.armMetal()) {
            // 바닐라와 같은 자세로 맞춘 뒤 강철 팔만 그림
            m.handSwingProgress = 0f;
            m.sneaking = false;
            m.leaningPitch = 0f;
            m.setAngles(player, 0f, 0f, 0f, 0f, 0f);
            arm.pitch = 0f;
            AutomailRender.drawFirstPersonArm(b, arm, matrices, vcp, light);
        }
    }
}
