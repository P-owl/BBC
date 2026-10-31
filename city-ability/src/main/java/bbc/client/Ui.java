package bbc.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.text.OrderedText;
import net.minecraft.text.StringVisitable;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/** 직접 만든 화면(성장 창·재능 카드)용 그리기 도우미: 원·고리·마름모·장식 테두리·줄바꿈 글자, 클릭 영역 */
final class Ui {
    private Ui() {}

    // Deepwoken 풍 색
    static final int TEAL = 0xF01B2B29, TEAL_D = 0xF0142120, TEAL_L = 0xFF2C403D, CREAM = 0xFFE8DDBE, CREAM_D = 0xFFBFB28E,
            BRONZE = 0xFFB08A4A, BRONZE_D = 0xFF6E5228, GOLD = 0xFFE0B860, INK = 0xFF15110C, LINE = 0xFFCFC3A0;

    // ═════════ 텍스처 (tools/make_ui_textures.py 가 만든 것, GUI 1칸 = 3픽셀) ═════════

    static Identifier id(String path) { return Identifier.of("bbc", "textures/gui/" + path + ".png"); }

    /** 텍스처를 쓰기 전에: 앞에 쌓인 사각형·글자를 먼저 그리고, 부드러운 확대·축소, 반투명 섞기 */
    private static Identifier prep(DrawContext ctx, String path, float alpha) {
        Identifier id = id(path);
        ctx.draw();
        MinecraftClient.getInstance().getTextureManager().getTexture(id).setFilter(true, false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1f, 1f, 1f, alpha);
        return id;
    }

    private static void unprep() { RenderSystem.setShaderColor(1f, 1f, 1f, 1f); }

    /** 텍스처 전체를 (x, y, w, h) 에 */
    static void tex(DrawContext ctx, String path, float x, float y, float w, float h) { tex(ctx, path, x, y, w, h, 1f); }

    static void tex(DrawContext ctx, String path, float x, float y, float w, float h, float alpha) {
        if (alpha <= 0.01f) return;
        Identifier id = prep(ctx, path, alpha);
        var m = ctx.getMatrices();
        m.push();
        m.translate(x, y, 0);
        m.scale(w / 256f, h / 256f, 1f);
        ctx.drawTexture(id, 0, 0, 256, 256, 0f, 0f, 256, 256, 256, 256);
        m.pop();
        unprep();
    }

    /**
     * 가로로 늘이는 텍스처 (양끝 cap 칸은 그대로, 가운데만 늘림). tw·th = 텍스처 크기(GUI 칸).
     * 높이 h 에 맞춰 통째로 비율 조절
     */
    static void slice3(DrawContext ctx, String path, int tw, int th, int cap, float x, float y, float w, float h, float alpha) {
        if (alpha <= 0.01f) return;
        Identifier id = prep(ctx, path, alpha);
        int TW = tw * 3, TH = th * 3, C = cap * 3;
        float k = h / th / 3f;                 // 텍스처 1픽셀 = k 칸
        int total = Math.max(2 * C + 1, Math.round(w / k));
        var m = ctx.getMatrices();
        m.push();
        m.translate(x, y, 0);
        m.scale(k, k, 1f);
        ctx.drawTexture(id, 0, 0, C, TH, 0f, 0f, C, TH, TW, TH);
        ctx.drawTexture(id, C, 0, total - 2 * C, TH, C, 0f, TW - 2 * C, TH, TW, TH);
        ctx.drawTexture(id, total - C, 0, C, TH, TW - C, 0f, C, TH, TW, TH);
        m.pop();
        unprep();
    }

    /** 금속 단추 (gold / red / teal) + 가운데 글자 */
    static void button(DrawContext ctx, TextRenderer tr, String color, float x, float y, float w, float h, String label, boolean hover) {
        slice3(ctx, "btn_" + color, 40, 22, 8, x, y, w, h, 1f);
        if (hover) { ctx.fill((int) x + 2, (int) y + 2, (int) (x + w) - 2, (int) (y + h) - 2, 0x22FFFFFF); }
        int tw = tr.getWidth(label);
        float ts = Math.min(1f, (h - 4) / 9f);
        var m = ctx.getMatrices();
        m.push();
        m.translate(x + w / 2f, y + h / 2f - 4 * ts + 0.5f, 0);
        m.scale(ts, ts, 1);
        ctx.drawText(tr, label, -tw / 2, 0, color.equals("gold") ? 0xFF2A1A08 : 0xFFF4E8CC, !color.equals("gold"));
        m.pop();
    }

    /** 장식 구분선 (가운데 마름모) — 너비 w, 가운데 (cx, y) */
    static void ornament(DrawContext ctx, float cx, float y, float w) {
        float h = 12 * Math.min(1f, Math.max(0.7f, w / 200f));
        tex(ctx, "ornament", cx - w / 2f, y - h / 2f, w, h, 0.95f);
    }

    /** 수련 도구 아이템 (텍스처 번호 7101 + 스탯 순서) */
    static net.minecraft.item.ItemStack toolStack(int statIndex) {
        var st = new net.minecraft.item.ItemStack(net.minecraft.item.Items.WARPED_FUNGUS_ON_A_STICK);
        st.set(net.minecraft.component.DataComponentTypes.CUSTOM_MODEL_DATA, new net.minecraft.component.type.CustomModelDataComponent(7101 + statIndex));
        return st;
    }

    /** 크기 조절한 아이템 (가운데 기준) */
    static void item(DrawContext ctx, net.minecraft.item.ItemStack st, float cx, float cy, float scale) {
        var m = ctx.getMatrices();
        m.push();
        m.translate(cx, cy, 0);
        m.scale(scale, scale, 1);
        ctx.drawItem(st, -8, -8);
        m.pop();
    }

    /** 글자를 가운데 정렬 + 배율 */
    static void text(DrawContext ctx, TextRenderer tr, String s, float cx, float y, float scale, int col, boolean shadow) {
        var m = ctx.getMatrices();
        m.push();
        m.translate(cx, y, 0);
        m.scale(scale, scale, 1);
        ctx.drawText(tr, s, -tr.getWidth(s) / 2, 0, col, shadow);
        m.pop();
    }

    static int alpha(int argb, float a) { return ((int) (((argb >>> 24) & 0xFF) * Math.max(0, Math.min(1, a))) << 24) | (argb & 0xFFFFFF); }

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
        static boolean inside(double mx, double my, float x, float y, float w, float h) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }
}
