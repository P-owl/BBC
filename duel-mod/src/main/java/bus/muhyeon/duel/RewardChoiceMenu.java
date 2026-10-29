package bus.muhyeon.duel;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.UUID;

/** 현자의 돌 결투 승리 후: 아이템 강탈 / 포켓몬 강탈 선택. 그냥 닫으면 아이템 강탈. */
public class RewardChoiceMenu extends ButtonMenu {
    private static final int ITEM = 3, POKEMON = 5;
    private final UUID loserId;

    public static void open(ServerPlayerEntity winner, UUID loserId) {
        winner.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, inv, p) -> new RewardChoiceMenu(syncId, inv, winner, loserId),
                Text.literal("결투 승리 — 무엇을 빼앗을까요?")));
    }

    private RewardChoiceMenu(int syncId, PlayerInventory inv, ServerPlayerEntity winner, UUID loserId) {
        super(syncId, inv, winner);
        this.loserId = loserId;
        button(ITEM, icon(Items.CHEST, "아이템 강탈", Formatting.GOLD, "상대 인벤토리에서 랜덤 한 칸"));
        button(POKEMON, icon(StoneMenu.cobblemon("poke_ball"), "포켓몬 강탈", Formatting.RED, "상대 파티에서 랜덤 1마리"));
    }

    @Override
    protected boolean onPick(int slot) {
        if (slot != ITEM && slot != POKEMON) return false;
        ServerPlayerEntity loser = DuelRewards.online(player.getServer(), loserId);
        if (loser == null) {
            player.sendMessage(Text.literal("상대가 접속을 종료해 보상을 받을 수 없습니다.").formatted(Formatting.GRAY), false);
            return true;
        }
        if (slot == ITEM) DuelRewards.stealItem(player, loser);
        else DuelRewards.stealPokemon(player, loser);
        return true;
    }

    @Override
    protected void onCancel() {
        ServerPlayerEntity loser = DuelRewards.online(player.getServer(), loserId);
        if (loser == null) return;
        player.sendMessage(Text.literal("선택하지 않아 아이템 강탈로 처리합니다.").formatted(Formatting.GRAY), false);
        DuelRewards.stealItem(player, loser);
    }
}
