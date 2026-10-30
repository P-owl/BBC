package bbc.mixin;

import net.minecraft.entity.decoration.Brightness;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.util.math.AffineTransformation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 디스플레이 엔티티(블록·글자) 연출: 변형·보간을 서버에서 바꾸려고 private 세터를 연다 */
@Mixin(DisplayEntity.class)
public interface DisplayEntityAccessor {
    @Invoker("setTransformation") void bbc$setTransformation(AffineTransformation t);
    @Invoker("setInterpolationDuration") void bbc$setInterpolationDuration(int ticks);
    @Invoker("setStartInterpolation") void bbc$setStartInterpolation(int ticks);
    @Invoker("setBrightness") void bbc$setBrightness(Brightness b);
    @Invoker("setBillboardMode") void bbc$setBillboardMode(DisplayEntity.BillboardMode m);
}
