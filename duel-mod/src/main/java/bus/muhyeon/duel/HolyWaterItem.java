package bus.muhyeon.duel;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

/** 성수: 신부가 만들고, 전투 모드 2번(성수 투척)으로 던진다 */
public class HolyWaterItem extends Item {
    public HolyWaterItem(Settings s) { super(s); }

    @Override
    public boolean hasGlint(ItemStack stack) { return true; }

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext ctx, List<Text> tip, TooltipType type) {
        tip.add(Text.literal("신부가 축복한 물").formatted(Formatting.YELLOW));
        tip.add(Text.literal("신부 전투 모드 2번: 던져서 4칸 범위 신성 피해 (뱀파이어 2배)").formatted(Formatting.GRAY));
    }
}
