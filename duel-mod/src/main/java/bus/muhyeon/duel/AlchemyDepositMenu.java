package bus.muhyeon.duel;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

/** 분해 상자: 일반 상자처럼 아이템을 넣고, 창을 닫으면 전부 분해. 분해 못 한 건 돌려줌 */
public class AlchemyDepositMenu extends GenericContainerScreenHandler {
    private final ServerPlayerEntity player;
    private final BlockPos pos;
    private final SimpleInventory box;
    private boolean done = false;

    public static void open(ServerPlayerEntity player, BlockPos pos) {
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, inv, p) -> new AlchemyDepositMenu(syncId, inv, player, pos, new SimpleInventory(27)),
                Text.literal("분해 상자 — 넣고 닫으면 분해")));
    }

    private AlchemyDepositMenu(int syncId, PlayerInventory inv, ServerPlayerEntity player, BlockPos pos, SimpleInventory box) {
        super(ScreenHandlerType.GENERIC_9X3, syncId, inv, box, 3);
        this.player = player;
        this.pos = pos.toImmutable();
        this.box = box;
    }

    @Override
    public void onClosed(PlayerEntity p) {
        super.onClosed(p);
        if (done) return;
        done = true;

        int kinds = 0, items = 0, returned = 0;
        double total = 0;
        String lastProblem = null;
        for (int i = 0; i < box.size(); i++) {
            ItemStack st = box.getStack(i);
            if (st.isEmpty()) continue;
            Alchemy.Result r = Alchemy.deposit(player, st);
            if (r.count() > 0) { kinds++; items += r.count(); total += r.energy(); }
            if (r.problem() != null) lastProblem = st.getName().getString() + ": " + r.problem();
            if (!st.isEmpty()) {
                returned += st.getCount();
                player.getInventory().offerOrDrop(st.copy());
            }
            box.setStack(i, ItemStack.EMPTY);
        }

        if (items > 0) {
            Alchemy.depositFx(player.getServerWorld(), pos);
            player.sendMessage(Text.literal("✦ " + kinds + "종 " + items + "개 분해  (+" + Alchemy.fmt(total) + " E)").formatted(Formatting.GOLD), false);
        }
        if (returned > 0) {
            player.sendMessage(Text.literal(returned + "개는 분해하지 못해 돌려받았습니다" + (lastProblem != null ? " — " + lastProblem : "")).formatted(Formatting.RED), false);
        }
        // 접속 중이고 연성진 근처면 연성 화면으로 돌아감
        Tasks.later(1, () -> {
            if (player.isDisconnected()) return;
            if (AlchemyMenu.near(player, pos)) AlchemyMenu.open(player, pos, null);
            else if (Jobs.entry(player).truth) AlchemyMenu.openPortable(player);
        });
    }
}
