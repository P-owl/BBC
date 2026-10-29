package bus.muhyeon.duel;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

import java.util.List;

/** 에이자의 적석: 서버에 단 하나. 기둥 속 사내가 석가면을 쓰고 한낮에 우클릭 → 완전생물 의식 */
public class AjaStoneItem extends Item {
    public AjaStoneItem(Settings s) { super(s); }

    @Override public boolean hasGlint(ItemStack stack) { return true; }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack st = user.getStackInHand(hand);
        if (world.isClient || !(user instanceof ServerPlayerEntity p)) return TypedActionResult.pass(st);
        return Ultimate.tryRitual(p, st) == ActionResult.SUCCESS ? TypedActionResult.success(st) : TypedActionResult.fail(st);
    }

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext c, List<Text> tip, TooltipType type) {
        tip.add(Text.literal("빛을 수만 배로 증폭시키는 붉은 보석").formatted(Formatting.RED));
        tip.add(Text.literal("세상에 단 하나뿐이다").formatted(Formatting.DARK_RED, Formatting.ITALIC));
        tip.add(Text.literal("들고 있는 동안 위치가 지도에 공개됩니다").formatted(Formatting.GRAY));
    }
}
