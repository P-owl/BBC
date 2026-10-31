package bbc.grow;

import bbc.core.ButtonMenu;
import bbc.core.PlayerData;
import bbc.job.JobSkills;
import bbc.job.Jobs;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/**
 * 성장 창 (/bbc grow 또는 K). 6줄 상자:
 *  1줄  파워·존재치 | 종족 (다시 돌리기) | 가진 재능 카드 | 고를 카드
 *  3줄  스탯 8개 (확정·대기·경험치, 수련 도구)
 *  5줄  특성 4개 (클릭해서 투자)
 *  6줄  직업 기술 숙련
 */
public class GrowthMenu extends ButtonMenu {

    public static void open(ServerPlayerEntity p) {
        Growth.ensureRace(p);
        p.openHandledScreen(new SimpleNamedScreenHandlerFactory((id, inv, x) -> new GrowthMenu(id, inv, p), Text.literal("성장")));
    }

    public static void openCards(ServerPlayerEntity p) {
        if (Growth.pick(Growth.e(p)).isEmpty()) return;
        p.openHandledScreen(new SimpleNamedScreenHandlerFactory((id, inv, x) -> new CardMenu(id, inv, p), Text.literal("재능 카드 — 하나를 고르세요")));
    }

    private GrowthMenu(int syncId, PlayerInventory inv, ServerPlayerEntity player) {
        super(syncId, inv, player, 6);
        draw();
    }

    private void draw() {
        PlayerData.Entry e = Growth.e(player);
        int pw = Growth.power(e);
        double ep = Growth.ep(e), next = Growth.epFor(pw + 1);
        button(0, icon(Items.NETHER_STAR, "파워 " + pw + (pw >= Growth.MAX_POWER ? " (최고)" : ""), Formatting.GOLD,
                "존재치 " + (int) ep + " / " + (int) next, Growth.bar(ep - Growth.epFor(pw), next - Growth.epFor(pw), 20),
                "이번 파워 투자 " + Growth.invested(e) + " / 15",
                "다음 파워: 스탯 15점 확정 + 존재치 " + (int) next,
                "존재치: 몬스터 처치 · 새 바이옴 발견"));
        Race r = Growth.race(e);
        List<String> rl = new ArrayList<>(List.of("장점: " + r.good, "약점: " + r.bad));
        if (!Growth.raceSub(e).isEmpty()) rl.add(0, "갈래: " + Growth.raceSub(e));
        rl.add(e.flag("g.rerolled") ? "다시 돌리기 사용함" : "▶ 클릭: 무료로 한 번 다시 돌리기");
        button(2, icon(Items.PLAYER_HEAD, "종족: " + r.name, r.color, rl.toArray(new String[0])));
        List<Talent> cards = Growth.cards(e);
        List<String> cl = new ArrayList<>();
        for (Talent t : cards) cl.add("· " + t.name + " — " + t.effect);
        if (cl.isEmpty()) cl.add("아직 없음 (파워가 오를 때 고름)");
        button(4, icon(Items.BOOK, "재능 카드 " + cards.size() + "장", Formatting.LIGHT_PURPLE, cl.toArray(new String[0])));
        if (!Growth.pick(e).isEmpty()) button(6, icon(Items.ENCHANTED_BOOK, "고를 카드가 있습니다!", Formatting.YELLOW, "▶ 클릭해서 고르기"));
        else button(6, icon(Items.GRAY_DYE, "고를 카드 없음", Formatting.GRAY));
        int tp = Growth.traitPoints(e);
        button(8, icon(Items.EXPERIENCE_BOTTLE, "특성 점수 " + Math.max(0, tp), Formatting.GREEN, "짝수 파워마다 1점 (최대 12, 한 곳 6)"));

        Stat[] st = Stat.values();
        for (int i = 0; i < st.length; i++) {
            Stat s = st[i];
            int v = Growth.stat(e, s), pd = Growth.pending(e, s);
            double x = Growth.exp(e, s), nd = Growth.need(e, s);
            button(18 + i, icon(s.icon, s.name + " " + v + (pd > 0 ? "  (+" + pd + " 대기)" : ""), pd > 0 ? Formatting.GREEN : Formatting.WHITE,
                    "경험치 " + Growth.bar(x, nd, 12) + " " + (int) x + "/" + (int) nd,
                    "쌓는 법: " + s.how,
                    "확정 도구: " + s.tool + " (우클릭)",
                    "최대 " + Growth.STAT_CAP));
        }
        for (int i = 0; i < Growth.TRAITS.length; i++) {
            int t = Growth.trait(e, i);
            button(37 + i * 2, icon(traitIcon(i), Growth.TRAIT_NAMES[i] + " " + t + "/6", t > 0 ? Formatting.AQUA : Formatting.WHITE,
                    Growth.TRAIT_DESC[i] + " (점당)", tp > 0 && t < 6 ? "▶ 클릭해서 1점 투자" : ""));
        }
        JobSkills.Set set = JobSkills.of(e);
        if (set != null) {
            JobSkills.Skill[] sk = set.skills(player, e);
            for (int i = 0; i < Math.min(9, sk.length); i++) {
                if (sk[i] == null) continue;
                double m = Growth.mastery(e, i);
                button(45 + i, icon(m >= 100 ? Items.ENCHANTED_BOOK : Items.PAPER, sk[i].name() + (m >= 100 ? " ★숙달" : " " + (int) m + "%"), m >= 100 ? Formatting.GOLD : Formatting.WHITE,
                        Growth.bar(m, 100, 12), "쓸수록 오름 (한 번에 2%)", "숙달: 재사용·비용 −20%"));
            }
        }
        refresh();
    }

    private static net.minecraft.item.Item traitIcon(int i) {
        return switch (i) { case 0 -> Items.GOLDEN_APPLE; case 1 -> Items.SUGAR; case 2 -> Items.IRON_SWORD; default -> Items.AMETHYST_SHARD; };
    }

    @Override
    protected boolean onPick(int slot) {
        PlayerData.Entry e = Growth.e(player);
        if (slot == 2 && !e.flag("g.rerolled")) { Growth.rerollRace(player); draw(); return false; }
        if (slot == 6 && !Growth.pick(e).isEmpty()) { player.closeHandledScreen(); openCards(player); return false; }
        for (int i = 0; i < Growth.TRAITS.length; i++) if (slot == 37 + i * 2) { Growth.spendTrait(player, i); draw(); return false; }
        return false;
    }

    /** 재능 카드 고르기 (1줄) */
    static class CardMenu extends ButtonMenu {
        private final List<Talent> pick;

        CardMenu(int syncId, PlayerInventory inv, ServerPlayerEntity player) {
            super(syncId, inv, player, 1);
            pick = Growth.pick(Growth.e(player));
            int[] at = pick.size() >= 4 ? new int[]{1, 3, 5, 7} : new int[]{2, 4, 6};
            for (int i = 0; i < pick.size() && i < at.length; i++) {
                Talent t = pick.get(i);
                button(at[i], icon(t.icon(), t.name, t.color(), "[" + t.rarityName() + " · " + t.group + "]", t.effect, "조건: " + t.cond, "▶ 클릭해서 고르기"));
            }
            refresh();
        }

        @Override
        protected boolean onPick(int slot) {
            int[] at = pick.size() >= 4 ? new int[]{1, 3, 5, 7} : new int[]{2, 4, 6};
            for (int i = 0; i < pick.size() && i < at.length; i++) if (at[i] == slot) { Growth.choose(player, pick.get(i)); return true; }
            return false;
        }

        @Override
        protected void onCancel() {
            player.sendMessage(Text.literal("카드는 나중에 성장 창(K)에서 고를 수 있습니다").formatted(Formatting.GRAY), false);
        }
    }
}
