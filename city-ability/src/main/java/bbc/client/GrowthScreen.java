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
 * 성장 창 (K). Deepwoken 캐릭터 창 참고: 청동 테두리의 어두운 청록 판, 위 가운데 이름판, 왼쪽 동그란 탭,
 * 큰 파워 메달(둘레 홈 = 존재치 진행), 이름·종족·직업, 특성 배지 4개, 스탯 배지(둘레 홈 = 다음 1점 경험치,
 * 확정 대기는 ↑ — 누르면 가방의 수련 도구로 확정, 배지 오른쪽 아래 = 그 스탯의 수련 도구).
 * 탭: 상태 / 재능 카드 / 기술 숙련. 텍스처는 tools/make_ui_textures.py
 */
public class GrowthScreen extends Screen {
    private static final int PW = 360, PH = 400, PM = 8, TABW = 36;
    private int tab = 0;
    private int selCard = 0;
    private final Ui.Hits hits = new Ui.Hits();
    private float sc = 1, ox, oy;
    private final long openedAt = System.currentTimeMillis();

    public GrowthScreen() { super(Text.literal("성장")); }

    @Override
    protected void init() { GrowthClient.send(GrowthActionPayload.SYNC, ""); }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, width, height, 0x60000000);
        Ui.tex(ctx, "vignette", 0, 0, width, height, 0.8f);
    }

    private float clock() { return (System.currentTimeMillis() - openedAt) / 50f; }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderBackground(ctx, mouseX, mouseY, delta);
        sc = Math.min(width * 0.94f / (PW + TABW + 2 * PM), height * 0.94f / (PH + 2 * PM + 6));
        ox = (width - (PW + TABW) * sc) / 2f + TABW * sc; oy = (height - PH * sc) / 2f + 3 * sc;
        double mx = (mouseX - ox) / sc, my = (mouseY - oy) / sc;
        hits.clear();
        var m = ctx.getMatrices();
        m.push();
        m.translate(ox, oy, 0);
        m.scale(sc, sc, 1);
        TextRenderer tr = textRenderer;
        // 판 + 이름판
        Ui.tex(ctx, "panel", -PM, -PM, PW + 2 * PM, PH + 2 * PM);
        Ui.tex(ctx, "plaque", PW / 2f - 65, -14, 130, 24);
        String[] names = {"상태", "재능 카드", "기술 숙련"};
        Ui.text(ctx, tr, names[tab], PW / 2f, -6, 1.15f, 0xFFF0E6C8, true);
        // 왼쪽 탭
        ItemStack[] icons = {new ItemStack(Items.NETHER_STAR), new ItemStack(Items.PAPER), new ItemStack(Items.WRITABLE_BOOK)};
        for (int i = 0; i < 3; i++) {
            float tx = -TABW - 2, ty = 14 + i * 34;
            boolean on = tab == i, hov = Ui.Hits.inside(mx, my, tx, ty, 28, 28);
            Ui.tex(ctx, on ? "tab_on" : "tab_off", tx, ty, 28, 28);
            Ui.item(ctx, icons[i], tx + 14, ty + 14, hov || on ? 1.0f : 0.85f);
            final int t = i;
            hits.add((int) tx, (int) ty, 28, 28, () -> tab = t, null, List.of(names[i]));
        }
        if (GrowthClient.state.size() == 0) Ui.text(ctx, tr, "불러오는 중...", PW / 2f, PH / 2f, 1f, 0xFFFFFFFF, true);
        else if (tab == 0) drawStatus(ctx, tr, mx, my);
        else if (tab == 1) drawCards(ctx, tr, mx, my);
        else drawMastery(ctx, tr);
        m.pop();
        List<String> tip = hits.tip(mx, my);
        if (tip != null && !tip.isEmpty()) {
            List<Text> lines = new ArrayList<>();
            for (int i = 0; i < tip.size(); i++) if (!tip.get(i).isEmpty()) lines.add(i == 0 ? Text.literal(tip.get(i)).styled(s -> s.withColor(0xF0D890)) : Text.literal(tip.get(i)));
            ctx.drawTooltip(tr, lines, mouseX, mouseY);
        }
    }

    // ═════════ 상태 ═════════

    private void drawStatus(DrawContext ctx, TextRenderer tr, double mx, double my) {
        JsonObject st = GrowthClient.state;
        int pw = GrowthClient.i("power");
        // ── 파워 메달
        float cx = 54, cy = 58;
        Ui.tex(ctx, "medallion", cx - 40, cy - 40, 80, 80);
        double ep = GrowthClient.d("ep"), from = GrowthClient.d("epFrom"), to = GrowthClient.d("epTo");
        float frac = (float) Math.max(0, Math.min(1, (ep - from) / Math.max(1, to - from)));
        if (frac > 0) {
            Ui.ring(ctx, cx, cy, 23.6f, 28.4f, 0x40F0D070, 0, frac);
            Ui.ring(ctx, cx, cy, 24.6f, 27.4f, 0xFFF0CE6A, 0, frac);
        }
        String n = String.valueOf(pw);
        Ui.text(ctx, tr, n, cx, cy - 13, n.length() > 1 ? 2.4f : 2.8f, 0xFFFFFFFF, true);
        Ui.text(ctx, tr, "/ " + GrowthClient.i("maxPower"), cx, cy + 12, 0.7f, 0xFF9FC0CC, false);
        Ui.text(ctx, tr, "파워", cx, cy + 43, 0.9f, 0xFFE8DDBE, true);
        hits.add((int) cx - 34, (int) cy - 34, 68, 68, null, null, List.of("파워 " + pw + " / " + GrowthClient.i("maxPower"),
                "존재치 " + (long) ep + " / " + (long) to, "다음 파워: 스탯 " + GrowthClient.i("per") + "점 확정 + 존재치 " + (long) to,
                "존재치: 몬스터 처치 · 새 바이옴 발견"));
        // ── 이름·종족·직업
        float lx = 104, ly = 18;
        var m = ctx.getMatrices();
        m.push(); m.translate(lx, ly, 0); m.scale(1.4f, 1.4f, 1);
        ctx.drawText(tr, GrowthClient.s("name"), 0, 0, 0xFFF4EAD0, true);
        m.pop();
        JsonObject race = st.getAsJsonObject("race");
        String rn = race.get("name").getAsString() + (race.get("sub").getAsString().isEmpty() ? "" : " (" + race.get("sub").getAsString() + ")");
        float rw = tag(ctx, tr, lx, ly + 18, "종족", rn);
        hits.add((int) lx, (int) ly + 18, (int) rw, 13, null, null, List.of(rn, "장점: " + race.get("good").getAsString(), "약점: " + race.get("bad").getAsString()));
        if (!race.get("rerolled").getAsBoolean()) {
            float bx = lx + rw + 4;
            boolean hov = Ui.Hits.inside(mx, my, bx, ly + 18, 40, 13);
            Ui.button(ctx, tr, "red", bx, ly + 18, 40, 13, "다시", hov);
            hits.add((int) bx, (int) ly + 18, 40, 13, () -> GrowthClient.send(GrowthActionPayload.REROLL, ""), null, List.of("종족 다시 굴리기", "한 번만 무료"));
        }
        tag(ctx, tr, lx, ly + 34, "직업", GrowthClient.s("job"));
        // ── 존재치 막대
        float bx = lx - 4, by = ly + 54, bw = 154;
        Ui.slice3(ctx, "bar", 160, 10, 8, bx, by, bw, 10, 1f);
        int fx0 = (int) (bx + 6), fx1 = (int) (bx + 6 + (bw - 12) * frac);
        if (fx1 > fx0) {
            ctx.fill(fx0, (int) by + 3, fx1, (int) by + 7, 0xFFD8B050);
            ctx.fill(fx0, (int) by + 3, fx1, (int) by + 4, 0xFFFFE89A);
        }
        hits.add((int) bx, (int) by, (int) bw, 10, null, null, List.of("존재치 " + (long) ep + " / " + (long) to, "몬스터 처치 · 새 바이옴 발견으로 쌓임"));
        ctx.drawText(tr, "존재치", (int) lx, (int) by + 13, 0xFFBFB28E, false);
        String es = (long) ep + " / " + (long) to;
        ctx.drawText(tr, es, (int) (bx + bw - 4 - tr.getWidth(es)), (int) by + 13, 0xFFE8DDBE, false);
        int inj = GrowthClient.i("injury");
        if (inj > 0) {
            ctx.drawText(tr, "부상  " + inj / 60 + "분 " + inj % 60 + "초", (int) lx, (int) by + 25, 0xFFFF6A5A, true);
            hits.add((int) lx, (int) by + 24, 100, 10, null, null, List.of("부상", "전투 중 도주한 대가: 최대 체력 −15%"));
        }
        // ── 특성 4개
        JsonArray traits = st.getAsJsonArray("traits");
        int tp = GrowthClient.i("tp");
        for (int i = 0; i < traits.size(); i++) {
            JsonObject t = traits.get(i).getAsJsonObject();
            float tx = 280 + (i % 2) * 44, ty = 40 + (i / 2) * 44;
            Ui.tex(ctx, "badge_trait", tx - 16, ty - 16, 32, 32);
            int v = t.get("v").getAsInt();
            for (int k = 0; k < 6; k++) {   // 6칸 점
                double a = Math.PI * 2 * (k + 0.5) / 6 - Math.PI / 2 + Math.PI;
                float px = tx + (float) Math.cos(a) * 9, py = ty + (float) Math.sin(a) * 9;
                Ui.diamond(ctx, px, py, 1.6f, k < v ? 0xFFF0CE6A : 0xFF4A443A);
            }
            Ui.text(ctx, tr, String.valueOf(v), tx, ty - 4, 1f, 0xFFFFFFFF, true);
            String tn = t.get("name").getAsString();
            Ui.text(ctx, tr, tn, tx, ty + 17, 0.75f, 0xFFE8DDBE, false);
            final int idx = i;
            boolean can = tp > 0 && v < 6;
            hits.add((int) tx - 15, (int) ty - 15, 30, 30, can ? () -> GrowthClient.send(GrowthActionPayload.TRAIT, String.valueOf(idx)) : null, null,
                    List.of(tn + "  " + v + " / 6", t.get("desc").getAsString() + " (점당)", can ? "클릭: 1점 투자" : ""));
            if (can) plus(ctx, tr, tx + 12, ty - 12);
        }
        Ui.text(ctx, tr, "특성 점수  " + tp, 302, 110, 0.85f, tp > 0 ? 0xFF8CE88C : 0xFF8A9490, true);

        Ui.ornament(ctx, PW / 2f, 122, 320);
        // ── 스탯
        JsonArray stats = st.getAsJsonArray("stats");
        group(ctx, tr, "신체", new int[]{0, 1, 2}, stats, 96, 136, mx, my);
        group(ctx, tr, "무기", new int[]{4, 5, 6}, stats, 264, 136, mx, my);
        group(ctx, tr, "정신", new int[]{3}, stats, 54, 216, mx, my);
        group(ctx, tr, "직업", new int[]{7}, stats, 140, 216, mx, my);
        // ── 오른쪽 아래 요약 상자
        int pend = 0;
        for (JsonElement el : stats) pend += el.getAsJsonObject().get("pend").getAsInt();
        int sx = 190, sy = 210, sw = 150, sh = 84;
        ctx.fill(sx, sy, sx + sw, sy + sh, 0x70061010);
        ctx.fill(sx, sy, sx + sw, sy + 1, 0x80CFC3A0); ctx.fill(sx, sy + sh - 1, sx + sw, sy + sh, 0x80CFC3A0);
        ctx.fill(sx, sy, sx + 1, sy + sh, 0x80CFC3A0); ctx.fill(sx + sw - 1, sy, sx + sw, sy + sh, 0x80CFC3A0);
        Ui.diamond(ctx, sx, sy, 2.5f, Ui.LINE); Ui.diamond(ctx, sx + sw, sy, 2.5f, Ui.LINE);
        Ui.diamond(ctx, sx, sy + sh, 2.5f, Ui.LINE); Ui.diamond(ctx, sx + sw, sy + sh, 2.5f, Ui.LINE);
        int inv = GrowthClient.i("inv"), per = GrowthClient.i("per");
        ctx.drawText(tr, "이번 파워 투자", sx + 8, sy + 8, 0xFFBFB28E, false);
        String ivs = inv + " / " + per;
        ctx.drawText(tr, ivs, sx + sw - 8 - tr.getWidth(ivs), sy + 8, inv >= per ? 0xFFF0CE6A : 0xFFE8DDBE, false);
        for (int k = 0; k < per && per <= 15; k++) {   // 투자 칸
            int px = sx + 8 + k * 9;
            ctx.fill(px, sy + 20, px + 7, sy + 24, 0xFF101414);
            if (k < inv) ctx.fill(px, sy + 20, px + 7, sy + 24, 0xFFD8B050);
        }
        ctx.drawText(tr, "확정 대기", sx + 8, sy + 32, 0xFFBFB28E, false);
        String ps = pend + "점";
        ctx.drawText(tr, ps, sx + sw - 8 - tr.getWidth(ps), sy + 32, pend > 0 ? 0xFF8CE88C : 0xFF8A9490, false);
        ctx.drawText(tr, "소각 토큰", sx + 8, sy + 46, 0xFFBFB28E, false);
        int burns = GrowthClient.i("burns");
        for (int k = 0; k < 3; k++) {
            float ix = sx + sw - 14 - (2 - k) * 13;
            Ui.circle(ctx, ix, sy + 50, 5.5f, 0xFF1A1410);
            if (k < burns) Ui.item(ctx, new ItemStack(Items.BLAZE_POWDER), ix, sy + 50, 0.55f);
        }
        ctx.drawText(tr, "재능 카드", sx + 8, sy + 62, 0xFFBFB28E, false);
        String cs = st.getAsJsonArray("cards").size() + "장";
        ctx.drawText(tr, cs, sx + sw - 8 - tr.getWidth(cs), sy + 62, 0xFFE8DDBE, false);

        Ui.ornament(ctx, PW / 2f, 306, 320);
        // ── 아래: 안내 + 카드 고르기
        ctx.drawText(tr, "↑ 가 뜬 스탯을 누르면", 20, 322, 0xFF9AA8A0, false);
        ctx.drawText(tr, "가방의 수련 도구로 1점 확정", 20, 334, 0xFF9AA8A0, false);
        ctx.drawText(tr, "배지 둘레 = 다음 1점 경험치", 20, 350, 0xFF7E8C86, false);
        ctx.drawText(tr, "도구는 조합 · 여러 번 사용 (내구도)", 20, 362, 0xFF7E8C86, false);
        if (!st.getAsJsonArray("pick").isEmpty()) pickButton(ctx, tr, 196, 326, mx, my);
    }

    /** 크림색 이름표 (청록 단추 바탕) — 너비를 돌려줌 */
    private float tag(DrawContext ctx, TextRenderer tr, float x, float y, String label, String value) {
        float w = (tr.getWidth(label) + 6 + tr.getWidth(value)) * 0.85f + 14;
        Ui.slice3(ctx, "btn_teal", 40, 22, 8, x, y, w, 13, 1f);
        var m = ctx.getMatrices();
        m.push(); m.translate(x + 7, y + 3, 0); m.scale(0.85f, 0.85f, 1);
        ctx.drawText(tr, label, 0, 0, 0xFF9FC0B8, false);
        ctx.drawText(tr, value, tr.getWidth(label) + 6, 0, 0xFFF4EAD0, false);
        m.pop();
        return w;
    }

    private void plus(DrawContext ctx, TextRenderer tr, float x, float y) {
        float p = 0.8f + 0.2f * (float) Math.sin(clock() * 0.3);
        Ui.circle(ctx, x, y, 5.5f, 0xFF102010);
        Ui.circle(ctx, x, y, 4.5f, Ui.alpha(0xFF46B858, p));
        ctx.fill((int) x - 2, (int) y, (int) x + 3, (int) y + 1, 0xFFFFFFFF);
        ctx.fill((int) x, (int) y - 2, (int) x + 1, (int) y + 3, 0xFFFFFFFF);
    }

    private void pickButton(DrawContext ctx, TextRenderer tr, float x, float y, double mx, double my) {
        float w = 144, h = 24;
        float p = 0.5f + 0.5f * (float) Math.sin(clock() * 0.25);
        ctx.fill((int) x - 3, (int) y - 3, (int) (x + w) + 3, (int) (y + h) + 3, Ui.alpha(0xFFF0C860, 0.15f + 0.2f * p));
        boolean hov = Ui.Hits.inside(mx, my, x, y, w, h);
        Ui.button(ctx, tr, "gold", x, y, w, h, "재능 카드 고르기!", hov);
        hits.add((int) x, (int) y, (int) w, (int) h, () -> client.setScreen(new TalentScreen(false)), null, List.of("고르지 않은 재능 카드가 있습니다"));
    }

    private void group(DrawContext ctx, TextRenderer tr, String title, int[] idx, JsonArray stats, float cx, float y, double mx, double my) {
        int sum = 0;
        for (int i : idx) sum += stats.get(i).getAsJsonObject().get("v").getAsInt();
        String t = title + "  " + sum;
        int tw = tr.getWidth(t);
        ctx.drawText(tr, t, (int) (cx - tw / 2f), (int) y, 0xFFE8DDBE, true);
        float lw = idx.length > 1 ? 54 : 10;
        ctx.fill((int) (cx - tw / 2f - 6 - lw), (int) y + 4, (int) (cx - tw / 2f - 6), (int) y + 5, 0x80CFC3A0);
        ctx.fill((int) (cx + tw / 2f + 6), (int) y + 4, (int) (cx + tw / 2f + 6 + lw), (int) y + 5, 0x80CFC3A0);
        for (int k = 0; k < idx.length; k++) {
            JsonObject s = stats.get(idx[k]).getAsJsonObject();
            badge(ctx, tr, s, idx[k], cx + (k - (idx.length - 1) / 2f) * 54, y + 32, mx, my);
        }
    }

    private void badge(DrawContext ctx, TextRenderer tr, JsonObject s, int statIdx, float cx, float cy, double mx, double my) {
        int v = s.get("v").getAsInt(), pend = s.get("pend").getAsInt(), tools = s.get("tools").getAsInt();
        double x = s.get("x").getAsDouble(), need = s.get("need").getAsDouble();
        boolean hov = Ui.Hits.inside(mx, my, cx - 18, cy - 18, 36, 36);
        Ui.tex(ctx, "badge", cx - 20, cy - 20, 40, 40);
        float fr = (float) Math.min(1, x / Math.max(1, need));
        if (fr > 0) Ui.ring(ctx, cx, cy, 12.4f, 15.2f, pend > 0 ? 0xFF7CE07C : 0xFFF0CE6A, 0, fr);
        if (hov) Ui.circle(ctx, cx, cy, 10.2f, 0x30FFFFFF);
        Ui.text(ctx, tr, String.valueOf(v), cx, cy - 4, 1f, 0xFFFFFFFF, true);
        String name = s.get("name").getAsString();
        var m = ctx.getMatrices();
        m.push(); m.translate(cx, cy + 22, 0); m.scale(0.72f, 0.72f, 1);
        Ui.wrapCentered(ctx, tr, name, 0, 0, 64, 0xFFE8DDBE, 2);
        m.pop();
        // 수련 도구 (오른쪽 아래): 있으면 밝게
        Ui.item(ctx, Ui.toolStack(statIdx), cx + 14, cy + 13, 0.62f);
        if (tools <= 0) ctx.fill((int) cx + 9, (int) cy + 8, (int) cx + 19, (int) cy + 18, 0x90101010);
        Runnable confirm = null;
        if (pend > 0) {
            boolean ok = tools > 0;
            float bob = (float) Math.sin(clock() * 0.3) * 1.2f;
            Ui.circle(ctx, cx + 13, cy - 13 + bob, 6f, 0xFF0C140C);
            Ui.circle(ctx, cx + 13, cy - 13 + bob, 5f, ok ? 0xFF46B858 : 0xFF6A6A4A);
            Ui.text(ctx, tr, "↑", cx + 13.5f, cy - 17 + bob, 1f, 0xFFFFFFFF, false);
            if (pend > 1) ctx.drawText(tr, "×" + pend, (int) cx + 20, (int) (cy - 17 + bob), 0xFF8CE88C, true);
            if (ok) confirm = () -> GrowthClient.send(GrowthActionPayload.CONFIRM, s.get("id").getAsString());
        }
        hits.add((int) cx - 18, (int) cy - 18, 36, 36, confirm, null, List.of(
                name + "  " + v + (pend > 0 ? "   (+" + pend + " 확정 대기)" : ""),
                "경험치 " + (int) x + " / " + (int) need,
                "쌓는 법: " + s.get("how").getAsString(),
                "확정 도구: " + s.get("tool").getAsString() + (tools > 0 ? " (남은 " + tools + "회)" : " (없음)"),
                pend > 0 ? (tools > 0 ? "클릭: 1점 확정" : "도구를 조합하세요") : ""));
    }

    // ═════════ 재능 카드 ═════════

    private void drawCards(DrawContext ctx, TextRenderer tr, double mx, double my) {
        JsonArray cards = GrowthClient.state.getAsJsonArray("cards");
        // 왼쪽 목록 (등급별)
        int lx = 16, ly = 18, lw = 156, lh = PH - 36;
        ctx.fill(lx, ly, lx + lw, ly + lh, 0x70061010);
        ctx.fill(lx, ly, lx + lw, ly + 1, 0x80CFC3A0); ctx.fill(lx, ly + lh - 1, lx + lw, ly + lh, 0x80CFC3A0);
        ctx.fill(lx, ly, lx + 1, ly + lh, 0x80CFC3A0); ctx.fill(lx + lw - 1, ly, lx + lw, ly + lh, 0x80CFC3A0);
        Ui.text(ctx, tr, "보유 카드 " + cards.size() + "장", lx + lw / 2f, ly + 8, 0.9f, 0xFFE8DDBE, true);
        Ui.ornament(ctx, lx + lw / 2f, ly + 22, lw - 16);
        int y = ly + 32;
        String[] heads = {"전설", "희귀", "일반"};
        int[] order = {2, 1, 0};
        List<Integer> flat = new ArrayList<>();
        for (int g = 0; g < 3; g++) {
            boolean any = false;
            for (int i = 0; i < cards.size(); i++) if (cards.get(i).getAsJsonObject().get("rarity").getAsInt() == order[g]) any = true;
            if (!any) continue;
            ctx.drawText(tr, heads[g], lx + 8, y, rarityCol(order[g]), true);
            ctx.fill(lx + 12 + tr.getWidth(heads[g]), y + 4, lx + lw - 8, y + 5, 0x60CFC3A0);
            y += 13;
            for (int i = 0; i < cards.size(); i++) {
                JsonObject c = cards.get(i).getAsJsonObject();
                if (c.get("rarity").getAsInt() != order[g]) continue;
                boolean sel = flat.size() == selCard;
                int fi = flat.size();
                flat.add(i);
                boolean hov = Ui.Hits.inside(mx, my, lx + 4, y - 2, lw - 8, 13);
                if (sel) Ui.tex(ctx, "row_sel", lx + 4, y - 2, lw - 8, 13);
                else if (hov) ctx.fill(lx + 4, y - 2, lx + lw - 4, y + 11, 0x20FFFFFF);
                Ui.tex(ctx, "gem_" + order[g], lx + 8, y, 9, 9);
                ctx.drawText(tr, c.get("name").getAsString(), lx + 21, y + 1, sel ? 0xFF1A1208 : 0xFFE8DDBE, false);
                hits.add(lx + 4, y - 2, lw - 8, 13, () -> selCard = fi, null, null);
                y += 14;
            }
            y += 6;
        }
        if (cards.isEmpty()) {
            Ui.text(ctx, tr, "아직 카드가 없습니다", lx + lw / 2f, ly + 40, 0.85f, 0xFF8A9490, false);
            Ui.text(ctx, tr, "파워가 오르면 고를 수 있어요", lx + lw / 2f, ly + 52, 0.75f, 0xFF7E8C86, false);
        }
        // 오른쪽: 고른 카드 크게
        float ccx = 266;
        if (!flat.isEmpty()) {
            JsonObject c = cards.get(flat.get(Math.min(selCard, flat.size() - 1))).getAsJsonObject();
            var m = ctx.getMatrices();
            m.push();
            m.translate(ccx - TalentScreen.CW * 1.3f / 2, 36, 0);
            m.scale(1.3f, 1.3f, 1);
            TalentScreen.drawCard(ctx, tr, c, 0, 0, false, false, 1f);
            m.pop();
        } else {
            var m = ctx.getMatrices();
            m.push();
            m.translate(ccx - TalentScreen.CW * 1.3f / 2, 36, 0);
            m.scale(1.3f, 1.3f, 1);
            TalentScreen.drawBack(ctx, 0, 0, 0.6f);
            m.pop();
        }
        if (!GrowthClient.state.getAsJsonArray("pick").isEmpty()) pickButton(ctx, tr, ccx - 72, PH - 52, mx, my);
    }

    static int rarityCol(int r) { return r == 2 ? 0xFFF0C050 : r == 1 ? 0xFF7FD8F0 : 0xFFD8C8A8; }

    // ═════════ 숙련 ═════════

    private void drawMastery(DrawContext ctx, TextRenderer tr) {
        JsonArray ms = GrowthClient.state.getAsJsonArray("mastery");
        Ui.text(ctx, tr, "쓸 때마다 +2%  ·  100% 면 ★숙달 (재사용 대기·비용 −20%)", PW / 2f, 22, 0.85f, 0xFFBFB28E, false);
        Ui.ornament(ctx, PW / 2f, 38, 320);
        if (ms.isEmpty()) { Ui.text(ctx, tr, "직업 기술이 없습니다", PW / 2f, 60, 0.9f, 0xFF8A9490, false); return; }
        for (int i = 0; i < ms.size(); i++) {
            JsonObject o = ms.get(i).getAsJsonObject();
            double v = o.get("m").getAsDouble();
            int y = 54 + i * 34;
            boolean done = v >= 100;
            // 번호 메달
            Ui.tex(ctx, "badge_trait", 18, y - 2, 22, 22);
            Ui.text(ctx, tr, done ? "★" : String.valueOf(i + 1), 29, y + 5, 0.9f, done ? 0xFFF0C850 : 0xFFFFFFFF, true);
            ctx.drawText(tr, o.get("name").getAsString(), 48, y, done ? 0xFFF0C850 : 0xFFF4EAD0, true);
            String ps = done ? "숙달" : (int) v + "%";
            ctx.drawText(tr, ps, PW - 22 - tr.getWidth(ps), y, done ? 0xFFF0C850 : 0xFFBFB28E, false);
            float bx = 44, by = y + 11, bw = PW - 62;
            Ui.slice3(ctx, "bar", 160, 10, 8, bx, by, bw, 10, 1f);
            int fx0 = (int) (bx + 6), fx1 = (int) (bx + 6 + (bw - 12) * Math.min(1, v / 100));
            if (fx1 > fx0) {
                ctx.fill(fx0, (int) by + 3, fx1, (int) by + 7, done ? 0xFFE0B040 : 0xFF6FA8C8);
                ctx.fill(fx0, (int) by + 3, fx1, (int) by + 4, done ? 0xFFFFE89A : 0xFFA8D8F0);
            }
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
