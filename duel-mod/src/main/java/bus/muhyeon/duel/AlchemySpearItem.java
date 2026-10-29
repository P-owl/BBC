package bus.muhyeon.duel;

import net.minecraft.component.type.AttributeModifierSlot;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

import java.util.List;

/**
 * 연성 창: 오토메일 의수로 땅에서 뽑아낸 강철 창.
 * 공격력 12 · 사거리 +1.5칸 · 5번 때리면 부서짐 · 60초가 지나도 부서짐.
 */
public class AlchemySpearItem extends Item {
    public static final int HITS = 5;
    public static final int LIFE_TICKS = 60 * 20;
    private static final Identifier REACH_ID = Identifier.of(DuelMod.MOD_ID, "spear_reach");

    public AlchemySpearItem(Settings settings) { super(settings); }

    public static AttributeModifiersComponent attributes() {
        return AttributeModifiersComponent.builder()
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE,
                        new EntityAttributeModifier(BASE_ATTACK_DAMAGE_MODIFIER_ID, 11, EntityAttributeModifier.Operation.ADD_VALUE), AttributeModifierSlot.MAINHAND)
                .add(EntityAttributes.GENERIC_ATTACK_SPEED,
                        new EntityAttributeModifier(BASE_ATTACK_SPEED_MODIFIER_ID, -2.7, EntityAttributeModifier.Operation.ADD_VALUE), AttributeModifierSlot.MAINHAND)
                .add(EntityAttributes.PLAYER_ENTITY_INTERACTION_RANGE,
                        new EntityAttributeModifier(REACH_ID, 1.5, EntityAttributeModifier.Operation.ADD_VALUE), AttributeModifierSlot.MAINHAND)
                .build();
    }

    /** 때릴 때마다 내구도 1 → 5번째에 부서짐 */
    @Override
    public boolean postHit(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        if (attacker.getWorld() instanceof ServerWorld w) {
            w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, target.getX(), target.getY() + 1, target.getZ(), 6, 0.3, 0.4, 0.3, 0.2);
            if (stack.getDamage() + 1 >= stack.getMaxDamage()) crumbleFx(w, attacker);
        }
        stack.damage(1, attacker, EquipmentSlot.MAINHAND);
        return true;
    }

    /** 수명이 다하면 부서짐 (만들어진 시각은 아이템을 처음 손에 든 틱부터 셈) */
    @Override
    public void inventoryTick(ItemStack stack, World world, Entity entity, int slot, boolean selected) {
        if (world.isClient || !(entity instanceof LivingEntity le)) return;
        net.minecraft.component.type.NbtComponent data = stack.get(net.minecraft.component.DataComponentTypes.CUSTOM_DATA);
        long now = world.getTime();
        long born;
        if (data == null || !data.contains("born")) {
            net.minecraft.nbt.NbtCompound n = new net.minecraft.nbt.NbtCompound();
            n.putLong("born", now);
            stack.set(net.minecraft.component.DataComponentTypes.CUSTOM_DATA, net.minecraft.component.type.NbtComponent.of(n));
            return;
        }
        born = data.copyNbt().getLong("born");
        if (now - born >= LIFE_TICKS) {
            if (world instanceof ServerWorld w) crumbleFx(w, le);
            stack.setCount(0);
        }
    }

    private static void crumbleFx(ServerWorld w, LivingEntity holder) {
        w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, net.minecraft.block.Blocks.IRON_BLOCK.getDefaultState()),
                holder.getX(), holder.getY() + 1.2, holder.getZ(), 30, 0.4, 0.4, 0.4, 0.2);
        w.playSound(null, holder.getBlockPos(), net.minecraft.sound.SoundEvents.ENTITY_ITEM_BREAK, net.minecraft.sound.SoundCategory.PLAYERS, 1.0f, 0.7f);
    }

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
        tooltip.add(Text.literal("오토메일 의수로 연성한 강철 창").formatted(Formatting.GRAY));
        tooltip.add(Text.literal("공격력 12 · 사거리 +1.5 · " + HITS + "번 때리면 부서짐 · 60초 뒤 부서짐").formatted(Formatting.AQUA));
    }
}
