package bus.muhyeon.duel;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;

import java.util.List;

/** 고대의 기둥: 숙련된 뱀파이어가 밤에 고대 도시·사막 피라미드 안 바닥에 대고 우클릭 → 기둥 각성 의식 */
public class AncientPillarItem extends Item {
    public AncientPillarItem(Settings s) { super(s); }

    @Override
    public ActionResult useOnBlock(ItemUsageContext ctx) {
        if (ctx.getWorld().isClient) return ActionResult.SUCCESS;
        if (!(ctx.getPlayer() instanceof ServerPlayerEntity p)) return ActionResult.PASS;
        return Pillar.tryPlace(p, ctx.getBlockPos().up());
    }

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext c, List<Text> tip, TooltipType type) {
        tip.add(Text.literal("오랜 잠을 품은 돌기둥").formatted(Formatting.DARK_PURPLE));
        tip.add(Text.literal("숙련된 뱀파이어: 밤에 고대 도시·사막 피라미드 안 바닥에 우클릭").formatted(Formatting.GRAY));
    }
}
