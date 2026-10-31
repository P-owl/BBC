package bbc.client;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.text.OrderedText;
import net.minecraft.text.StringVisitable;

import java.util.ArrayList;
import java.util.List;

/** 직접 만든 화면(성장 창·재능 카드)용 그리기 도우미: 원·고리·마름모·장식 테두리·줄바꿈 글자, 클릭 영역 */
final class Ui {
    private Ui() {}

    // Deepwoken 풍 색
    static final int TEAL = 0xF01B2B29, TEAL_D = 0xF0142120, TEAL_L = 0xFF2C403D, CREAM = 0xFFE8DDBE, CREAM_D = 0xFFBFB28E,
            BRONZE = 0xFFB08A4A, BRONZE_D = 0xFF6E5228, GOLD = 0xFFE0B860, INK = 0xFF15110C, LINE = 0xFFCFC3A0;

    static void quad(DrawContext ctx, float x1, float y1, float x2, float y2, float x3, float y3, float x4, float y4, int col) {
        var vc = ctx.getVertexConsumers().getBuffer(RenderLayer.getGui());
        var m = ctx.getMatrices().peek().getPositionMatrix();
        vc.vertex(m, x1, y1, 0).color(col); vc.vertex(m, x2, y2, 0).color(col); vc.vertex(m, x3, y3, 0).color(col); vc.vertex(m, x4, y4, 0).color(col);
        vc.vertex(m, x4, y4, 0).color(col); vc.vertex(m, x3, y3, 0).color(col); vc.vertex(m, x2, y2, 0).color(col); vc.vertex(m, x1, y1, 0).color(col);
    }

    static void circle(DrawContext ctx, float cx, float cy, float r, int col) { ring(ctx, cx, cy, 0, r, col, 0, 1); }

    /** 고리 (r0~r1), 시작~끝 비율 (0 = 위에서 시계방향) */
    static void ring(DrawContext ctx, float cx, float cy, float r0, float r1, int col, float from, float to) {
        int n = Math.max(6, (int) (r1 * 2.2 * (to - from)) + 6);
        for (int i = 0; i < n; i++) {
            double a0 = Math.PI * 2 * (from + (to - from) * i / n) - Math.PI / 2, a1 = Math.PI * 2 * (from + (to - from) * (i + 1) / n) - Math.PI / 2;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            quad(ctx, cx + c0 * r0, cy + s0 * r0, cx + c0 * r1, cy + s0 * r1, cx + c1 * r1, cy + s1 * r1, cx + c1 * r0, cy + s1 * r0, col);
        }
        ctx.draw();
    }

    static void diamond(DrawContext ctx, float cx, float cy, float r, int col) {
        quad(ctx, cx, cy - r, cx + r, cy, cx, cy + r, cx - r, cy, col);
        ctx.draw();
    }

    /** 얇은 크림색 테두리 + 네 귀퉁이 마름모 (Deepwoken 창 테두리) */
    static void frame(DrawContext ctx, int x, int y, int w, int h, int bg) {
        ctx.fill(x, y, x + w, y + h, bg);
        int c = LINE;
        ctx.fill(x + 2, y + 2, x + w - 2, y + 3, c); ctx.fill(x + 2, y + h - 3, x + w - 2, y + h - 2, c);
        ctx.fill(x + 2, y + 2, x + 3, y + h - 2, c); ctx.fill(x + w - 3, y + 2, x + w - 2, y + h - 2, c);
        for (int[] p : new int[][]{{x + 2, y + 2}, {x + w - 2, y + 2}, {x + 2, y + h - 2}, {x + w - 2, y + h - 2}}) {
            diamond(ctx, p[0], p[1], 4, INK);
            diamond(ctx, p[0], p[1], 3, c);
        }
    }

    /** 가운데 마름모가 있는 가로 구분선 */
    static void divider(DrawContext ctx, int x, int y, int w) {
        ctx.fill(x, y, x + w, y + 1, 0xAACFC3A0);
        diamond(ctx, x + w / 2f, y + 0.5f, 3, LINE);
        diamond(ctx, x, y + 0.5f, 2, LINE);
        diamond(ctx, x + w, y + 0.5f, 2, LINE);
    }

    /** 크림색 이름표 (Deepwoken 'Origin: ...' 칸) */
    static void tag(DrawContext ctx, TextRenderer tr, int x, int y, String label, String value) {
        int w = tr.getWidth(label + value) + 8;
        ctx.fill(x, y, x + w, y + 11, CREAM);
        ctx.fill(x, y + 10, x + w, y + 11, CREAM_D);
        ctx.drawText(tr, label, x + 4, y + 2, 0xFF5A4A2A, false);
        ctx.drawText(tr, value, x + 4 + tr.getWidth(label), y + 2, INK, false);
    }

    /** 줄바꿈해서 가운데 정렬, 그린 줄 수를 돌려줌 */
    static int wrapCentered(DrawContext ctx, TextRenderer tr, String s, int cx, int y, int width, int col, int maxLines) {
        List<OrderedText> lines = tr.wrapLines(StringVisitable.plain(s), width);
        int n = Math.min(maxLines, lines.size());
        for (int i = 0; i < n; i++) {
            OrderedText l = lines.get(i);
            ctx.drawText(tr, l, cx - tr.getWidth(l) / 2, y + i * 10, col, false);
        }
        return n;
    }

    static int wrapLeft(DrawContext ctx, TextRenderer tr, String s, int x, int y, int width, int col) {
        List<OrderedText> lines = tr.wrapLines(StringVisitable.plain(s), width);
        for (int i = 0; i < lines.size(); i++) ctx.drawText(tr, lines.get(i), x, y + i * 10, col, false);
        return lines.size();
    }

    /** 이번 프레임의 클릭 영역 (화면 좌표가 아니라 창 안 좌표) */
    static final class Hits {
        private record Hit(int x, int y, int w, int h, Runnable left, Runnable right, List<String> tip) {}
        private final List<Hit> list = new ArrayList<>();
        void clear() { list.clear(); }
        void add(int x, int y, int w, int h, Runnable left, Runnable right, List<String> tip) { list.add(new Hit(x, y, w, h, left, right, tip)); }
        boolean click(double mx, double my, int button) {
            for (int i = list.size() - 1; i >= 0; i--) {
                Hit h = list.get(i);
                if (mx >= h.x && mx < h.x + h.w && my >= h.y && my < h.y + h.h) {
                    Runnable r = button == 1 ? h.right : h.left;
                    if (r != null) { r.run(); return true; }
                }
            }
            return false;
        }
        List<String> tip(double mx, double my) {
            for (int i = list.size() - 1; i >= 0; i--) {
                Hit h = list.get(i);
                if (h.tip != null && mx >= h.x && mx < h.x + h.w && my >= h.y && my < h.y + h.h) return h.tip;
            }
            return null;
        }
        boolean over(double mx, double my, int x, int y, int w, int h) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }
}
