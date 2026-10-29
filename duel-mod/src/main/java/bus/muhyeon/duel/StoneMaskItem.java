package bus.muhyeon.duel;

import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Equipment;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

import java.util.List;

/** 석가면: 머리에 쓸 수 있음. 밤에 쓴 채로 피를 묻히면 뱀파이어가 된다 (Vampire 참고) */
public class StoneMaskItem extends Item implements Equipment {
    public StoneMaskItem(Settings s) { super(s); }

    @Override public EquipmentSlot getSlotType() { return EquipmentSlot.HEAD; }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        return equipAndSwap(this, world, user, hand);
    }

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext ctx, List<Text> tip, TooltipType type) {
        tip.add(Text.literal("고대 문명이 남긴 돌가면").formatted(Formatting.GRAY));
        tip.add(Text.literal("밤에 쓰고… 피를 묻히면…?").formatted(Formatting.DARK_RED, Formatting.ITALIC));
        tip.add(Text.literal("기둥 속 사내 각성에도 필요합니다").formatted(Formatting.DARK_GRAY));
    }
}
