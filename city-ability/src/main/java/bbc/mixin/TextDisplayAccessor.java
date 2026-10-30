package bbc.mixin;

import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(DisplayEntity.TextDisplayEntity.class)
public interface TextDisplayAccessor {
    @Invoker("setText") void bbc$setText(Text t);
    @Invoker("setBackground") void bbc$setBackground(int argb);
}
