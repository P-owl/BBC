package bbc.job.alchemy;

import bbc.core.Tasks;
import bbc.core.ButtonMenu;

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
 * 연성 창: 땅에서 뽑아낸 강철 창. 무기 칸(2·3번)에 들어가 창 평타·창 기술을 씀 (Weapon.of 가 창으로 봄).
 * 30초 뒤 부서짐 (전투 평타는 아이템 내구도를 안 쓰니 수명으로만).
 */
public class AlchemySpearItem extends Item {
    public static final int HITS = 64;
    public static final int LIFE_TICKS = 30 * 20;
    private static final Identifier REACH_ID = Identifier.of("bbc", "spear_reach");

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
        tooltip.add(Text.literal("땅에서 연성한 강철 창").formatted(Formatting.GRAY));
        tooltip.add(Text.literal("무기 칸에서 창으로 싸움 · 30초 뒤 부서짐").formatted(Formatting.AQUA));
    }
}
