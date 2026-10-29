package bus.muhyeon.duel;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

/** 도전장: 대선 후보가 대통령 4칸 안에서 대통령을 우클릭 */
public class ChallengeLetterItem extends Item {
    public ChallengeLetterItem(Settings s) { super(s); }

    @Override public boolean hasGlint(ItemStack stack) { return true; }

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext c, List<Text> tip, TooltipType type) {
        tip.add(Text.literal("대통령 자리를 건 결투 신청서").formatted(Formatting.GOLD));
        tip.add(Text.literal("대통령 4칸 안에서 대통령을 우클릭 (레벨 100 고정 6:6)").formatted(Formatting.GRAY));
        tip.add(Text.literal("거절할 때마다 대통령은 큰 벌을 받습니다").formatted(Formatting.DARK_GRAY));
    }
}
