package bbc.job.alchemy;

import bbc.core.Tasks;
import bbc.core.ButtonMenu;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * 현자의 돌 연성 (인체연성 의식).
 * 조건: ① 연성 에너지 stoneCost (손실·현자의 돌 할인 없음)
 *       ② 영혼 stoneSouls 개 — 대연금술사가 된 뒤 생명체를 쓰러뜨려 모은 영혼 (몹 1 · 주민 5 · 플레이어 25). 연성하면 전부 바침
 *       ③ 1인당 stoneCooldownDays 일에 1번 (포켓몬 제물 대신)
 */
public class StoneForgeMenu extends ButtonMenu {
    private static final int RANK = 0, ENERGY = 1, ITEMS = 2, SACRIFICE = 3, COOLDOWN = 4, SERVER = 5, WARN = 6, CONFIRM = 8;
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    public static final int MIN_RANK = 4;

    private final BlockPos pos;

    /** 진리를 본 자는 제물 4마리 · 대기 절반 */
    static int sacrifices(AlchemyData.Entry e) { return e.truth ? AlchemyValues.stoneSacrifices * 2 / 3 : AlchemyValues.stoneSacrifices; }
    static int souls(AlchemyData.Entry e) { return (int) e.num("souls"); }
    static int cooldownDays(AlchemyData.Entry e) { return e.truth ? Math.max(1, AlchemyValues.stoneCooldownDays / 2) : AlchemyValues.stoneCooldownDays; }

    public static void open(ServerPlayerEntity player, BlockPos pos) {
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, inv, p) -> new StoneForgeMenu(syncId, inv, player, pos),
                Text.literal("현자의 돌 연성 — 되돌릴 수 없습니다")));
    }

    private StoneForgeMenu(int syncId, PlayerInventory inv, ServerPlayerEntity player, BlockPos pos) {
        super(syncId, inv, player);
        this.pos = pos.toImmutable();
        AlchemyData data = AlchemyData.get(player.getServer());
        AlchemyData.Entry e = data.of(player.getUuid());

        boolean okR = Alch.rank(e) >= MIN_RANK;
        row(RANK, okR, "자격: 대연금술사 이상", "현재: " + Alch.rankName(e));

        boolean okE = e.energy + 1e-6 >= AlchemyValues.stoneCost;
        row(ENERGY, okE, "연성 에너지", "필요: " + Alchemy.fmt(AlchemyValues.stoneCost) + " E  (손실·할인 없음)", "보유: " + Alchemy.fmt(e.energy) + " E");

        List<String> il = new ArrayList<>();
        boolean okI = true;
        for (var en : AlchemyValues.STONE_ITEMS.entrySet()) {
            int have = Alch.count(player, en.getKey());
            boolean ok = have >= en.getValue();
            okI &= ok;
            il.add((ok ? "✔ " : "✘ ") + new ItemStack(en.getKey()).getName().getString() + " " + en.getValue() + "개  (" + have + ")");
        }
        il.add("연성할 때 소모됩니다");
        row(ITEMS, okI, "재료", il.toArray(new String[0]));

        boolean okS = souls(e) >= sacrifices(e);
        row(SACRIFICE, okS, "제물: 영혼", "영혼 " + sacrifices(e) + "개 (지금 " + souls(e) + "개)",
                "대연금술사가 된 뒤 쓰러뜨린 생명이 영혼이 됨", "몹 1 · 주민 5 · 플레이어 25", "연성하면 영혼은 전부 사라집니다");

        long left = cooldownLeft(e);
        boolean okC = left <= 0;
        row(COOLDOWN, okC, "개인 대기", cooldownDays(e) + "일에 1번", okC ? "지금 가능" : "남은 시간: " + dur(left));

        long sLeft = serverLeft(data);
        boolean okSv = sLeft <= 0;
        row(SERVER, okSv, "서버 대기", "서버 전체에서 " + AlchemyValues.stoneServerCooldownDays + "일에 1개만 연성됩니다",
                okSv ? "지금 가능" : "남은 시간: " + dur(sLeft));

        button(WARN, ButtonMenu.icon(Items.WITHER_SKELETON_SKULL, "⚠ 경고", Formatting.DARK_RED,
                "모은 영혼 " + sacrifices(e) + "개와 재료를 바쳐 돌 1개를 만듭니다",
                "생명을 재료로 쓰는 금기의 연성입니다"));

        boolean all = okR && okE && okI && okS && okC && okSv;
        button(CONFIRM, ButtonMenu.icon(all ? AlchemyItems.PHILOSOPHERS_STONE : Items.BARRIER,
                all ? "연성 시작" : "조건을 모두 채워야 합니다", all ? Formatting.DARK_RED : Formatting.GRAY,
                all ? "클릭하면 바로 연성됩니다" : "✘ 표시된 조건을 확인하세요"));
    }

    private void row(int slot, boolean ok, String title, String... lore) {
        button(slot, ButtonMenu.icon(ok ? Items.LIME_DYE : Items.GRAY_DYE, (ok ? "✔ " : "✘ ") + title, ok ? Formatting.GREEN : Formatting.RED, lore));
    }

    private static String dur(long ms) {
        return (ms / DAY_MS) + "일 " + ((ms % DAY_MS) / 3_600_000L) + "시간";
    }

    private static long serverLeft(AlchemyData d) {
        if (d.serverLastStone <= 0 || AlchemyValues.stoneServerCooldownDays <= 0) return 0;
        return d.serverLastStone + AlchemyValues.stoneServerCooldownDays * DAY_MS - System.currentTimeMillis();
    }

    private static boolean itemsReady(ServerPlayerEntity p) {
        for (var en : AlchemyValues.STONE_ITEMS.entrySet()) if (Alch.count(p, en.getKey()) < en.getValue()) return false;
        return true;
    }

    @Override
    protected boolean onPick(int slot) {
        if (slot != CONFIRM) return false;
        forge(player, pos);
        return true;
    }

    // ───────── 조건 ─────────

    private static long cooldownLeft(AlchemyData.Entry e) {
        if (e.lastStone <= 0) return 0;
        return e.lastStone + cooldownDays(e) * DAY_MS - System.currentTimeMillis();
    }

    // ───────── 의식 ─────────

    private static void forge(ServerPlayerEntity p, BlockPos pos) {
        // 창을 연 뒤 상태가 바뀌었을 수 있으니 전부 다시 확인
        AlchemyData.Entry me = Alch.entry(p);
        if (Alch.rank(me) < MIN_RANK) { fail(p, "대연금술사(등급 4) 이상만 현자의 돌을 연성할 수 있습니다."); return; }
        if (!me.truth && !AlchemyMenu.near(p, pos)) { fail(p, "연성진에서 너무 멀어졌습니다."); return; }
        if (bbc.combat.CombatTag.tagged(p)) { fail(p, "전투 중에는 연성할 수 없습니다."); return; }
        AlchemyData data = AlchemyData.get(p.getServer());
        AlchemyData.Entry e = data.of(p.getUuid());
        if (e.energy + 1e-6 < AlchemyValues.stoneCost) { fail(p, "연성 에너지가 부족합니다."); return; }
        if (cooldownLeft(e) > 0) { fail(p, "아직 다시 연성할 수 없습니다."); return; }
        if (serverLeft(data) > 0) { fail(p, "서버 전체 대기 중입니다. 남은 시간: " + dur(serverLeft(data))); return; }
        if (!itemsReady(p)) { fail(p, "재료가 부족합니다."); return; }
        if (souls(e) < sacrifices(e)) { fail(p, "영혼이 부족합니다 (" + souls(e) + " / " + sacrifices(e) + ")."); return; }
        int victims = souls(e);
        e.num("souls", 0);

        for (var en : AlchemyValues.STONE_ITEMS.entrySet()) Alch.take(p, en.getKey(), en.getValue());
        e.energy = Math.max(0, e.energy - AlchemyValues.stoneCost);
        e.lastStone = System.currentTimeMillis();
        data.serverLastStone = e.lastStone;
        data.markDirty();
        p.getInventory().offerOrDrop(new ItemStack(AlchemyItems.PHILOSOPHERS_STONE));

        // 연출: 붉은 스파크 → 바닥 연성진 → 번개
        ServerWorld w = p.getServerWorld();
        Alchemy.bigFx(p);
        StoneFx.circleAt(w, Vec3d.ofBottomCenter(pos), 60);
        w.playSound(null, pos, SoundEvents.ENTITY_WITHER_SPAWN, SoundCategory.PLAYERS, 0.7f, 0.6f);
        Alchemy.fx(w, pos, true);

        p.sendMessage(Text.literal("☠ 영혼 " + victims + "개 — 생명이 현자의 돌이 되었다...").formatted(Formatting.DARK_RED), false);
        p.getServer().getPlayerManager().broadcast(
                Text.literal("✦ " + p.getName().getString() + "님이 " + victims + "개의 영혼을 바쳐 현자의 돌을 연성했습니다.")
                        .formatted(Formatting.RED, Formatting.BOLD), false);
    }

    private static void fail(ServerPlayerEntity p, String msg) {
        p.sendMessage(Text.literal(msg).formatted(Formatting.RED), false);
    }
}
