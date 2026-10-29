package bus.muhyeon.duel;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/**
 * 상자 한 줄(9칸)짜리 버튼 메뉴. 아이콘을 클릭하면 onPick 이 호출된다.
 * 서버에서만 동작하므로 클라이언트 코드가 필요 없다.
 */
public abstract class ButtonMenu extends GenericContainerScreenHandler {
    protected final ServerPlayerEntity player;
    private boolean picked = false;

    private final int size;

    protected ButtonMenu(int syncId, PlayerInventory playerInv, ServerPlayerEntity player) { this(syncId, playerInv, player, 1); }

    /** rows = 1~6 줄 */
    protected ButtonMenu(int syncId, PlayerInventory playerInv, ServerPlayerEntity player, int rows) {
        super(type(rows), syncId, playerInv, new SimpleInventory(rows * 9), rows);
        this.player = player;
        this.size = rows * 9;
        for (int i = 0; i < size; i++) getInventory().setStack(i, icon(Items.GRAY_STAINED_GLASS_PANE, " ", Formatting.GRAY));
    }

    private static ScreenHandlerType<GenericContainerScreenHandler> type(int rows) {
        return switch (rows) {
            case 2 -> ScreenHandlerType.GENERIC_9X2;
            case 3 -> ScreenHandlerType.GENERIC_9X3;
            case 4 -> ScreenHandlerType.GENERIC_9X4;
            case 5 -> ScreenHandlerType.GENERIC_9X5;
            case 6 -> ScreenHandlerType.GENERIC_9X6;
            default -> ScreenHandlerType.GENERIC_9X1;
        };
    }

    /** 다시 그리기 (창을 닫지 않고 내용만 갱신) */
    protected void refresh() { syncState(); }

    protected void button(int slot, ItemStack icon) { getInventory().setStack(slot, icon); }

    /** 클릭된 칸 번호. true 를 돌려주면 선택 완료로 보고 창을 닫는다. */
    protected abstract boolean onPick(int slot);

    /** 아무것도 고르지 않고 닫았을 때 */
    protected void onCancel() {}

    @Override
    public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity p) {
        // 아이템을 집거나 옮기는 동작은 전부 막고, 메뉴 칸 클릭만 처리
        if (!picked && slotIndex >= 0 && slotIndex < size && actionType == SlotActionType.PICKUP) {
            if (onPick(slotIndex)) {
                picked = true;
                player.closeHandledScreen();
                return;
            }
        }
        syncState();
    }

    @Override
    public ItemStack quickMove(PlayerEntity p, int slot) { return ItemStack.EMPTY; }

    @Override
    public void onClosed(PlayerEntity p) {
        super.onClosed(p);
        if (!picked) {
            picked = true;
            onCancel();
        }
    }

    public static ItemStack icon(Item item, String name, Formatting color, String... lore) {
        return icon(new ItemStack(item), Text.literal(name), color, lore);
    }

    /** 이미 만든 아이템(포켓몬 아이콘 등)에 이름·설명을 붙임 */
    public static ItemStack icon(ItemStack base, Text name, Formatting color, String... lore) {
        ItemStack s = base.copy();
        s.set(DataComponentTypes.CUSTOM_NAME, name.copy().formatted(color, Formatting.BOLD).styled(st -> st.withItalic(false)));
        List<Text> lines = new ArrayList<>();
        for (String l : lore) lines.add(Text.literal(l).formatted(Formatting.GRAY).styled(st -> st.withItalic(false)));
        s.set(DataComponentTypes.LORE, new LoreComponent(lines));
        return s;
    }
}
