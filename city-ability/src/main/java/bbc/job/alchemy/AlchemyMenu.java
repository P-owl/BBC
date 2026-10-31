package bbc.job.alchemy;

import bbc.core.Tasks;
import bbc.core.ButtonMenu;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 연성진 화면 (상자 6줄, 서버 전용).
 * 위 5줄 = 이해한 아이템 목록 (클릭하면 연성)
 * 아래 1줄 = 버튼
 * 내 인벤토리에서 Shift+클릭 = 그 묶음을 분해
 */
public class AlchemyMenu extends GenericContainerScreenHandler {
    private static final int PER_PAGE = 45;
    private static final int PREV = 45, STONE = 46, BOX = 47, JOB = 48, INFO = 49, FILTER = 51, SEARCH = 52, NEXT = 53;
    private static final String[] FILTER_NAMES = {"전체", "바닐라", "코블몬"};

    /** /alchemy search 가 어느 연성진을 다시 열지 기억 */
    static final Map<UUID, BlockPos> LAST_CIRCLE = new HashMap<>();

    private final ServerPlayerEntity player;
    private final BlockPos pos;
    private final String search;
    /** 진리를 본 자가 연성진 없이 연 화면 */
    private final boolean portable;
    private int page = 0;
    private int filter = 0;
    private List<Item> list = List.of();

    public static void open(ServerPlayerEntity player, BlockPos pos, String search) {
        LAST_CIRCLE.put(player.getUuid(), pos.toImmutable());
        openAt(player, pos, search, false);
    }

    /** 진리를 본 자: 연성진 없이 그 자리에서 */
    public static void openPortable(ServerPlayerEntity player) { openPortable(player, null); }

    public static void openPortable(ServerPlayerEntity player, String search) {
        Alchemy.fx(player.getServerWorld(), player.getBlockPos(), true);
        player.getServerWorld().playSound(null, player.getBlockPos(), net.minecraft.sound.SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP,
                net.minecraft.sound.SoundCategory.PLAYERS, 0.6f, 1.6f); // 손뼉
        openAt(player, player.getBlockPos(), search, true);
    }

    private static void openAt(ServerPlayerEntity player, BlockPos pos, String search, boolean portable) {
        String base = portable ? "손뼉 연성" : "연성진 — 등가교환";
        String title = search == null || search.isBlank() ? base : base + " — 검색: " + search;
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, inv, p) -> new AlchemyMenu(syncId, inv, player, pos, search, portable),
                Text.literal(title)));
    }

    private AlchemyMenu(int syncId, PlayerInventory inv, ServerPlayerEntity player, BlockPos pos, String search, boolean portable) {
        super(ScreenHandlerType.GENERIC_9X6, syncId, inv, new SimpleInventory(54), 6);
        this.player = player;
        this.portable = portable;
        this.pos = pos.toImmutable();
        this.search = search == null ? "" : search.toLowerCase(Locale.ROOT).trim();
        render();
    }

    // ───────── 그리기 ─────────

    private void rebuildList() {
        AlchemyData.Entry e = AlchemyData.get(player.getServer()).of(player.getUuid());
        List<Item> l = new ArrayList<>();
        List<Item> source = new ArrayList<>(AlchemyValues.knownItems(e));
        for (Item item : source) {
            String id = AlchemyValues.id(item);
            if (!AlchemyValues.canOutput(item)) continue;
            if (filter == 1 && !id.startsWith("minecraft:")) continue;
            if (filter == 2 && id.startsWith("minecraft:")) continue;
            if (!search.isEmpty()) {
                String name = new ItemStack(item).getName().getString().toLowerCase(Locale.ROOT);
                if (!id.contains(search) && !name.contains(search)) continue;
            }
            l.add(item);
        }
        l.sort(Comparator.comparingDouble(AlchemyValues::valueOf).thenComparing(AlchemyValues::id));
        list = l;
        int pages = Math.max(1, (list.size() + PER_PAGE - 1) / PER_PAGE);
        if (page >= pages) page = pages - 1;
        if (page < 0) page = 0;
    }

    private void render() {
        rebuildList();
        AlchemyData.Entry e = AlchemyData.get(player.getServer()).of(player.getUuid());
        boolean stone = Alchemy.hasStone(player);
        double mult = Alchemy.multiplier(player);
        int lossPct = (int) Math.round((mult - 1) * 100);

        for (int i = 0; i < PER_PAGE; i++) {
            int idx = page * PER_PAGE + i;
            if (idx >= list.size()) { getInventory().setStack(i, ItemStack.EMPTY); continue; }
            Item item = list.get(idx);
            double v = AlchemyValues.valueOf(item);
            double unit = v * mult;
            int can = (int) Math.floor((e.energy + 1e-6) / unit);
            ItemStack s = new ItemStack(item);
            List<Text> lore = new ArrayList<>();
            lore.add(line("가치: " + Alchemy.fmt(v) + " E", Formatting.GRAY));
            lore.add(line("연성 비용: " + Alchemy.fmt(unit) + " E  (손실 " + lossPct + "%)", Formatting.AQUA));
            lore.add(line(can > 0 ? "지금 " + Alchemy.fmt(can) + "개 연성 가능" : "에너지 부족", can > 0 ? Formatting.GREEN : Formatting.RED));
            lore.add(line("좌클릭 1개 · 우클릭 " + Math.min(16, s.getMaxCount()) + "개 · Shift " + s.getMaxCount() + "개", Formatting.DARK_GRAY));
            s.set(DataComponentTypes.LORE, new LoreComponent(lore));
            getInventory().setStack(i, s);
        }

        for (int i = 45; i < 54; i++) getInventory().setStack(i, ButtonMenu.icon(Items.BLACK_STAINED_GLASS_PANE, " ", Formatting.GRAY));
        int pages = Math.max(1, (list.size() + PER_PAGE - 1) / PER_PAGE);
        if (page > 0) getInventory().setStack(PREV, ButtonMenu.icon(Items.ARROW, "◀ 이전 페이지", Formatting.WHITE, (page) + " / " + pages));
        if (page < pages - 1) getInventory().setStack(NEXT, ButtonMenu.icon(Items.ARROW, "다음 페이지 ▶", Formatting.WHITE, (page + 2) + " / " + pages));
        getInventory().setStack(STONE, ButtonMenu.icon(AlchemyItems.PHILOSOPHERS_STONE, "현자의 돌 연성", Formatting.DARK_RED,
                "대연금술사 이상 · 에너지 " + Alchemy.fmt(AlchemyValues.stoneCost) + " E + 희귀 재료",
                "+ 영혼 " + AlchemyValues.stoneSacrifices + "개를 제물로 (대연금술사가 된 뒤 쓰러뜨린 생명)",
                "개인 " + AlchemyValues.stoneCooldownDays + "일 · 서버 " + AlchemyValues.stoneServerCooldownDays + "일에 1개 · 클릭해서 조건 확인"));
        getInventory().setStack(JOB, Alch.isAlchemist(e) && Alch.rank(e) == 4
                ? ButtonMenu.icon(Items.ENDER_EYE, "진리의 문 열기", Formatting.DARK_PURPLE,
                        "대연금술사만 · 이 연성진 근처에서", "대가: 연성 에너지 " + Alchemy.fmt(Alch.GATE_ENERGY) + " E + 경험치 " + Alch.GATE_LEVELS + "레벨",
                        "+ 팔·다리·심장 중 하나 (영구)", "클릭해서 통행료 고르기")
                : Alch.isAlchemist(e)
                ? (Alch.rank(e) == 4
                        ? ButtonMenu.icon(Items.ENDER_EYE, "진리의 문 열기", Formatting.DARK_PURPLE,
                                "대연금술사만 · 연성 에너지 " + Alchemy.fmt(Alch.GATE_ENERGY) + " E + 경험치 " + Alch.GATE_LEVELS + "레벨",
                                "+ 팔·다리·심장 중 하나를 통행료로 (영구)", "클릭해서 통행료 고르기")
                        : ButtonMenu.icon(Items.BREWING_STAND, "직업: " + Alch.rankName(e), Formatting.GOLD,
                                "숙련도 " + Alchemy.fmt(e.xp) + " E (연성에 쓴 에너지)", "다음 등급은 /alchemy 로 확인"))
                : ButtonMenu.icon(Items.BREWING_STAND, "연금술사 되기", Formatting.GOLD,
                        "조건: 이해한 아이템 " + Alch.JOIN_KNOWN + "종 (지금 " + e.known.size() + ")",
                        "누적 분해 " + Alchemy.fmt(Alch.JOIN_DEPOSITED) + " E (지금 " + Alchemy.fmt(e.depositedTotal) + ")",
                        "공물: 다이아몬드 블록 1 · 금 블록 4 · 레드스톤 블록 8 · 자수정 조각 16", "클릭해서 입문"));
        getInventory().setStack(BOX, ButtonMenu.icon(Items.HOPPER, "분해 상자 열기", Formatting.GOLD,
                "아이템을 잔뜩 넣고 창을 닫으면 전부 분해", "분해한 아이템은 '이해'되어 연성 목록에 추가",
                "분해할 수 없는 아이템은 돌려받음"));
        String cap = AlchemyValues.dailyCap > 0
                ? "오늘 분해: " + Alchemy.fmt(e.gainedToday(Alch.dailyCap(e))) + " / " + Alchemy.fmt(Alch.dailyCap(e)) + " E"
                : "하루 분해 한도 없음";
        getInventory().setStack(INFO, ButtonMenu.icon(Items.NETHER_STAR, "연성 에너지: " + Alchemy.fmt(e.energy) + " E", Formatting.LIGHT_PURPLE,
                cap,
                "연성 가능: " + AlchemyValues.knownItems(e).size() + "종 (직접 분해 " + AlchemyValues.depositedKinds(e) + "종)",
                Alch.rankName(e) + "  ·  연성 손실 " + lossPct + "%" + (stone && Alch.rank(e) < 5 ? " (현자의 돌)" : ""),
                "내 인벤토리 Shift+클릭 = 그 묶음 바로 분해"));
        getInventory().setStack(FILTER, ButtonMenu.icon(Items.HOPPER_MINECART, "분류: " + FILTER_NAMES[filter], Formatting.YELLOW, "클릭해서 전체 / 바닐라 / 코블몬 전환"));
        getInventory().setStack(SEARCH, ButtonMenu.icon(Items.SPYGLASS, search.isEmpty() ? "검색" : "검색: " + search, Formatting.YELLOW,
                "채팅에  /alchemy search <단어>", "영어 이름이나 ID로 검색 (예: diamond, candy)",
                search.isEmpty() ? "" : "클릭하면 검색 해제"));
        sendContentUpdates();
    }

    private static Text line(String s, Formatting f) {
        return Text.literal(s).formatted(f).styled(st -> st.withItalic(false));
    }

    // ───────── 클릭 ─────────

    @Override
    public void onSlotClick(int slot, int button, SlotActionType action, PlayerEntity p) {
        try {
            if (slot >= 0 && slot < PER_PAGE) clickItem(slot, button, action);
            else if (slot >= 45 && slot < 54 && action == SlotActionType.PICKUP) clickButton(slot);
            else if (slot >= 54 && slot < slots.size() && action == SlotActionType.QUICK_MOVE) depositSlot(slots.get(slot));
        } catch (Exception ex) {
            bbc.Bbc.LOG.error("[alchemy] click failed", ex);
        }
        // 클라이언트가 예측해서 옮긴 아이템을 서버 상태로 되돌림
        syncState();
    }

    private void clickItem(int slot, int button, SlotActionType action) {
        int idx = page * PER_PAGE + slot;
        if (idx >= list.size()) return;
        Item item = list.get(idx);
        int max = new ItemStack(item).getMaxCount();
        int want;
        if (action == SlotActionType.QUICK_MOVE) want = max;
        else if (action == SlotActionType.PICKUP) want = button == 1 ? Math.min(16, max) : 1;
        else return;
        boolean stone = Alchemy.hasStone(player);
        Alchemy.Result r = Alchemy.transmute(player, item, want);
        if (r.problem() != null) {
            player.sendMessage(Text.literal(r.problem()).formatted(Formatting.RED), true);
        } else {
            Alchemy.fx(player.getServerWorld(), portable ? player.getBlockPos() : pos, stone || portable);
            player.sendMessage(Text.literal("✦ " + new ItemStack(item).getName().getString() + " ×" + r.count()
                    + " 연성  (-" + Alchemy.fmt(r.energy()) + " E)").formatted(Formatting.AQUA), true);
        }
        render();
    }

    private void clickButton(int slot) {
        switch (slot) {
            case PREV -> { page--; render(); }
            case NEXT -> { page++; render(); }
            case FILTER -> { filter = (filter + 1) % FILTER_NAMES.length; page = 0; render(); }
            case SEARCH -> {
                if (!search.isEmpty()) {
                    Tasks.later(1, () -> { if (!player.isDisconnected()) { if (portable) openPortable(player); else open(player, pos, null); } });
                } else {
                    player.sendMessage(Text.literal("채팅에  /alchemy search <단어>  를 입력하세요. 예) /alchemy search candy").formatted(Formatting.YELLOW), false);
                    player.closeHandledScreen();
                }
            }
            case JOB -> Tasks.later(1, () -> {
                if (player.isDisconnected()) return;
                AlchemyData.Entry je = Alch.entry(player);
                if (Alch.isAlchemist(je) && Alch.rank(je) == 4) { GateMenu.open(player); return; }   // 진리의 문
                player.closeHandledScreen();
                AlchemistJob.tryJoin(player);
            });
            case STONE -> Tasks.later(1, () -> { if (!player.isDisconnected()) StoneForgeMenu.open(player, pos); });
            case BOX -> Tasks.later(1, () -> { if (!player.isDisconnected()) AlchemyDepositMenu.open(player, pos); });
            default -> { }
        }
    }

    private void depositSlot(Slot s) {
        ItemStack st = s.getStack();
        if (st.isEmpty()) return;
        String name = st.getName().getString();
        Alchemy.Result r = Alchemy.deposit(player, st);
        if (r.problem() != null) {
            player.sendMessage(Text.literal(name + ": " + r.problem()).formatted(Formatting.RED), true);
            return;
        }
        s.markDirty();
        Alchemy.depositFx(player.getServerWorld(), portable ? player.getBlockPos() : pos);
        player.sendMessage(Text.literal("✦ " + name + " ×" + r.count() + " 분해  (+" + Alchemy.fmt(r.energy()) + " E)").formatted(Formatting.GOLD), true);
        render();
    }

    @Override
    public ItemStack quickMove(PlayerEntity p, int slot) { return ItemStack.EMPTY; }

    @Override
    public boolean canUse(PlayerEntity p) {
        if (portable) return true;
        return p.getWorld().getBlockState(pos).isOf(AlchemyItems.ALCHEMY_CIRCLE)
                && p.squaredDistanceTo(Vec3d.ofCenter(pos)) <= 64;
    }

    static boolean near(ServerPlayerEntity p, BlockPos pos) {
        ServerWorld w = p.getServerWorld();
        return pos != null && w.getBlockState(pos).isOf(AlchemyItems.ALCHEMY_CIRCLE) && p.squaredDistanceTo(Vec3d.ofCenter(pos)) <= 64;
    }
}
