package bus.muhyeon.duel;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

import java.util.List;

/** 오토메일: 진리의 문에 바친 신체 부위를 강철로 대신함. 우클릭으로 장착(소모), 영구 */
public class AutomailItem extends Item {
    private final String part;

    public AutomailItem(String part, Settings settings) {
        super(settings);
        this.part = part;
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (world.isClient || !(user instanceof ServerPlayerEntity p)) return TypedActionResult.success(stack);
        AlchemyData data = AlchemyData.get(p.getServer());
        AlchemyData.Entry e = data.of(p.getUuid());
        if (!e.truth || e.toll.isEmpty()) {
            p.sendMessage(Text.literal("진리의 문에 몸의 일부를 바친 자만 오토메일을 달 수 있습니다").formatted(Formatting.RED), true);
            return TypedActionResult.fail(stack);
        }
        if (!e.toll.equals(part)) {
            p.sendMessage(Text.literal("잃어버린 부위가 다릅니다 — 필요한 건 " + Jobs.automailName(e.toll)).formatted(Formatting.RED), true);
            return TypedActionResult.fail(stack);
        }
        if (part.equals(e.automail)) {
            p.sendMessage(Text.literal("이미 " + Jobs.automailName(part) + "를 달고 있습니다").formatted(Formatting.GRAY), true);
            return TypedActionResult.fail(stack);
        }
        e.automail = part;
        data.markDirty();
        stack.decrement(1);
        Jobs.applyToll(p);
        p.heal(p.getMaxHealth());
        p.getServerWorld().playSound(null, p.getBlockPos(), SoundEvents.BLOCK_ANVIL_USE, SoundCategory.PLAYERS, 1.0f, 0.8f);
        p.getServerWorld().playSound(null, p.getBlockPos(), SoundEvents.BLOCK_IRON_DOOR_CLOSE, SoundCategory.PLAYERS, 1.0f, 0.6f);
        p.getServerWorld().spawnParticles(net.minecraft.particle.ParticleTypes.ELECTRIC_SPARK, p.getX(), p.getY() + 1, p.getZ(), 40, 0.4, 0.7, 0.4, 0.3);
        p.sendMessage(Text.literal("⚙ " + Jobs.automailName(part) + " 장착 완료 — " + effect(part)).formatted(Formatting.GOLD, Formatting.BOLD), false);
        return TypedActionResult.consume(stack);
    }

    static String effect(String part) {
        return switch (part) {
            case "arm" -> "공격력 +15%, 채굴 속도 +15%, 넉백 저항 +20% · 전투 8번: 칼날 연성 (20초 공격력 +6)";
            case "leg" -> "이동 속도 +10%, 턱 오르기 +0.5칸, 안전 낙하 +4칸 · 전투 8번: 칼날 차기";
            case "heart" -> "최대 체력 +4 (하트 2칸), 방어 +3";
            default -> "";
        };
    }

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
        tooltip.add(Text.literal("진리의 문에 바친 부위를 강철로 대신합니다").formatted(Formatting.GRAY));
        tooltip.add(Text.literal("효과: 페널티 제거 + " + effect(part)).formatted(Formatting.AQUA));
        tooltip.add(Text.literal("우클릭으로 장착 (영구, 1회)").formatted(Formatting.DARK_GRAY));
    }
}
