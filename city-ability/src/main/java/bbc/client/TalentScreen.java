package bbc.client;

import bbc.net.GrowthActionPayload;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 재능 카드 고르기 (Deepwoken 처럼 파워가 오르면 바로 뜸).
 *  1) '파워 상승' 문양이 뜨고 카드 뒷면이 차례로 깔림  2) 한 장씩 뒤집혀 앞면
 *  왼쪽 클릭 = 고르기 (카드가 커지며 빛나고 창이 닫힘), 오른쪽 클릭 또는 '소각' 모드에서 클릭 = 태우기
 *  (아래부터 불타 사라지고 남은 카드가 가운데로 모임. 토큰 1, 앞으로 다시 안 나옴, 마지막 한 장은 못 태움)
 *  ESC 로 닫으면 나중에 성장 창(K)에서 다시 고를 수 있음
 * 카드 그림·틀은 tools/make_ui_textures.py (card_0~2, card_back, art/카드id)
 */
public class TalentScreen extends Screen {
    static final int CW = 104, CH = 162, GAP = 14, M = 6;
    private final long openedAt = System.currentTimeMillis();
    private final boolean fresh;
    private boolean burnMode;
    private final Ui.Hits hits = new Ui.Hits();
    private float sc = 1, ox, oy;
    private int vw;
    /** 카드별 현재 x (태운 뒤 남은 카드가 미끄러지듯 모이게) */
    private final Map<String, Float> posX = new HashMap<>();
    /** 태우는 중인 카드 (서버가 지운 뒤에도 불타는 모습을 그림) */
    private record Burning(JsonObject card, float x, long at) {}
    private final List<Burning> burning = new ArrayList<>();
    private final Set<String> burnedIds = new HashSet<>();
    /** 고른 카드: 이 시간부터 연출 후 닫힘 */
    private JsonObject chosen;
    private float chosenX;
    private long chosenAt = -1;
    private JsonArray snapshot = new JsonArray();

    /** fresh = 방금 파워가 올라서 뜬 것 (문양·뒤집기 연출) */
    public TalentScreen(boolean fresh) { super(Text.literal("재능 카드")); this.fresh = fresh; }

    /** 연출 중이면 서버가 후보를 비워도 바로 닫지 않음 */
    boolean busy() { return chosenAt >= 0; }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, width, height, 0x70081010);
        Ui.tex(ctx, "vignette", 0, 0, width, height, 1f);
    }

    private static float now() { return System.currentTimeMillis() / 50f; }
    private float t() { return (System.currentTimeMillis() - openedAt) / 50f; }   // 틱

    private static float ease(float x) { x = Math.max(0, Math.min(1, x)); return 1 - (1 - x) * (1 - x) * (1 - x); }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderBackground(ctx, mouseX, mouseY, delta);
        // 보여줄 카드: 고르는 연출 중이면 그때 모습 그대로, 아니면 서버 후보에서 태운 것 뺀 것
        JsonArray live = GrowthClient.state.has("pick") ? GrowthClient.state.getAsJsonArray("pick") : new JsonArray();
        if (chosenAt < 0) {
            JsonArray a = new JsonArray();
            for (var el : live) if (!burnedIds.contains(el.getAsJsonObject().get("id").getAsString())) a.add(el);
            snapshot = a;
        }
        JsonArray pick = snapshot;
        int n = Math.max(1, pick.size());
        vw = Math.max(n * (CW + GAP) + 60, 500);
        int vh = 320;
        sc = Math.min(width * 0.96f / vw, height * 0.94f / vh);
        ox = (width - vw * sc) / 2f; oy = (height - vh * sc) / 2f;
        double mx = (mouseX - ox) / sc, my = (mouseY - oy) / sc;
        hits.clear();
        var m = ctx.getMatrices();
        m.push();
        m.translate(ox, oy, 0);
        m.scale(sc, sc, 1);
        TextRenderer tr = textRenderer;
        float t = fresh ? t() : 100;
        float cx = vw / 2f;
        float fadeAll = chosenAt >= 0 ? Math.max(0, 1 - (now() - chosenAt) / 14f) : 1f;

        // ── 위 문구 + 장식 줄
        float ta = Math.min(1, t / 6f) * fadeAll;
        Ui.text(ctx, tr, "수련이 마침내 결실을 맺는다...", cx, 14, 1.1f, Ui.alpha(0xFFF0E6C8, ta), true);
        Ui.ornament(ctx, cx, 30, 240);
        // ── 파워 상승 문양 (처음 1.5초)
        if (t < 32) {
            float a = t < 4 ? t / 4 : t > 24 ? Math.max(0, (32 - t) / 8) : 1;
            float pop = 0.8f + 0.2f * ease(t / 6f) + (t > 24 ? (t - 24) * 0.01f : 0);
            float w = 220 * pop, h = 64 * pop;
            Ui.tex(ctx, "power_up", cx - w / 2, 92 - h / 2, w, h, a);
            Ui.text(ctx, tr, "파워 상승", cx, 126, 1.8f, Ui.alpha(0xFFFFF4D8, a), true);
            Ui.text(ctx, tr, "파워 " + GrowthClient.i("power"), cx, 146, 1f, Ui.alpha(0xFFE8D27A, a), true);
        }
        // ── 소각 토큰 (불꽃 3칸) + 소각 모드 단추
        int burns = GrowthClient.i("burns");
        float tokA = Math.min(1, Math.max(0, (t - 20) / 6f)) * fadeAll;
        if (tokA > 0) {
            float bx0 = cx - 150;
            Ui.text(ctx, tr, "소각", bx0 + 4, 44, 0.8f, Ui.alpha(0xFFFFB070, tokA), true);
            for (int i = 0; i < 3; i++) {
                float ix = bx0 - 14 + i * 14 + 22;
                Ui.circle(ctx, ix + 16, 60, 6.5f, Ui.alpha(0xFF1A1410, tokA));
                Ui.circle(ctx, ix + 16, 60, 5.5f, Ui.alpha(i < burns ? 0xFF5A2A14 : 0xFF2A2420, tokA));
                if (i < burns) Ui.item(ctx, new ItemStack(Items.BLAZE_POWDER), ix + 16, 60, 0.6f);
            }
            float bw = 86, bh = 16, bx = cx + 150 - bw, by = 52;
            boolean hov = Ui.Hits.inside(mx, my, bx, by, bw, bh);
            Ui.button(ctx, tr, burnMode ? "red" : "teal", bx, by, bw, bh, burnMode ? "소각 모드 켜짐" : "소각 모드", hov);
            if (chosenAt < 0) hits.add((int) bx, (int) by, (int) bw, (int) bh, () -> burnMode = !burnMode, null,
                    List.of("켜면 왼쪽 클릭도 태우기", "남은 소각 " + burns + "번 (파워가 오를 때마다 +1, 최대 3)"));
        }
        // ── 카드
        int total = pick.size() * CW + Math.max(0, pick.size() - 1) * GAP;
        float x0 = cx - total / 2f;
        int y0 = 112;
        boolean canBurn = burns > 0 && pick.size() > 1;
        for (int i = 0; i < pick.size(); i++) {
            JsonObject c = pick.get(i).getAsJsonObject();
            String id = c.get("id").getAsString();
            float target = x0 + i * (CW + GAP);
            float x = posX.containsKey(id) ? posX.get(id) + (target - posX.get(id)) * 0.25f : target;
            posX.put(id, x);
            float appear = t - 8 - i * 3;          // 뒷면이 깔리는 때
            float flip = t - 26 - i * 5;           // 뒤집히는 때
            if (appear < 0) continue;
            boolean isChosen = chosen != null && id.equals(chosen.get("id").getAsString());
            float a = chosen == null ? 1 : isChosen ? 1 : fadeAll;
            if (a <= 0.01f) continue;
            boolean hover = chosenAt < 0 && flip > 6 && Ui.Hits.inside(mx, my, x, y0 - 8, CW, CH + 8);
            float slide = ease(appear / 7f);
            float y = y0 + (hover ? -7 : 0) + (1 - slide) * 40;
            float s = 1;
            if (isChosen) {   // 고른 카드: 가운데로 오며 커짐
                float k = ease((now() - chosenAt) / 8f);
                x = chosenX + (cx - CW / 2f - chosenX) * k;
                y = y0 - 10 * k;
                s = 1 + 0.12f * k;
            }
            // 뒤집기: 가로로 줄었다가 앞면으로 펴짐
            float fx = flip < 0 ? 1 : flip < 3 ? 1 - flip / 3 : flip < 6 ? (flip - 3) / 3 : 1;
            boolean front = flip >= 3;
            m.push();
            m.translate(x + CW / 2f, y + CH / 2f, 0);
            m.scale(Math.max(0.02f, fx) * s, s, 1);
            m.translate(-CW / 2f, -CH / 2f, 0);
            if (front) drawCard(ctx, tr, c, 0, 0, hover && burnMode && canBurn, hover, a * slide);
            else drawBack(ctx, 0, 0, a * slide);
            if (isChosen) {   // 빛
                float k = (now() - chosenAt);
                float fl = Math.max(0, 1 - k / 10f);
                ctx.fill(0, 0, CW, CH, Ui.alpha(0xFFFFF4D0, fl * 0.7f));
            }
            m.pop();
            if (hover) {
                String hint = burnMode ? (canBurn ? "클릭: 태우기" : "태울 수 없음") : "왼쪽: 고르기   오른쪽: 태우기";
                Ui.text(ctx, tr, hint, x + CW / 2f, y0 + CH + 10, 0.75f, burnMode ? 0xFFFF9A6A : 0xFFE8DDBE, true);
            }
            if (flip > 6 && chosenAt < 0) {
                final float fxx = x;
                Runnable choose = () -> { chosen = c; chosenX = fxx; chosenAt = (long) now(); GrowthClient.send(GrowthActionPayload.CHOOSE, id); };
                Runnable burn = canBurn ? () -> {
                    burning.add(new Burning(c, fxx, (long) now()));
                    burnedIds.add(id);
                    burnMode = false;
                    GrowthClient.send(GrowthActionPayload.BURN, id);
                } : null;
                hits.add((int) x, y0 - 8, CW, CH + 8, burnMode ? burn : choose, burn, null);
            }
        }
        // ── 불타는 카드
        burning.removeIf(b -> now() - b.at > 18);
        for (Burning b : burning) drawBurning(ctx, tr, b.card, b.x, y0, (now() - b.at) / 18f);
        // ── 아래 안내
        float ha = Math.min(1, Math.max(0, (t - 40) / 8f)) * fadeAll;
        if (ha > 0) {
            Ui.ornament(ctx, cx, y0 + CH + 26, 300);
            Ui.text(ctx, tr, "왼쪽 클릭  고르기     ·     오른쪽 클릭 / 소각 모드  태우기 (다시는 안 나옴)     ·     ESC  나중에", cx, y0 + CH + 36, 0.8f, Ui.alpha(0xFFB8B0A0, ha), true);
        }
        m.pop();
        List<String> tip = hits.tip(mx, my);
        if (tip != null) ctx.drawTooltip(tr, tip.stream().map(Text::literal).map(x -> (Text) x).toList(), mouseX, mouseY);
        if (chosenAt >= 0 && now() - chosenAt > 16) close();
    }

    /** 불타는 카드: 아래에서 위로 타 들어가며 사라지고 불씨가 튐 */
    private void drawBurning(DrawContext ctx, TextRenderer tr, JsonObject c, float x, float y, float p) {
        var m = ctx.getMatrices();
        float line = CH * (1 - ease(p * 1.1f));        // 이 위만 남음
        float yy0 = y - p * 6;
        // 탄 부분은 잘라서 안 그림 (가위 영역은 화면 좌표)
        ctx.enableScissor((int) (ox + (x - M - 2) * sc), (int) (oy + (yy0 - M - 2) * sc), (int) (ox + (x + CW + M + 2) * sc), (int) (oy + (yy0 + line) * sc));
        m.push();
        m.translate(x, yy0, 0);
        drawCard(ctx, tr, c, 0, 0, true, false, Math.max(0, 1 - p * 0.6f));
        m.pop();
        ctx.disableScissor();
        m.push();
        m.translate(x, yy0, 0);
        for (int k = 0; k < 6; k++) {
            int yy = (int) line - k;
            ctx.fill(0, yy, CW, yy + 1, Ui.alpha(k < 2 ? 0xFFFFE070 : 0xFFFF6020, 1 - k / 6f));
        }
        ctx.fill(0, (int) line - 14, CW, (int) line - 6, 0x40FF4010);
        // 불씨
        Random r = new Random(c.get("id").getAsString().hashCode());
        for (int k = 0; k < 26; k++) {
            float ex = r.nextFloat() * CW, sp = 0.5f + r.nextFloat();
            float ey = line - (p * 60 * sp) % 50 - r.nextFloat() * 10;
            int col = r.nextBoolean() ? 0xFFFFC040 : 0xFFFF6A20;
            float sz = 1 + r.nextFloat() * 1.5f;
            ctx.fill((int) ex, (int) ey, (int) (ex + sz), (int) (ey + sz), Ui.alpha(col, 1 - p));
        }
        m.pop();
    }

    /** 카드 뒷면 */
    static void drawBack(DrawContext ctx, float x, float y, float alpha) {
        Ui.tex(ctx, "card_back", x - M, y - M, CW + 2 * M, CH + 2 * M, alpha);
    }

    /** 카드 앞면 (성장 창의 카드 탭에서도 씀). 좌표 = 카드 왼쪽 위 (틀 바깥 여백 M 은 알아서) */
    static void drawCard(DrawContext ctx, TextRenderer tr, JsonObject c, float x, float y, boolean burnHover, boolean hover, float alpha) {
        int r = c.get("rarity").getAsInt();
        String id = c.get("id").getAsString();
        var m = ctx.getMatrices();
        m.push();
        m.translate(x, y, 0);
        Ui.tex(ctx, "card_" + r, -M, -M, CW + 2 * M, CH + 2 * M, alpha);
        Ui.tex(ctx, "art/" + id, 11, 25, 82, 60, alpha);
        if (hover && !burnHover) ctx.fill(11, 25, 93, 85, Ui.alpha(0x22FFF0C0, alpha));
        int ink = Ui.alpha(0xFF2A1E10, alpha);
        // 제목
        String name = c.get("name").getAsString();
        float ns = Math.min(1f, 80f / tr.getWidth(name));
        Ui.text(ctx, tr, name, CW / 2f, 10.5f + (1 - ns) * 4, ns, ink, false);
        // 분류·등급
        String g = c.get("group").getAsString() + " · " + (r == 2 ? "전설" : r == 1 ? "희귀" : "일반");
        Ui.text(ctx, tr, g, CW / 2f, 90.5f, 0.7f, Ui.alpha(r == 2 ? 0xFFF4D278 : r == 1 ? 0xFFB8DDF0 : 0xFFE0CDA8, alpha), false);
        // 설명 + 조건
        m.push(); m.translate(CW / 2f, 106, 0); m.scale(0.78f, 0.78f, 1);
        Ui.wrapCentered(ctx, tr, c.get("effect").getAsString(), 0, 0, (int) (80 / 0.78f), ink, 4);
        m.pop();
        ctx.fill(22, 140, CW - 22, 141, Ui.alpha(0x60806040, alpha));
        m.push(); m.translate(CW / 2f, 144, 0); m.scale(0.66f, 0.66f, 1);
        Ui.wrapCentered(ctx, tr, "조건: " + c.get("cond").getAsString(), 0, 0, (int) (84 / 0.66f), Ui.alpha(0xFF7A5A30, alpha), 2);
        m.pop();
        if (burnHover) {   // 태울 카드: 붉게 + 위로 일렁이는 불
            ctx.fill(0, 0, CW, CH, Ui.alpha(0x50FF3010, alpha));
            float w = (System.currentTimeMillis() % 1000) / 1000f;
            for (int k = 0; k < 8; k++) {
                int fx = 6 + k * 12;
                int fh = (int) (10 + 6 * Math.sin((w + k * 0.37) * Math.PI * 2));
                ctx.fill(fx, CH - fh, fx + 8, CH, Ui.alpha(0xA0FF7020, alpha));
                ctx.fill(fx + 2, CH - fh / 2, fx + 6, CH, Ui.alpha(0xC0FFD060, alpha));
            }
        }
        m.pop();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (hits.click((mouseX - ox) / sc, (mouseY - oy) / sc, button)) return true;
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
