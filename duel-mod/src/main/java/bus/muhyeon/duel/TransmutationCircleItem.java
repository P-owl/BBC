package bus.muhyeon.duel;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 연성진: 우클릭하면 눈앞에 연성진이 그려지고 레이드 보스 연금술사 피카츄가 소환된다. */
public class TransmutationCircleItem extends Item {
    private static final int CAST_TICKS = 40;
    private static final Set<UUID> CASTING = ConcurrentHashMap.newKeySet();

    public TransmutationCircleItem(Settings settings) { super(settings); }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (world.isClient() || !(user instanceof ServerPlayerEntity sp)) return TypedActionResult.success(stack, world.isClient());

        if (CASTING.contains(sp.getUuid())) return TypedActionResult.fail(stack);
        if (BattleRegistry.getBattleByParticipatingPlayer(sp) != null) {
            sp.sendMessage(Text.literal("배틀 중에는 쓸 수 없습니다.").formatted(Formatting.RED), true);
            return TypedActionResult.fail(stack);
        }
        boolean ready = false;
        for (Pokemon p : Cobblemon.INSTANCE.getStorage().getParty(sp)) if (p != null && !p.isFainted()) ready = true;
        if (!ready) {
            sp.sendMessage(Text.literal("싸울 수 있는 포켓몬이 없습니다.").formatted(Formatting.RED), true);
            return TypedActionResult.fail(stack);
        }

        // 눈앞 4칸 바닥
        Vec3d look = sp.getRotationVec(1.0f).multiply(1, 0, 1).normalize();
        Vec3d spot = sp.getPos().add(look.multiply(4));
        BlockPos bp = BlockPos.ofFloored(spot);
        int y = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, bp.getX(), bp.getZ());
        if (Math.abs(y - sp.getY()) > 4) y = (int) Math.floor(sp.getY());
        Vec3d pos = new Vec3d(bp.getX() + 0.5, y, bp.getZ() + 0.5);

        if (!sp.getAbilities().creativeMode) stack.decrement(1);
        CASTING.add(sp.getUuid());
        StoneFx.circleAt(sp.getServerWorld(), pos, CAST_TICKS);
        sp.sendMessage(Text.literal("✦ 연성진이 빛나기 시작했다...").formatted(Formatting.AQUA), true);
        Tasks.later(CAST_TICKS, () -> {
            CASTING.remove(sp.getUuid());
            if (sp.isRemoved()) return;
            boolean ok = RaidBoss.summon(sp, pos);
            if (!ok && !sp.getAbilities().creativeMode) {
                sp.getInventory().offerOrDrop(new ItemStack(DuelMod.TRANSMUTATION_CIRCLE));
                sp.sendMessage(Text.literal("연성에 실패해 연성진을 돌려받았습니다.").formatted(Formatting.GRAY), false);
            }
        });
        return TypedActionResult.success(stack, false);
    }

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
        tooltip.add(Text.translatable("item.duel.transmutation_circle.tooltip1").formatted(Formatting.GRAY));
        tooltip.add(Text.translatable("item.duel.transmutation_circle.tooltip2").formatted(Formatting.DARK_GRAY));
    }
}
