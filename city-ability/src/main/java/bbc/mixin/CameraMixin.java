package bbc.mixin;

import bbc.client.CombatHudBridge;
import bbc.client.Cutscene;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 컷신 카메라 (Cutscene) + 화면 흔들림: 카메라 각도에만 살짝 더한다 (조준·몸 방향은 그대로) */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow protected abstract void setRotation(float yaw, float pitch);
    @Shadow protected abstract void setPos(double x, double y, double z);
    @Shadow public abstract float getYaw();
    @Shadow public abstract float getPitch();
    @Shadow public abstract Vec3d getPos();
    @Shadow private boolean thirdPerson;

    @Inject(method = "update", at = @At("TAIL"))
    private void bbc$shake(BlockView area, Entity focused, boolean thirdPerson, boolean inverse, float delta, CallbackInfo ci) {
        double[] cs = Cutscene.camera(getPos(), getYaw(), getPitch(), delta);
        if (cs != null) {
            setPos(cs[0], cs[1], cs[2]);
            setRotation((float) cs[3], (float) cs[4]);
            this.thirdPerson = true;   // 내 몸이 보이고 1인칭 손은 안 그림
        }
        float[] s = CombatHudBridge.shake(delta);
        if (s != null) setRotation(getYaw() + s[0], getPitch() + s[1]);
    }
}
