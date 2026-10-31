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

/**
 * 재능 카드 고르기 (Deepwoken 처럼 파워가 오르면 바로 뜸).
 *  1) '파워 상승' 문양이 뜨고 카드 뒷면이 차례로 깔림  2) 한 장씩 뒤집혀 앞면 (제목 띠·그림·분류·설명·조건)
 *  왼쪽 클릭 = 고르기, 오른쪽 클릭 또는 '소각' 모드에서 클릭 = 태우기 (토큰 1, 앞으로 다시 안 나옴, 마지막 한 장은 못 태움)
 *  ESC 로 닫으면 나중에 성장 창(K)에서 다시 고를 수 있음
 */
public class TalentScreen extends Screen {
    private static final int CW = 104, CH = 162, GAP = 10;
    private final long openedAt = System.currentTimeMillis();
    private final boolean fresh;
    private boolean burnMode;
    private final Ui.Hits hits = new Ui.Hits();
    private float sc = 1, ox, oy;
    private int vw;

    /** fresh = 방금 파워가 올라서 뜬 것 (문양·뒤집기 연출) */
    public TalentScreen(boolean fresh) { super(Text.literal("재능 카드")); this.fresh = fresh; }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, width, height, 0xA0101614);
    }

    private float t() { return (System.currentTimeMillis() - openedAt) / 50f; }   // 틱

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderBackground(ctx, mouseX, mouseY, delta);
        JsonArray pick = GrowthClient.state.has("pick") ? GrowthClient.state.getAsJsonArray("pick") : new JsonArray();
        int n = Math.max(1, pick.size());
        vw = Math.max(n * (CW + GAP) + 40, 480);
        int vh = 300;
        sc = Math.min(width * 0.96f / vw, height * 0.9f / vh);
        ox = (width - vw * sc) / 2f; oy = (height - vh * sc) / 2f;
        double mx = (mouseX - ox) / sc, my = (mouseY - oy) / sc;
        hits.clear();
        var m = ctx.getMatrices();
        m.push();
        m.translate(ox, oy, 0);
        m.scale(sc, sc, 1);
        TextRenderer tr = textRenderer;
        float t = fresh ? t() : 100;
        // 위 문구 + 파워 상승 문양
        ctx.drawCenteredTextWithShadow(tr, "수련이 마침내 결실을 맺는다...", vw / 2, 20, 0xFFF0E6C8);
        if (t < 30) {
            float a = t < 5 ? t / 5 : t > 22 ? Math.max(0, (30 - t) / 8) : 1;
            int col = ((int) (a * 255) << 24) | 0xFFFFFF;
            Ui.diamond(ctx, vw / 2f, 60, 14 * a + 4, (col & 0xFF000000) | 0xE8DDBE);
            for (int s = -1; s <= 1; s += 2) {
                Ui.quad(ctx, vw / 2f + s * 18, 60, vw / 2f + s * 70, 54, vw / 2f + s * 80, 60, vw / 2f + s * 70, 66, (col & 0xFF000000) | 0xE8DDBE);
                ctx.draw();
            }
            m.push(); m.translate(vw / 2f, 74, 0); m.scale(1.6f, 1.6f, 1);
            ctx.drawCenteredTextWithShadow(tr, "파워 상승", 0, 0, col);
            m.pop();
        }
        // 소각 토큰 + 소각 모드 단추
        int burns = GrowthClient.i("burns");
        int bx = vw / 2 + 120, by = 36;
        ctx.drawItem(new ItemStack(Items.BLAZE_POWDER), vw / 2 - 30, 32);
        ctx.drawText(tr, burns + " 소각", vw / 2 - 12, 36, 0xFFFFB070, true);
        ctx.fill(bx, by - 3, bx + 74, by + 12, burnMode ? 0xFFB03020 : 0xFF4A2A20);
        ctx.drawText(tr, burnMode ? "소각 모드 켜짐" : "소각 모드", bx + 6, by, 0xFFFFE0C0, false);
        hits.add(bx, by - 3, 74, 15, () -> burnMode = !burnMode, null, null);
        // 카드
        int total = pick.size() * CW + (pick.size() - 1) * GAP;
        int x0 = vw / 2 - total / 2, y0 = 96;
        for (int i = 0; i < pick.size(); i++) {
            JsonObject c = pick.get(i).getAsJsonObject();
            int x = x0 + i * (CW + GAP);
            float appear = t - 6 - i * 3;          // 뒷면이 깔리는 때
            float flip = t - 24 - i * 5;           // 뒤집히는 때
            if (appear < 0) continue;
            boolean hover = Math.abs(mx - (x + CW / 2.0)) < CW / 2.0 && my >= y0 && my < y0 + CH;
            int lift = hover && flip > 6 ? -6 : 0;
            float slide = Math.min(1, appear / 6f);
            int y = y0 + lift + (int) ((1 - slide) * 30);
            // 뒤집기: 가로로 줄었다가 앞면으로 펴짐
            float fx = flip < 0 ? 1 : flip < 3 ? 1 - flip / 3 : flip < 6 ? (flip - 3) / 3 : 1;
            boolean front = flip >= 3;
            m.push();
            m.translate(x + CW / 2f, 0, 0);
            m.scale(Math.max(0.02f, fx), 1, 1);
            m.translate(-(x + CW / 2f), 0, 0);
            if (front) drawCard(ctx, tr, c, x, y, 1f, hover && burnMode, hover, slide);
            else drawBack(ctx, x, y);
            m.pop();
            if (flip > 6) {
                String id = c.get("id").getAsString();
                boolean canBurn = burns > 0 && pick.size() > 1;
                Runnable choose = () -> { GrowthClient.send(GrowthActionPayload.CHOOSE, id); close(); };
                Runnable burn = canBurn ? () -> GrowthClient.send(GrowthActionPayload.BURN, id) : null;
                hits.add(x, y0 - 6, CW, CH + 6, burnMode ? burn : choose, burn, null);
            }
        }
        ctx.drawCenteredTextWithShadow(tr, "왼쪽 클릭: 고르기   ·   오른쪽 클릭 / 소각 모드: 태우기 (다시는 안 나옴)   ·   ESC: 나중에", vw / 2, y0 + CH + 18, 0xFFB8B0A0);
        m.pop();
    }

    /** 카드 뒷면: 금색 무늬 */
    static void drawBack(DrawContext ctx, int x, int y) {
        ctx.fill(x - 1, y - 1, x + CW + 1, y + CH + 1, Ui.INK);
        ctx.fill(x, y, x + CW, y + CH, 0xFFD8B070);
        ctx.fill(x + 4, y + 4, x + CW - 4, y + CH - 4, 0xFF2E2A24);
        for (int r = 0; r < 6; r++) for (int c = 0; c < 4; c++) {
            float cx = x + 14 + c * 25, cy = y + 16 + r * 26;
            Ui.diamond(ctx, cx, cy, 9, 0xFFD8B070);
            Ui.diamond(ctx, cx, cy, 5, 0xFF2E2A24);
            Ui.diamond(ctx, cx, cy, 2, 0xFFD8B070);
        }
    }

    /** 카드 앞면 (성장 창의 카드 탭에서도 씀) */
    static void drawCard(DrawContext ctx, TextRenderer tr, JsonObject c, int x, int y, float scale, boolean burnHover, boolean hover, float alpha) {
        var m = ctx.getMatrices();
        m.push();
        m.translate(x, y, 0);
        m.scale(scale, scale, 1);
        int r = c.get("rarity").getAsInt();
        int frame = r == 2 ? 0xFFE0B040 : r == 1 ? 0xFF8FB8C8 : 0xFFB8913A;
        int paper = burnHover ? 0xFFE0A090 : 0xFFEDE3C8;
        ctx.fill(-1, -1, CW + 1, CH + 1, hover ? 0xFFFFFFFF : Ui.INK);
        ctx.fill(0, 0, CW, CH, frame);
        ctx.fill(3, 3, CW - 3, CH - 3, burnHover ? 0xFF8A3020 : 0xFF6A6A62);
        // 그림 칸
        ctx.fill(14, 24, CW - 14, 24 + 62, frame);
        ctx.fill(16, 26, CW - 16, 22 + 62, 0xFFF8F6F0);
        var icon = new ItemStack(switch (c.get("group").getAsString()) {
            case "전투" -> Items.IRON_SWORD; case "생존" -> Items.GOLDEN_APPLE; case "생활" -> Items.WHEAT; case "종족" -> Items.PLAYER_HEAD;
            default -> Items.BOOK; });
        m.push(); m.translate(CW / 2f - 18, 38, 0); m.scale(2.25f, 2.25f, 1);
        ctx.drawItem(icon, 0, 0);
        m.pop();
        // 제목 띠
        ctx.fill(6, 6, CW - 6, 20, Ui.INK);
        ctx.fill(7, 7, CW - 7, 19, paper);
        String name = c.get("name").getAsString();
        float ns = Math.min(1f, (CW - 16) / (float) tr.getWidth(name));
        m.push(); m.translate(CW / 2f, 9 + (1 - ns) * 4, 0); m.scale(ns, ns, 1);
        ctx.drawText(tr, name, -tr.getWidth(name) / 2, 0, Ui.INK, false);
        m.pop();
        // 분류·등급
        String g = c.get("group").getAsString() + " · " + (r == 2 ? "전설" : r == 1 ? "희귀" : "일반");
        m.push(); m.translate(CW / 2f, 90, 0); m.scale(0.75f, 0.75f, 1);
        ctx.drawText(tr, g, -tr.getWidth(g) / 2, 0, r == 2 ? 0xFFF0D070 : 0xFFE0E0D8, false);
        m.pop();
        // 설명 종이
        ctx.fill(6, 100, CW - 6, CH - 6, paper);
        m.push(); m.translate(CW / 2f, 104, 0); m.scale(0.8f, 0.8f, 1);
        int lines = Ui.wrapCentered(ctx, tr, c.get("effect").getAsString(), 0, 0, (int) ((CW - 16) / 0.8f), Ui.INK, 4);
        Ui.wrapCentered(ctx, tr, "조건: " + c.get("cond").getAsString(), 0, lines * 10 + 4, (int) ((CW - 16) / 0.8f), 0xFF6A5A3A, 2);
        m.pop();
        m.pop();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (hits.click((mouseX - ox) / sc, (mouseY - oy) / sc, button)) return true;
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
