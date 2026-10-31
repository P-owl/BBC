package bbc.job.alchemy;

import bbc.core.Tasks;
import bbc.core.ButtonMenu;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

import java.util.List;

/**
 * 현자의 돌
 *  - 허공 우클릭: 효과 선택창 (StoneMenu)
 *  - 플레이어 우클릭: ① 리스크 없는 결투 (시작될 때 돌 소모)
 *  - 들고만 있어도 연성 손실이 줄고 붉은 스파크가 남
 */
public class PhilosopherStoneItem extends Item {
    public PhilosopherStoneItem(Settings settings) { super(settings); }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (!world.isClient() && user instanceof ServerPlayerEntity sp) {
            StoneFx.small(sp);
            StoneMenu.open(sp);
        }
        return TypedActionResult.success(stack, world.isClient());
    }

    @Override
    public ActionResult useOnEntity(ItemStack stack, PlayerEntity user, LivingEntity entity, Hand hand) {
        if (user.getWorld().isClient()) return entity instanceof PlayerEntity ? ActionResult.SUCCESS : ActionResult.PASS;
        if (!(user instanceof ServerPlayerEntity caster) || !(entity instanceof ServerPlayerEntity target)) return ActionResult.PASS;
        if (target.isSpectator() || target.isCreative()) {
            caster.sendMessage(Text.literal("관전/크리에이티브 상태인 플레이어에게는 쓸 수 없습니다.").formatted(Formatting.RED), true);
            return ActionResult.FAIL;
        }
        String why = StoneDuel.start(caster, target);
        if (why != null) { caster.sendMessage(Text.literal(why).formatted(Formatting.RED), true); return ActionResult.FAIL; }
        StoneFx.big(caster);
        if (!caster.getAbilities().creativeMode) stack.decrement(1);
        return ActionResult.SUCCESS;
    }

    /** 손 → 인벤토리 순으로 현자의 돌 1개 소모. 크리에이티브는 소모 없음. */
    public static boolean consumeOne(ServerPlayerEntity player) {
        if (player.getAbilities().creativeMode) return true;
        for (Hand h : Hand.values()) {
            ItemStack s = player.getStackInHand(h);
            if (s.isOf(AlchemyItems.PHILOSOPHERS_STONE)) { s.decrement(1); return true; }
        }
        var inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.isOf(AlchemyItems.PHILOSOPHERS_STONE)) { s.decrement(1); return true; }
        }
        return false;
    }

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
        tooltip.add(Text.literal("우클릭: 효과 하나를 골라 돌 1개 소모").formatted(Formatting.GRAY));
        tooltip.add(Text.literal("플레이어 우클릭: 리스크 없는 결투").formatted(Formatting.GRAY));
        tooltip.add(Text.literal("지니고 있으면 연성 손실 감소").formatted(Formatting.DARK_GRAY));
    }
}
