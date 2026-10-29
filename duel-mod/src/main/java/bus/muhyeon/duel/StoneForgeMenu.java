package bus.muhyeon.duel;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.pokemon.Pokemon;
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
 *       ② 파티 stoneSacrifices 마리가 전부 Lv.stoneMinLevel 이상 → 전부 제물로 소멸 (지닌 도구만 돌려받음)
 *       ③ 1인당 stoneCooldownDays 일에 1번
 */
public class StoneForgeMenu extends ButtonMenu {
    private static final int RANK = 0, ENERGY = 1, ITEMS = 2, SACRIFICE = 3, COOLDOWN = 4, SERVER = 5, WARN = 6, CONFIRM = 8;
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    public static final int MIN_RANK = 4;

    private final BlockPos pos;

    /** 진리를 본 자는 제물 4마리 · 대기 절반 */
    static int sacrifices(AlchemyData.Entry e) { return e.truth ? Math.min(4, AlchemyValues.stoneSacrifices) : AlchemyValues.stoneSacrifices; }
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

        boolean okR = Jobs.rank(e) >= MIN_RANK;
        row(RANK, okR, "자격: 대연금술사 이상", "현재: " + Jobs.rankName(e));

        boolean okE = e.energy + 1e-6 >= AlchemyValues.stoneCost;
        row(ENERGY, okE, "연성 에너지", "필요: " + Alchemy.fmt(AlchemyValues.stoneCost) + " E  (손실·할인 없음)", "보유: " + Alchemy.fmt(e.energy) + " E");

        List<String> il = new ArrayList<>();
        boolean okI = true;
        for (var en : AlchemyValues.STONE_ITEMS.entrySet()) {
            int have = Jobs.count(player, en.getKey());
            boolean ok = have >= en.getValue();
            okI &= ok;
            il.add((ok ? "✔ " : "✘ ") + new ItemStack(en.getKey()).getName().getString() + " " + en.getValue() + "개  (" + have + ")");
        }
        il.add("연성할 때 소모됩니다");
        row(ITEMS, okI, "재료", il.toArray(new String[0]));

        List<Pokemon> party = party(player);
        int ok = 0;
        for (Pokemon p : party) if (p.getLevel() >= AlchemyValues.stoneMinLevel) ok++;
        boolean okS = sacrificeReady(player);
        row(SACRIFICE, okS, "제물", "파티 " + sacrifices(e) + "마리 전부 Lv." + AlchemyValues.stoneMinLevel + " 이상",
                "현재: 파티 " + party.size() + "마리 중 " + ok + "마리 충족", "제물이 된 포켓몬은 영원히 사라집니다");

        long left = cooldownLeft(e);
        boolean okC = left <= 0;
        row(COOLDOWN, okC, "개인 대기", cooldownDays(e) + "일에 1번", okC ? "지금 가능" : "남은 시간: " + dur(left));

        long sLeft = serverLeft(data);
        boolean okSv = sLeft <= 0;
        row(SERVER, okSv, "서버 대기", "서버 전체에서 " + AlchemyValues.stoneServerCooldownDays + "일에 1개만 연성됩니다",
                okSv ? "지금 가능" : "남은 시간: " + dur(sLeft));

        button(WARN, ButtonMenu.icon(Items.WITHER_SKELETON_SKULL, "⚠ 경고", Formatting.DARK_RED,
                "파티 포켓몬 " + sacrifices(e) + "마리와 재료를 바쳐 돌 1개를 만듭니다",
                "지닌 도구만 돌려받고, 포켓몬은 되돌릴 수 없습니다",
                "이로치·전설도 예외 없음"));

        boolean all = okR && okE && okI && okS && okC && okSv;
        button(CONFIRM, ButtonMenu.icon(all ? DuelMod.PHILOSOPHERS_STONE : Items.BARRIER,
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
        for (var en : AlchemyValues.STONE_ITEMS.entrySet()) if (Jobs.count(p, en.getKey()) < en.getValue()) return false;
        return true;
    }

    @Override
    protected boolean onPick(int slot) {
        if (slot != CONFIRM) return false;
        forge(player, pos);
        return true;
    }

    // ───────── 조건 ─────────

    private static List<Pokemon> party(ServerPlayerEntity p) {
        List<Pokemon> l = new ArrayList<>();
        for (Pokemon mon : Cobblemon.INSTANCE.getStorage().getParty(p).toGappyList()) if (mon != null) l.add(mon);
        return l;
    }

    private static boolean sacrificeReady(ServerPlayerEntity p) {
        AlchemyData.Entry e = Jobs.entry(p);
        List<Pokemon> party = party(p);
        if (party.size() < sacrifices(e)) return false;
        int ok = 0;
        for (Pokemon mon : party) if (mon.getLevel() >= AlchemyValues.stoneMinLevel) ok++;
        return ok >= sacrifices(e);
    }

    private static long cooldownLeft(AlchemyData.Entry e) {
        if (e.lastStone <= 0) return 0;
        return e.lastStone + cooldownDays(e) * DAY_MS - System.currentTimeMillis();
    }

    // ───────── 의식 ─────────

    private static void forge(ServerPlayerEntity p, BlockPos pos) {
        // 창을 연 뒤 상태가 바뀌었을 수 있으니 전부 다시 확인
        AlchemyData.Entry me = Jobs.entry(p);
        if (Jobs.rank(me) < MIN_RANK) { fail(p, "대연금술사(등급 4) 이상만 현자의 돌을 연성할 수 있습니다."); return; }
        if (!me.truth && !AlchemyMenu.near(p, pos)) { fail(p, "연성진에서 너무 멀어졌습니다."); return; }
        if (BattleRegistry.getBattleByParticipatingPlayer(p) != null) { fail(p, "배틀 중에는 연성할 수 없습니다."); return; }
        AlchemyData data = AlchemyData.get(p.getServer());
        AlchemyData.Entry e = data.of(p.getUuid());
        if (e.energy + 1e-6 < AlchemyValues.stoneCost) { fail(p, "연성 에너지가 부족합니다."); return; }
        if (cooldownLeft(e) > 0) { fail(p, "아직 다시 연성할 수 없습니다."); return; }
        if (serverLeft(data) > 0) { fail(p, "서버 전체 대기 중입니다. 남은 시간: " + dur(serverLeft(data))); return; }
        if (!itemsReady(p)) { fail(p, "재료가 부족합니다."); return; }
        if (!sacrificeReady(p)) { fail(p, "제물 조건(파티 " + sacrifices(e) + "마리 전부 Lv." + AlchemyValues.stoneMinLevel + " 이상)이 맞지 않습니다."); return; }

        // 레벨이 높은 순서가 아니라 파티 앞쪽부터 필요한 수만큼 (기본 6 = 파티 전원)
        PlayerPartyStore store = Cobblemon.INSTANCE.getStorage().getParty(p);
        List<Pokemon> victims = new ArrayList<>();
        for (Pokemon mon : party(p)) {
            if (mon.getLevel() >= AlchemyValues.stoneMinLevel) victims.add(mon);
            if (victims.size() >= sacrifices(e)) break;
        }
        // 먼저 전부 파티에서 빼 보고, 하나라도 실패하면 뺀 것을 되돌린 뒤 중단
        List<Pokemon> removed = new ArrayList<>();
        for (Pokemon mon : victims) {
            if (mon.getEntity() != null) mon.recall();
            if (!store.remove(mon)) {
                for (Pokemon back : removed) if (!store.add(back)) Cobblemon.INSTANCE.getStorage().getPC(p).add(back);
                fail(p, "제물을 바치는 중 오류가 나서 취소했습니다. 포켓몬은 돌려놓았습니다.");
                DuelMod.LOG.error("[alchemy] stone forge: failed to remove {} from {}", mon.getUuid(), p.getName().getString());
                return;
            }
            removed.add(mon);
        }
        List<String> names = new ArrayList<>();
        for (Pokemon mon : removed) {
            ItemStack held = mon.heldItem();
            if (held != null && !held.isEmpty()) p.getInventory().offerOrDrop(held.copy());
            names.add(mon.getSpecies().getTranslatedName().getString() + " Lv." + mon.getLevel() + (mon.getShiny() ? "★" : ""));
        }

        for (var en : AlchemyValues.STONE_ITEMS.entrySet()) Jobs.take(p, en.getKey(), en.getValue());
        e.energy = Math.max(0, e.energy - AlchemyValues.stoneCost);
        e.lastStone = System.currentTimeMillis();
        data.serverLastStone = e.lastStone;
        data.markDirty();
        p.getInventory().offerOrDrop(new ItemStack(DuelMod.PHILOSOPHERS_STONE));

        // 연출: 붉은 스파크 → 바닥 연성진 → 번개
        ServerWorld w = p.getServerWorld();
        Alchemy.bigFx(p);
        StoneFx.circleAt(w, Vec3d.ofBottomCenter(pos), 60);
        w.playSound(null, pos, SoundEvents.ENTITY_WITHER_SPAWN, SoundCategory.PLAYERS, 0.7f, 0.6f);
        Alchemy.fx(w, pos, true);

        p.sendMessage(Text.literal("☠ " + String.join(", ", names) + " — 생명이 현자의 돌이 되었다...").formatted(Formatting.DARK_RED), false);
        p.getServer().getPlayerManager().broadcast(
                Text.literal("✦ " + p.getName().getString() + "님이 " + victims.size() + "개의 생명을 바쳐 현자의 돌을 연성했습니다.")
                        .formatted(Formatting.RED, Formatting.BOLD), false);
    }

    private static void fail(ServerPlayerEntity p, String msg) {
        p.sendMessage(Text.literal(msg).formatted(Formatting.RED), false);
    }
}
