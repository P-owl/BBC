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
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * 성장 창 (K). Deepwoken 캐릭터 창을 참고: 어두운 청록 바탕 + 크림색 테두리, 왼쪽 탭 아이콘,
 * 위에 큰 파워 메달과 종족·직업 이름표·존재치 막대, 오른쪽 위 특성 4개 배지, 가운데 스탯 배지(그룹별),
 * 배지 둘레 고리 = 다음 1점까지 경험치, 확정 대기는 ↑ 표시 (클릭하면 가방의 수련 도구로 확정).
 * 탭: 상태 / 재능 카드 / 기술 숙련
 */
public class GrowthScreen extends Screen {
    private static final int PW = 360, PH = 400;
    private int tab = 0;
    private int selCard = 0;
    private final Ui.Hits hits = new Ui.Hits();
    private float sc = 1, ox, oy;

    public GrowthScreen() { super(Text.literal("성장")); }

    @Override
    protected void init() { GrowthClient.send(GrowthActionPayload.SYNC, ""); }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, width, height, 0x88000000);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderBackground(ctx, mouseX, mouseY, delta);
        sc = Math.min(width * 0.94f / (PW + 30), height * 0.94f / PH);
        ox = (width - (PW + 30) * sc) / 2f + 30 * sc; oy = (height - PH * sc) / 2f;   // 왼쪽 탭 자리 30
        double mx = (mouseX - ox) / sc, my = (mouseY - oy) / sc;
        hits.clear();
        var m = ctx.getMatrices();
        m.push();
        m.translate(ox, oy, 0);
        m.scale(sc, sc, 1);
        TextRenderer tr = textRenderer;
        Ui.frame(ctx, 0, 0, PW, PH, Ui.TEAL);
        // 왼쪽 탭
        ItemStack[] icons = {new ItemStack(Items.NETHER_STAR), new ItemStack(Items.ENCHANTED_BOOK), new ItemStack(Items.WRITABLE_BOOK)};
        String[] names = {"상태", "재능 카드", "기술 숙련"};
        for (int i = 0; i < 3; i++) {
            int tx = -26, ty = 10 + i * 26;
            boolean on = tab == i;
            Ui.circle(ctx, tx + 11, ty + 11, 12, Ui.INK);
            Ui.circle(ctx, tx + 11, ty + 11, 11, on ? Ui.CREAM : Ui.CREAM_D);
            ctx.drawItem(icons[i], tx + 3, ty + 3);
            final int t = i;
            hits.add(tx, ty, 22, 22, () -> tab = t, null, List.of(names[i]));
        }
        if (GrowthClient.state.size() == 0) {
            ctx.drawCenteredTextWithShadow(tr, "불러오는 중...", PW / 2, PH / 2, 0xFFFFFFFF);
        } else if (tab == 0) drawStatus(ctx, tr, mx, my);
        else if (tab == 1) drawCards(ctx, tr, mx, my);
        else drawMastery(ctx, tr);
        m.pop();
        // 툴팁 (화면 좌표)
        List<String> tip = hits.tip(mx, my);
        if (tip != null && !tip.isEmpty()) {
            List<Text> lines = new ArrayList<>();
            for (String s : tip) lines.add(Text.literal(s));
            ctx.drawTooltip(tr, lines, mouseX, mouseY);
        }
    }

    // ═════════ 상태 ═════════

    private void drawStatus(DrawContext ctx, TextRenderer tr, double mx, double my) {
        JsonObject st = GrowthClient.state;
        int pw = GrowthClient.i("power");
        // 파워 메달
        int cx = 46, cy = 44;
        Ui.circle(ctx, cx, cy, 31, Ui.INK);
        Ui.ring(ctx, cx, cy, 25, 30, Ui.BRONZE, 0, 1);
        for (int k = 0; k < 12; k++) {   // 톱니 장식
            double a = Math.PI * 2 * k / 12;
            Ui.diamond(ctx, cx + (float) Math.cos(a) * 30, cy + (float) Math.sin(a) * 30, 3, Ui.GOLD);
        }
        Ui.circle(ctx, cx, cy, 24, 0xFF203A4A);
        Ui.ring(ctx, cx, cy, 22, 24, 0xFF8FB0C0, 0, 1);
        double ep = GrowthClient.d("ep"), from = GrowthClient.d("epFrom"), to = GrowthClient.d("epTo");
        float frac = (float) Math.max(0, Math.min(1, (ep - from) / Math.max(1, to - from)));
        Ui.ring(ctx, cx, cy, 25, 27, 0xFFE8D27A, 0, frac);   // 존재치 진행 고리
        var m = ctx.getMatrices();
        m.push(); m.translate(cx, cy - 9, 0); m.scale(2.4f, 2.4f, 1);
        String n = String.valueOf(pw);
        ctx.drawText(tr, n, -tr.getWidth(n) / 2, 0, 0xFFFFFFFF, true);
        m.pop();
        ctx.drawCenteredTextWithShadow(tr, "파워", cx, cy + 34, 0xFFE8DDBE);
        hits.add(cx - 30, cy - 30, 60, 60, null, null, List.of("파워 " + pw + " / " + GrowthClient.i("maxPower"),
                "존재치 " + (long) ep + " / " + (long) to, "다음 파워: 스탯 " + GrowthClient.i("per") + "점 확정 + 존재치 " + (long) to,
                "존재치: 몬스터 처치 · 새 바이옴 발견"));
        // 이름표
        JsonObject race = st.getAsJsonObject("race");
        int lx = 84, ly = 12;
        Ui.tag(ctx, tr, lx, ly, "이름: ", GrowthClient.s("name"));
        String rn = race.get("name").getAsString() + (race.get("sub").getAsString().isEmpty() ? "" : " (" + race.get("sub").getAsString() + ")");
        Ui.tag(ctx, tr, lx, ly + 14, "종족: ", rn);
        int rw = tr.getWidth("종족: " + rn) + 8;
        hits.add(lx, ly + 14, rw, 11, null, null, List.of("장점: " + race.get("good").getAsString(), "약점: " + race.get("bad").getAsString()));
        if (!race.get("rerolled").getAsBoolean()) {
            int bx = lx + rw + 3;
            ctx.fill(bx, ly + 14, bx + 34, ly + 25, 0xFF6E2A2A);
            ctx.drawText(tr, "다시", bx + 6, ly + 16, 0xFFFFE0C0, false);
            hits.add(bx, ly + 14, 34, 11, () -> GrowthClient.send(GrowthActionPayload.REROLL, ""), null, List.of("종족 다시 돌리기 (한 번 무료)"));
        }
        Ui.tag(ctx, tr, lx, ly + 28, "직업: ", GrowthClient.s("job"));
        // 존재치 막대
        int bx = lx, by = ly + 46, bw = 150;
        ctx.fill(bx - 1, by - 1, bx + bw + 1, by + 6, Ui.INK);
        ctx.fill(bx, by, bx + bw, by + 5, 0xFF2A2A20);
        ctx.fill(bx, by, bx + (int) (bw * frac), by + 5, 0xFFE8D27A);
        ctx.drawText(tr, "존재치 " + (long) ep + " / " + (long) to, bx, by + 8, 0xFFE8DDBE, false);
        int inj = GrowthClient.i("injury");
        if (inj > 0) ctx.drawText(tr, "부상 " + inj / 60 + "분 " + inj % 60 + "초", bx, by + 18, 0xFFFF6A5A, false);
        // 특성 4개 (오른쪽 위)
        JsonArray traits = st.getAsJsonArray("traits");
        int tp = GrowthClient.i("tp");
        for (int i = 0; i < traits.size(); i++) {
            JsonObject t = traits.get(i).getAsJsonObject();
            int tx = 266 + (i % 2) * 46, ty = 24 + (i / 2) * 42;
            Ui.circle(ctx, tx, ty, 13, Ui.INK);
            Ui.circle(ctx, tx, ty, 12, Ui.CREAM);
            Ui.circle(ctx, tx, ty, 9, 0xFF2A2A22);
            String v = String.valueOf(t.get("v").getAsInt());
            ctx.drawText(tr, v, tx - tr.getWidth(v) / 2, ty - 4, 0xFFFFFFFF, true);
            String tn = t.get("name").getAsString();
            ctx.drawText(tr, tn, tx - tr.getWidth(tn) / 2, ty + 15, 0xFFE8DDBE, false);
            final int idx = i;
            boolean can = tp > 0 && t.get("v").getAsInt() < 6;
            hits.add(tx - 13, ty - 13, 26, 26, can ? () -> GrowthClient.send(GrowthActionPayload.TRAIT, String.valueOf(idx)) : null, null,
                    List.of(tn + " " + v + "/6", t.get("desc").getAsString() + " (점당)", can ? "클릭: 1점 투자" : ""));
            if (can) { ctx.fill(tx + 8, ty - 13, tx + 15, ty - 6, 0xFF3AA04A); ctx.drawText(tr, "+", tx + 9, ty - 13, 0xFFFFFFFF, false); }
        }
        ctx.drawText(tr, "특성 점수 " + tp, 250, 94, tp > 0 ? 0xFF7CE07C : 0xFFAAAAAA, false);

        Ui.divider(ctx, 12, 112, PW - 24);
        // 스탯 그룹
        JsonArray stats = st.getAsJsonArray("stats");
        group(ctx, tr, "신체", new int[]{0, 1, 2}, stats, 20, 124, mx, my);
        group(ctx, tr, "무기", new int[]{6, 5, 4}, stats, 196, 124, mx, my);
        group(ctx, tr, "정신", new int[]{3}, stats, 20, 196, mx, my);
        group(ctx, tr, "직업", new int[]{7}, stats, 104, 196, mx, my);
        Ui.divider(ctx, 12, 272, PW - 24);
        // 아래 요약
        int pend = 0;
        for (JsonElement el : stats) pend += el.getAsJsonObject().get("pend").getAsInt();
        ctx.drawText(tr, "이번 파워 투자  " + GrowthClient.i("inv") + " / " + GrowthClient.i("per"), 20, 282, 0xFFE8DDBE, false);
        ctx.drawText(tr, "확정 대기  " + pend + "점", 20, 294, pend > 0 ? 0xFF7CE07C : 0xFFAAAAAA, false);
        ctx.drawText(tr, "소각 토큰  " + GrowthClient.i("burns"), 20, 306, 0xFFFF9A5A, false);
        ctx.drawText(tr, "↑ 가 뜬 스탯을 누르면 가방의 수련 도구로 1점 확정", 20, 324, 0xFF9AA8A0, false);
        ctx.drawText(tr, "배지 둘레 고리 = 다음 1점까지 경험치", 20, 336, 0xFF9AA8A0, false);
        if (!st.getAsJsonArray("pick").isEmpty()) {
            int px = 200, py = 286;
            ctx.fill(px, py, px + 140, py + 22, 0xFF8A6A20);
            ctx.fill(px + 1, py + 1, px + 139, py + 21, 0xFFC09A40);
            ctx.drawCenteredTextWithShadow(tr, "재능 카드 고르기!", px + 70, py + 7, 0xFFFFFFFF);
            hits.add(px, py, 140, 22, () -> client.setScreen(new TalentScreen(false)), null, null);
        }
    }

    private void group(DrawContext ctx, TextRenderer tr, String title, int[] idx, JsonArray stats, int x, int y, double mx, double my) {
        int sum = 0;
        for (int i : idx) sum += stats.get(i).getAsJsonObject().get("v").getAsInt();
        String t = title + " (" + sum + ")";
        ctx.drawText(tr, t, x + idx.length * 25 - tr.getWidth(t) / 2, y, 0xFFE8DDBE, false);
        for (int k = 0; k < idx.length; k++) {
            JsonObject s = stats.get(idx[k]).getAsJsonObject();
            badge(ctx, tr, s, x + 25 + k * 50, y + 30);
        }
    }

    private void badge(DrawContext ctx, TextRenderer tr, JsonObject s, int cx, int cy) {
        int v = s.get("v").getAsInt(), pend = s.get("pend").getAsInt(), tools = s.get("tools").getAsInt();
        double x = s.get("x").getAsDouble(), need = s.get("need").getAsDouble();
        Ui.circle(ctx, cx, cy, 16, Ui.INK);
        Ui.ring(ctx, cx, cy, 12, 16, Ui.BRONZE_D, 0, 1);
        Ui.ring(ctx, cx, cy, 12, 16, pend > 0 ? 0xFF7CE07C : Ui.GOLD, 0, (float) Math.min(1, x / Math.max(1, need)));
        Ui.circle(ctx, cx, cy, 11, Ui.CREAM);
        Ui.circle(ctx, cx, cy, 9, 0xFF2A2A22);
        String vs = String.valueOf(v);
        ctx.drawText(tr, vs, cx - tr.getWidth(vs) / 2, cy - 4, 0xFFFFFFFF, true);
        String name = s.get("name").getAsString();
        var m = ctx.getMatrices();
        m.push(); m.translate(cx, cy + 19, 0); m.scale(0.75f, 0.75f, 1);
        Ui.wrapCentered(ctx, tr, name, 0, 0, 60, 0xFFE8DDBE, 2);
        m.pop();
        Runnable confirm = null;
        if (pend > 0) {
            boolean ok = tools > 0;
            ctx.fill(cx + 9, cy - 17, cx + 19, cy - 7, Ui.INK);
            ctx.fill(cx + 10, cy - 16, cx + 18, cy - 8, ok ? 0xFF3AA04A : 0xFF6A6A4A);
            ctx.drawText(tr, "↑", cx + 11, cy - 16, 0xFFFFFFFF, false);
            if (pend > 1) ctx.drawText(tr, String.valueOf(pend), cx + 20, cy - 16, 0xFF7CE07C, true);
            if (ok) confirm = () -> GrowthClient.send(GrowthActionPayload.CONFIRM, s.get("id").getAsString());
        }
        hits.add(cx - 16, cy - 16, 32, 32, confirm, null, List.of(
                name + " " + v + (pend > 0 ? "  (+" + pend + " 확정 대기)" : ""),
                "경험치 " + (int) x + " / " + (int) need,
                "쌓는 법: " + s.get("how").getAsString(),
                "확정 도구: " + s.get("tool").getAsString() + " (가방에 " + tools + "개)",
                pend > 0 ? (tools > 0 ? "클릭: 1점 확정" : "도구가 없습니다 (조합)") : ""));
    }

    // ═════════ 재능 카드 ═════════

    private void drawCards(DrawContext ctx, TextRenderer tr, double mx, double my) {
        JsonArray cards = GrowthClient.state.getAsJsonArray("cards");
        // 왼쪽 목록 (등급별)
        Ui.frame(ctx, 10, 10, 150, PH - 20, Ui.TEAL_D);
        int y = 20;
        String[] heads = {"전설", "희귀", "일반"};
        int[] order = {2, 1, 0};
        List<Integer> flat = new ArrayList<>();
        for (int g = 0; g < 3; g++) {
            boolean any = false;
            for (int i = 0; i < cards.size(); i++) if (cards.get(i).getAsJsonObject().get("rarity").getAsInt() == order[g]) any = true;
            if (!any) continue;
            ctx.drawText(tr, heads[g], 18, y, 0xFFE8DDBE, false);
            ctx.fill(18 + tr.getWidth(heads[g]) + 4, y + 4, 150, y + 5, 0x88CFC3A0);
            y += 12;
            for (int i = 0; i < cards.size(); i++) {
                JsonObject c = cards.get(i).getAsJsonObject();
                if (c.get("rarity").getAsInt() != order[g]) continue;
                boolean sel = flat.size() == selCard;
                int fi = flat.size();
                flat.add(i);
                ctx.drawText(tr, (sel ? "› " : "  ") + c.get("name").getAsString(), 22, y, sel ? 0xFFFFFFFF : rarityCol(order[g]), false);
                hits.add(18, y - 1, 136, 11, () -> selCard = fi, null, null);
                y += 12;
            }
            y += 4;
        }
        if (cards.isEmpty()) ctx.drawText(tr, "아직 카드가 없습니다", 20, 24, 0xFFAAAAAA, false);
        // 오른쪽: 고른 카드 크게
        if (!flat.isEmpty()) {
            JsonObject c = cards.get(flat.get(Math.min(selCard, flat.size() - 1))).getAsJsonObject();
            TalentScreen.drawCard(ctx, tr, c, 190, 40, 1.15f, false, false, 1f);
        }
        if (!GrowthClient.state.getAsJsonArray("pick").isEmpty()) {
            int px = 196, py = PH - 40;
            ctx.fill(px, py, px + 140, py + 22, 0xFF8A6A20);
            ctx.fill(px + 1, py + 1, px + 139, py + 21, 0xFFC09A40);
            ctx.drawCenteredTextWithShadow(tr, "재능 카드 고르기!", px + 70, py + 7, 0xFFFFFFFF);
            hits.add(px, py, 140, 22, () -> client.setScreen(new TalentScreen(false)), null, null);
        }
    }

    static int rarityCol(int r) { return r == 2 ? 0xFFF0C050 : r == 1 ? 0xFF7FD8F0 : 0xFFD8D8D8; }

    // ═════════ 숙련 ═════════

    private void drawMastery(DrawContext ctx, TextRenderer tr) {
        JsonArray ms = GrowthClient.state.getAsJsonArray("mastery");
        ctx.drawText(tr, "기술 숙련 — 쓸 때마다 +2%, 100% 면 ★숙달 (재사용·비용 −20%)", 16, 16, 0xFFE8DDBE, false);
        Ui.divider(ctx, 12, 30, PW - 24);
        if (ms.isEmpty()) { ctx.drawText(tr, "직업 기술이 없습니다", 20, 44, 0xFFAAAAAA, false); return; }
        for (int i = 0; i < ms.size(); i++) {
            JsonObject o = ms.get(i).getAsJsonObject();
            double v = o.get("m").getAsDouble();
            int y = 42 + i * 26;
            boolean done = v >= 100;
            ctx.drawText(tr, o.get("name").getAsString() + (done ? "  ★숙달" : ""), 20, y, done ? 0xFFF0C050 : 0xFFFFFFFF, false);
            int bx = 20, by = y + 11, bw = 300;
            ctx.fill(bx - 1, by - 1, bx + bw + 1, by + 6, Ui.INK);
            ctx.fill(bx, by, bx + bw, by + 5, 0xFF2A2A20);
            ctx.fill(bx, by, bx + (int) (bw * Math.min(1, v / 100)), by + 5, done ? 0xFFF0C050 : 0xFF7FB0D0);
            ctx.drawText(tr, (int) v + "%", bx + bw - 24, y, 0xFFAAAAAA, false);
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
