package bus.muhyeon.duel;

import com.cobblemon.mod.common.battles.BattleBuilder;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.battles.BattleStartResult;
import com.cobblemon.mod.common.battles.ErroredBattleStart;
import com.cobblemon.mod.common.battles.SuccessfulBattleStart;
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

import java.util.List;

/**
 * 결투 장갑: 다른 플레이어를 우클릭하면 수락 절차 없이 바로 1:1 배틀이 시작된다.
 * 성공했을 때만 1개 소모 + 10초 쿨다운.
 */
public class DuelGloveItem extends Item {
    private static final int COOLDOWN_TICKS = 200;

    public DuelGloveItem(Settings settings) {
        super(settings);
    }

    @Override
    public ActionResult useOnEntity(ItemStack stack, PlayerEntity user, LivingEntity entity, Hand hand) {
        if (!(entity instanceof PlayerEntity)) return ActionResult.PASS;
        if (user.getWorld().isClient()) return ActionResult.SUCCESS;   // 손 휘두르기만

        if (!(user instanceof ServerPlayerEntity attacker) || !(entity instanceof ServerPlayerEntity target)) {
            return ActionResult.PASS;
        }
        if (target.isSpectator() || target.isCreative()) {
            attacker.sendMessage(Text.literal("관전/크리에이티브 상태인 플레이어에게는 쓸 수 없습니다.").formatted(Formatting.RED), true);
            return ActionResult.FAIL;
        }
        if (BattleRegistry.getBattleByParticipatingPlayer(attacker) != null) {
            attacker.sendMessage(Text.literal("이미 배틀 중입니다.").formatted(Formatting.RED), true);
            return ActionResult.FAIL;
        }
        if (BattleRegistry.getBattleByParticipatingPlayer(target) != null) {
            attacker.sendMessage(Text.literal(target.getName().getString() + "님은 이미 배틀 중입니다.").formatted(Formatting.RED), true);
            return ActionResult.FAIL;
        }

        BattleStartResult result = BattleBuilder.INSTANCE.pvp1v1(attacker, target);

        if (result instanceof SuccessfulBattleStart ok) {
            DuelRewards.markGlove(ok.getBattle(), attacker);
            if (!attacker.getAbilities().creativeMode) stack.decrement(1);
            attacker.getItemCooldownManager().set(this, COOLDOWN_TICKS);

            attacker.getWorld().playSound(null, target.getBlockPos(),
                    SoundEvents.ENTITY_EVOKER_PREPARE_SUMMON, SoundCategory.PLAYERS, 1.0f, 0.8f);

            target.sendMessage(Text.literal("⚔ " + attacker.getName().getString() + "님이 결투 장갑을 던졌습니다! 피할 수 없습니다. 지면 아이템 하나를 빼앗깁니다.")
                    .formatted(Formatting.DARK_RED, Formatting.BOLD), false);
            attacker.sendMessage(Text.literal("⚔ " + target.getName().getString() + "님에게 결투를 강제로 걸었습니다.")
                    .formatted(Formatting.GOLD), false);
            return ActionResult.SUCCESS;
        }

        if (result instanceof ErroredBattleStart err) {
            // 포켓몬 없음 / 전부 기절 등 코블몬 기본 오류 메시지를 사용자에게 전달
            err.sendTo(attacker, t -> t);
        }
        return ActionResult.FAIL;
    }

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
        tooltip.add(Text.translatable("item.duel.duel_glove.tooltip").formatted(Formatting.GRAY));
    }
}
