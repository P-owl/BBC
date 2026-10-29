package bus.muhyeon.duel;

import com.cobblemon.mod.common.battles.BattleBuilder;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.battles.BattleStartResult;
import com.cobblemon.mod.common.battles.ErroredBattleStart;
import com.cobblemon.mod.common.battles.SuccessfulBattleStart;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
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
 *  - 허공 우클릭: 효과 선택창
 *  - 플레이어 우클릭: 리스크 없는 결투 (시작될 때 돌 소모)
 *  - 자기 연금술사 피카츄 우클릭: 현자 폼으로 영구 전환
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
        if (user.getWorld().isClient()) {
            return (entity instanceof PlayerEntity || entity instanceof PokemonEntity) ? ActionResult.SUCCESS : ActionResult.PASS;
        }
        if (!(user instanceof ServerPlayerEntity caster)) return ActionResult.PASS;

        if (entity instanceof PokemonEntity pe) return alchemistPikachu(caster, pe);
        if (!(entity instanceof ServerPlayerEntity target)) return ActionResult.PASS;

        if (target.isSpectator() || target.isCreative()) {
            caster.sendMessage(Text.literal("관전/크리에이티브 상태인 플레이어에게는 쓸 수 없습니다.").formatted(Formatting.RED), true);
            return ActionResult.FAIL;
        }
        if (BattleRegistry.getBattleByParticipatingPlayer(caster) != null) {
            caster.sendMessage(Text.literal("이미 배틀 중입니다.").formatted(Formatting.RED), true);
            return ActionResult.FAIL;
        }
        if (BattleRegistry.getBattleByParticipatingPlayer(target) != null) {
            caster.sendMessage(Text.literal(target.getName().getString() + "님은 이미 배틀 중입니다.").formatted(Formatting.RED), true);
            return ActionResult.FAIL;
        }

        BattleStartResult result = BattleBuilder.INSTANCE.pvp1v1(caster, target);
        if (result instanceof SuccessfulBattleStart ok) {
            DuelRewards.markStone(ok.getBattle(), caster);
            StoneFx.big(caster);
            if (!caster.getAbilities().creativeMode) stack.decrement(1);

            caster.getWorld().playSound(null, target.getBlockPos(),
                    SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 1.0f, 0.6f);
            target.sendMessage(Text.literal("✦ " + caster.getName().getString()
                    + "님이 현자의 돌로 결투를 강제했습니다! 지면 아이템이나 포켓몬을 빼앗길 수 있습니다.")
                    .formatted(Formatting.DARK_PURPLE, Formatting.BOLD), false);
            caster.sendMessage(Text.literal("✦ 리스크 없는 결투 시작. 져도 잃는 것이 없습니다.")
                    .formatted(Formatting.LIGHT_PURPLE), false);
            return ActionResult.SUCCESS;
        }
        if (result instanceof ErroredBattleStart err) err.sendTo(caster, t -> t);
        return ActionResult.FAIL;
    }

    private ActionResult alchemistPikachu(ServerPlayerEntity caster, PokemonEntity pe) {
        var mon = pe.getPokemon();
        if (!caster.getUuid().equals(mon.getOwnerUUID())) return ActionResult.PASS;
        if (BattleRegistry.getBattleByParticipatingPlayer(caster) != null) {
            caster.sendMessage(Text.literal("배틀 중에는 쓸 수 없습니다.").formatted(Formatting.RED), true);
            return ActionResult.FAIL;
        }
        if (AlchemistForm.isSage(mon)) {
            caster.sendMessage(Text.literal("이미 현자의 돌과 하나가 되었습니다.").formatted(Formatting.GRAY), true);
            return ActionResult.FAIL;
        }
        if (!AlchemistForm.isAlchemist(mon)) {
            caster.sendMessage(Text.literal("현자의 돌에 반응하지 않습니다. (연금술사 피카츄만 가능)").formatted(Formatting.GRAY), true);
            return ActionResult.FAIL;
        }
        if (!consumeOne(caster)) return ActionResult.FAIL;

        AlchemistForm.toSage(mon);
        StoneFx.big(caster);
        caster.sendMessage(Text.literal("✦ " + mon.getSpecies().getTranslatedName().getString()
                + "이(가) 현자의 돌과 하나가 되었다! 이제 원래 모습으로 돌아갈 수 없다.")
                .formatted(Formatting.DARK_RED, Formatting.BOLD), false);
        return ActionResult.SUCCESS;
    }

    /** 손 → 인벤토리 순으로 현자의 돌 1개 소모. 크리에이티브는 소모 없음. */
    public static boolean consumeOne(ServerPlayerEntity player) {
        if (player.getAbilities().creativeMode) return true;
        for (Hand h : Hand.values()) {
            ItemStack s = player.getStackInHand(h);
            if (s.isOf(DuelMod.PHILOSOPHERS_STONE)) { s.decrement(1); return true; }
        }
        var inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.isOf(DuelMod.PHILOSOPHERS_STONE)) { s.decrement(1); return true; }
        }
        return false;
    }

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
        tooltip.add(Text.translatable("item.duel.philosophers_stone.tooltip1").formatted(Formatting.GRAY));
        tooltip.add(Text.translatable("item.duel.philosophers_stone.tooltip2").formatted(Formatting.GRAY));
    }
}
