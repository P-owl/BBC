package bbc.job.alchemy;

import bbc.core.Tasks;
import bbc.core.ButtonMenu;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

/** 연성 장갑: 진리를 보기 전의 연금술사는 이걸 손(주 손이나 보조 손)에 끼고 있어야 연성 기술을 쓸 수 있음 */
public class AlchemyGloveItem extends Item {
    public AlchemyGloveItem(Settings settings) { super(settings); }

    public static boolean wearing(PlayerEntity p) {
        return p.getMainHandStack().isOf(AlchemyItems.ALCHEMY_GLOVE) || p.getOffHandStack().isOf(AlchemyItems.ALCHEMY_GLOVE);
    }

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
        tooltip.add(Text.translatable("item.bbc.alchemy_glove.tooltip1").formatted(Formatting.GRAY));
        tooltip.add(Text.translatable("item.bbc.alchemy_glove.tooltip2").formatted(Formatting.DARK_GRAY));
    }
}
