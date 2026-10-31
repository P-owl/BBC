package bbc.client;

import bbc.net.GrowthActionPayload;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/**
 * 성장 창 (K) — 마인크래프트 보관함 창 모양 (회색 상자, 왼쪽 레시피 책 탭, 바닐라 단추·발전과제 테두리·경험치 막대).
 *  상태: 파워(도전과제 테두리 + 경험치 레벨 같은 초록 숫자), 이름·종족·직업, 존재치(경험치 막대), 특성 4개(목표 테두리),
 *        스탯 8개(과제 테두리 안에 그 스탯의 수련 도구, 아래 = 수치 + 다음 1점 경험치 막대, 확정 대기면 노란 테두리 + 초록 +N → 클릭 확정)
 *  재능 카드: 왼쪽 목록 + 오른쪽 카드 / 기술 숙련: 보스 막대
 */
public class GrowthScreen extends Screen {
    private static final int W = 300, H = 212;
    private int tab = 0;
    private int selCard = 0;
    private final Ui.Hits hits = new Ui.Hits();
    private float sc = 1;
    private int ox, oy;

    public GrowthScreen() { super(Text.literal("성장")); }

    @Override
    protected void init() { GrowthClient.send(GrowthActionPayload.SYNC, ""); }

    @Override
    public boolean shouldPause() { return false; }

    private void click() { TalentScreen.play(SoundEvents.UI_BUTTON_CLICK.value(), 1f); }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderBackground(ctx, mouseX, mouseY, delta);
        int fullW = W + 32;
        sc = Math.min(1f, Math.min(width / (float) (fullW + 8), height / (float) (H + 8)));
        ox = Math.round((width - fullW * sc) / 2f + 32 * sc); oy = Math.round((height - H * sc) / 2f);
        double mx = (mouseX - ox) / sc, my = (mouseY - oy) / sc;
        hits.clear();
        var m = ctx.getMatrices();
        m.push();
        m.translate(ox, oy, 0);
        m.scale(sc, sc, 1);
        TextRenderer tr = textRenderer;
        // 왼쪽 탭 (상자 뒤로 들어가게 먼저)
        String[] names = {"상태", "재능 카드", "기술 숙련"};
        ItemStack[] icons = {new ItemStack(Items.EXPERIENCE_BOTTLE), new ItemStack(Items.PAPER), new ItemStack(Items.WRITABLE_BOOK)};
        for (int i = 0; i < 3; i++) {
            int ty = 18 + i * 28;
            if (tab != i) Ui.sideTab(ctx, false, -28, ty);
            final int t = i;
            hits.add(-30, ty, 30, 26, () -> { if (tab != t) { tab = t; click(); } }, null, List.of(names[i]));
        }
        Ui.panel(ctx, 0, 0, W, H);
        Ui.sideTab(ctx, true, -31, 18 + tab * 28);
        for (int i = 0; i < 3; i++) Ui.item(ctx, icons[i], (tab == i ? -31 : -28) + 8, 18 + i * 28 + 5, 1);
        Ui.titleBar(ctx, tr, 3, 3, W - 6, "성장 · " + names[tab]);
        if (GrowthClient.state.size() == 0) Ui.centered(ctx, tr, "불러오는 중...", W / 2, H / 2, Ui.TEXT, true);
        else if (tab == 0) drawStatus(ctx, tr, mx, my);
        else if (tab == 1) drawCards(ctx, tr, mx, my);
        else drawMastery(ctx, tr);
        m.pop();
        List<String> tip = hits.tip(mx, my);
        if (tip != null && !tip.isEmpty()) {
            List<Text> lines = new ArrayList<>();
            for (int i = 0; i < tip.size(); i++) if (!tip.get(i).isEmpty())
                lines.add(i == 0 ? Text.literal(tip.get(i)).formatted(Formatting.YELLOW) : Text.literal(tip.get(i)).formatted(Formatting.GRAY));
            ctx.drawTooltip(tr, lines, mouseX, mouseY);
        }
    }

    // ═════════ 상태 ═════════

    private void drawStatus(DrawContext ctx, TextRenderer tr, double mx, double my) {
        JsonObject st = GrowthClient.state;
        int pw = GrowthClient.i("power");
        double ep = GrowthClient.d("ep"), from = GrowthClient.d("epFrom"), to = GrowthClient.d("epTo");
        float frac = (float) Math.max(0, Math.min(1, (ep - from) / Math.max(1, to - from)));
        // ── 파워: 도전과제 테두리(2배) + 경험치 레벨 같은 초록 숫자
        Ui.frame(ctx, "challenge", true, 8, 18, 52);
        Ui.outlined(ctx, tr, String.valueOf(pw), 34, 36, 2, Ui.GREEN);
        Ui.centered(ctx, tr, "파워", 34, 72, Ui.TITLE, true);
        hits.add(8, 18, 52, 52, null, null, List.of("파워 " + pw + " / " + GrowthClient.i("maxPower"),
                "다음 파워: 스탯 " + GrowthClient.i("per") + "점 확정 + 존재치 " + (long) to, "존재치는 몬스터 처치 · 새 바이옴 발견으로"));
        // ── 이름·종족·직업
        int lx = 68;
        ctx.drawText(tr, GrowthClient.s("name"), lx, 20, Ui.TEXT, true);
        JsonObject race = st.getAsJsonObject("race");
        String rn = race.get("name").getAsString() + (race.get("sub").getAsString().isEmpty() ? "" : " (" + race.get("sub").getAsString() + ")");
        String rl = "종족: " + rn;
        ctx.drawText(tr, rl, lx, 32, Ui.TEXT, true);
        int rw = tr.getWidth(rl);
        hits.add(lx, 31, rw, 10, null, null, List.of(rn, "장점: " + race.get("good").getAsString(), "약점: " + race.get("bad").getAsString()));
        if (!race.get("rerolled").getAsBoolean()) {
            int bx = lx + rw + 4;
            boolean hov = Ui.Hits.inside(mx, my, bx, 29, 34, 14);
            Ui.button(ctx, tr, bx, 29, 34, 14, "다시", hov, true);
            hits.add(bx, 29, 34, 14, () -> { click(); GrowthClient.send(GrowthActionPayload.REROLL, ""); }, null, List.of("종족 다시 굴리기", "한 번만 무료"));
        }
        ctx.drawText(tr, "직업: " + GrowthClient.s("job"), lx, 44, Ui.TEXT, true);
        // ── 존재치 = 경험치 막대
        String es = "존재치 " + (long) ep + " / " + (long) to;
        ctx.drawText(tr, es, lx, 56, Ui.TEXT, true);
        Ui.xpBar(ctx, lx, 66, frac);
        hits.add(lx, 55, 182, 16, null, null, List.of(es, "몬스터 처치 · 새 바이옴 발견으로 쌓임", "가득 차고 스탯을 다 확정하면 파워 상승"));
        int inj = GrowthClient.i("injury");
        if (inj > 0) {
            String is = "부상 " + inj / 60 + ":" + String.format("%02d", inj % 60);
            ctx.drawText(tr, is, W - 8 - tr.getWidth(is), 20, Ui.RED, false);
            hits.add(W - 8 - tr.getWidth(is), 19, tr.getWidth(is), 10, null, null, List.of("부상", "전투 중 도주: 최대 체력 −15%"));
        }
        // ── 소각 토큰 (오른쪽 위 슬롯 3칸)
        int burns = GrowthClient.i("burns");
        for (int i = 0; i < 3; i++) {
            int sx = W - 8 - 54 + i * 18;
            Ui.slot(ctx, sx, 34);
            if (i < burns) Ui.item(ctx, new ItemStack(Items.BLAZE_POWDER), sx + 1, 35, 1);
        }
        hits.add(W - 8 - 54, 34, 54, 18, null, null, List.of("소각 토큰 " + burns + " / 3", "재능 카드를 태우면 다시는 안 나옴", "파워가 오를 때마다 +1"));

        // ── 특성 4개 (목표 테두리)
        int tp = GrowthClient.i("tp");
        ctx.drawText(tr, "특성", 8, 84, Ui.TITLE, true);
        ctx.drawText(tr, "남은 점수 " + tp, 8 + tr.getWidth("특성") + 6, 84, tp > 0 ? Ui.LIME : Ui.TEXT_DIM, true);
        JsonArray traits = st.getAsJsonArray("traits");
        for (int i = 0; i < traits.size(); i++) {
            JsonObject t = traits.get(i).getAsJsonObject();
            int fx = 8 + i * 72, fy = 94;
            int v = t.get("v").getAsInt();
            Ui.frame(ctx, "goal", v > 0, fx, fy, 26);
            Ui.outlined(ctx, tr, String.valueOf(v), fx + 13, fy + 9, 1, v > 0 ? Ui.GREEN : Ui.WHITE);
            String tn = t.get("name").getAsString();
            ctx.drawText(tr, tn, fx + 30, fy + 4, Ui.TEXT, true);
            ctx.drawText(tr, v + " / 6", fx + 30, fy + 15, Ui.TEXT_DIM, true);
            final int idx = i;
            boolean can = tp > 0 && v < 6;
            if (can) {
                int bx = fx + 30 + tr.getWidth(v + " / 6") + 3;
                boolean hov = Ui.Hits.inside(mx, my, bx, fy + 13, 11, 11);
                Ui.button(ctx, tr, bx, fy + 13, 11, 11, "+", hov, true);
                hits.add(bx, fy + 13, 11, 11, () -> { click(); GrowthClient.send(GrowthActionPayload.TRAIT, String.valueOf(idx)); }, null, List.of(tn + " +1"));
            }
            hits.add(fx, fy, 26, 26, null, null, List.of(tn + "  " + v + " / 6", t.get("desc").getAsString() + " (점당)", can ? "+ 를 누르면 1점 투자" : ""));
        }
        Ui.goldLine(ctx, 8, 126, W - 16, Ui.GOLD_D);

        // ── 스탯 8개 (과제 테두리 + 수련 도구)
        JsonArray stats = st.getAsJsonArray("stats");
        int[][] groups = {{0, 1, 2}, {3}, {4, 5, 6}, {7}};
        String[] gnames = {"신체", "정신", "무기", "직업"};
        int[] gx = {8, 116, 156, 264};
        for (int g = 0; g < 4; g++) {
            int span = groups[g].length * 32 - 6;
            int sum = 0;
            for (int i : groups[g]) sum += stats.get(i).getAsJsonObject().get("v").getAsInt();
            Ui.centered(ctx, tr, gnames[g] + " " + sum, gx[g] + span / 2, 130, Ui.TITLE, true);
            for (int k = 0; k < groups[g].length; k++) stat(ctx, tr, stats.get(groups[g][k]).getAsJsonObject(), groups[g][k], gx[g] + k * 32, 141, mx, my);
        }
        Ui.goldLine(ctx, 8, 185, W - 16, Ui.GOLD_D);

        // ── 아래: 투자 현황 + 카드 고르기
        int pend = 0;
        for (JsonElement el : stats) pend += el.getAsJsonObject().get("pend").getAsInt();
        int inv = GrowthClient.i("inv"), per = GrowthClient.i("per");
        ctx.drawText(tr, "이번 파워 투자 " + inv + "/" + per, 8, 190, inv >= per ? Ui.LIME : Ui.TEXT, true);
        ctx.drawText(tr, "확정 대기 " + pend + "점", 8, 200, pend > 0 ? Ui.LIME : Ui.TEXT_DIM, true);
        if (!st.getAsJsonArray("pick").isEmpty()) pickButton(ctx, tr, W - 8 - 120, 188, mx, my);
        else ctx.drawText(tr, "노란 테두리 스탯을 누르면 확정", W - 8 - tr.getWidth("노란 테두리 스탯을 누르면 확정"), 195, Ui.TEXT_DIM, true);
    }

    private void pickButton(DrawContext ctx, TextRenderer tr, int x, int y, double mx, double my) {
        boolean hov = Ui.Hits.inside(mx, my, x, y, 120, 20);
        boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
        Ui.button(ctx, tr, x, y, 120, 20, (blink ? "§e" : "§f") + "재능 카드 고르기!", hov, true);
        hits.add(x, y, 120, 20, () -> { click(); client.setScreen(new TalentScreen(false)); }, null, List.of("고르지 않은 재능 카드가 있습니다"));
    }

    /** 스탯 하나: 과제 테두리(확정 대기면 노란색) 안에 수련 도구, 아래 수치·경험치 막대 */
    private void stat(DrawContext ctx, TextRenderer tr, JsonObject s, int idx, int x, int y, double mx, double my) {
        int v = s.get("v").getAsInt(), pend = s.get("pend").getAsInt(), tools = s.get("tools").getAsInt();
        double xp = s.get("x").getAsDouble(), need = s.get("need").getAsDouble();
        Ui.frame(ctx, "task", pend > 0, x, y, 26);
        Ui.item(ctx, Ui.toolStack(idx), x + 5, y + 5, 1);
        if (Ui.Hits.inside(mx, my, x, y, 26, 26)) ctx.fill(x + 5, y + 5, x + 21, y + 21, 0x80FFFFFF);
        Ui.centered(ctx, tr, String.valueOf(v), x + 13, y + 28, Ui.TEXT, true);
        // 다음 1점 경험치
        int bw = 24, fw = (int) (bw * Math.min(1, xp / Math.max(1, need)));
        ctx.fill(x + 1, y + 38, x + 1 + bw, y + 40, Ui.DARK);
        if (fw > 0) ctx.fill(x + 1, y + 38, x + 1 + fw, y + 40, pend > 0 ? Ui.YELLOW : Ui.GREEN);
        Runnable confirm = null;
        if (pend > 0) {
            ctx.getMatrices().push();
            ctx.getMatrices().translate(0, 0, 200);   // 아이템 위로
            Ui.outlined(ctx, tr, "+" + pend, x + 22, y - 3, 1, Ui.GREEN);
            ctx.getMatrices().pop();
            if (tools > 0) confirm = () -> { click(); GrowthClient.send(GrowthActionPayload.CONFIRM, s.get("id").getAsString()); };
        }
        String name = s.get("name").getAsString();
        hits.add(x, y, 26, 40, confirm, null, List.of(
                name + "  " + v + (pend > 0 ? "  (+" + pend + " 확정 대기)" : ""),
                "경험치 " + (int) xp + " / " + (int) need,
                "쌓는 법: " + s.get("how").getAsString(),
                "확정 도구: " + s.get("tool").getAsString() + (tools > 0 ? " (남은 " + tools + "회)" : " (없음)"),
                pend > 0 ? (tools > 0 ? "§a클릭: 1점 확정" : "§c도구를 조합하세요") : ""));
    }

    // ═════════ 재능 카드 ═════════

    private void drawCards(DrawContext ctx, TextRenderer tr, double mx, double my) {
        JsonArray cards = GrowthClient.state.getAsJsonArray("cards");
        int lx = 8, ly = 18, lw = 150, lh = H - 26;
        Ui.inset(ctx, lx, ly, lw, lh, Ui.SLOT);
        int y = ly + 4;
        String[] heads = {"전설", "희귀", "일반"};
        int[] order = {2, 1, 0};
        int[] cols = {Ui.GOLD, Ui.AQUA, Ui.WHITE};
        List<Integer> flat = new ArrayList<>();
        for (int g = 0; g < 3; g++) {
            boolean any = false;
            for (int i = 0; i < cards.size(); i++) if (cards.get(i).getAsJsonObject().get("rarity").getAsInt() == order[g]) any = true;
            if (!any) continue;
            ctx.drawText(tr, heads[g], lx + 4, y, Ui.GRAY, false);
            y += 11;
            for (int i = 0; i < cards.size(); i++) {
                JsonObject c = cards.get(i).getAsJsonObject();
                if (c.get("rarity").getAsInt() != order[g]) continue;
                boolean sel = flat.size() == selCard;
                int fi = flat.size();
                flat.add(i);
                boolean hov = Ui.Hits.inside(mx, my, lx + 2, y - 1, lw - 4, 12);
                if (sel) { ctx.fill(lx + 2, y - 2, lx + lw - 2, y + 10, 0xFF3A3A44); ctx.fill(lx + 2, y - 2, lx + 4, y + 10, Ui.TITLE); }
                else if (hov) ctx.fill(lx + 2, y - 2, lx + lw - 2, y + 10, 0x30FFFFFF);
                ctx.drawTextWithShadow(tr, c.get("name").getAsString(), lx + 8, y, cols[g]);
                hits.add(lx + 2, y - 2, lw - 4, 12, () -> selCard = fi, null, null);
                y += 12;
            }
            y += 4;
        }
        if (cards.isEmpty()) {
            ctx.drawTextWithShadow(tr, "아직 카드가 없습니다", lx + 6, ly + 6, Ui.GRAY);
            ctx.drawTextWithShadow(tr, "파워가 오르면 고를 수 있음", lx + 6, ly + 18, Ui.TEXT_DIM);
        }
        int cx = lx + lw + (W - 8 - lx - lw) / 2, cy = 18;
        if (!flat.isEmpty()) {
            JsonObject c = cards.get(flat.get(Math.min(selCard, flat.size() - 1))).getAsJsonObject();
            TalentScreen.drawCard(ctx, tr, c, cx - TalentScreen.CW / 2, cy, false, false, 1f);
        } else TalentScreen.drawBack(ctx, cx - TalentScreen.CW / 2, cy, 1f);
        if (!GrowthClient.state.getAsJsonArray("pick").isEmpty()) pickButton(ctx, tr, cx - 60, cy + TalentScreen.CH + 6, mx, my);
    }

    // ═════════ 숙련 ═════════

    private void drawMastery(DrawContext ctx, TextRenderer tr) {
        JsonArray ms = GrowthClient.state.getAsJsonArray("mastery");
        ctx.drawText(tr, "쓸 때마다 +2% · 100% = ★숙달 (재사용 대기·비용 −20%)", 8, 20, Ui.TEXT_DIM, true);
        Ui.goldLine(ctx, 8, 32, W - 16, Ui.GOLD_D);
        if (ms.isEmpty()) { ctx.drawText(tr, "직업 기술이 없습니다", 8, 40, Ui.TEXT_DIM, true); return; }
        for (int i = 0; i < ms.size(); i++) {
            JsonObject o = ms.get(i).getAsJsonObject();
            double v = o.get("m").getAsDouble();
            int y = 38 + i * 24;
            boolean done = v >= 100;
            Ui.frame(ctx, "task", done, 8, y - 2, 20);
            Ui.centered(ctx, tr, done ? "★" : String.valueOf(i + 1), 18, y + 4, done ? 0xFFFFDD00 : Ui.WHITE, true);
            ctx.drawText(tr, o.get("name").getAsString(), 34, y, Ui.TEXT, true);
            String ps = done ? "숙달" : (int) v + "%";
            ctx.drawText(tr, ps, W - 8 - tr.getWidth(ps), y, done ? Ui.GOLD : Ui.TEXT_DIM, true);
            Ui.bossBar(ctx, done ? "yellow" : "green", 34, y + 11, (float) Math.min(1, v / 100));
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (hits.click((mouseX - ox) / sc, (mouseY - oy) / sc, button)) return true;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 75) { close(); return true; }   // K 로 닫기
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
