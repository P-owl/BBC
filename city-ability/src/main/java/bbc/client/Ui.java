package bbc.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.item.ItemStack;
import net.minecraft.text.OrderedText;
import net.minecraft.text.StringVisitable;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * 직접 만든 화면(성장 창·재능 카드)용 그리기 도우미 — 마인크래프트 바닐라 GUI 모양 그대로:
 * 회색 상자(보관함 창), 움푹한 칸(슬롯), 바닐라 단추·발전과제 테두리·알림(토스트)·경험치 막대 그림을 씀.
 * 글자는 배율 1(또는 정수배)로만 그려서 픽셀이 뭉개지지 않게.
 */
final class Ui {
    private Ui() {}

    // 바닐라 GUI 색
    static final int BG = 0xFFC6C6C6, HL = 0xFFFFFFFF, SH = 0xFF555555, DARK = 0xFF373737, SLOT = 0xFF8B8B8B, BLACK = 0xFF000000,
            TEXT = 0xFF404040, TEXT_DIM = 0xFF707070, GREEN = 0xFF80FF20, YELLOW = 0xFFFFFF55, GOLD = 0xFFFFAA00, AQUA = 0xFF55FFFF,
            RED = 0xFFFF5555, WHITE = 0xFFFFFFFF, GRAY = 0xFFAAAAAA;

    // ═════════ 바닐라 모양 ═════════

    /** 보관함 창 같은 회색 상자: 검은 외곽(모서리 깎음) + 위왼쪽 흰 줄 + 아래오른쪽 어두운 줄 */
    static void panel(DrawContext ctx, int x, int y, int w, int h) {
        ctx.fill(x + 2, y, x + w - 2, y + 1, BLACK);
        ctx.fill(x + 2, y + h - 1, x + w - 2, y + h, BLACK);
        ctx.fill(x, y + 2, x + 1, y + h - 2, BLACK);
        ctx.fill(x + w - 1, y + 2, x + w, y + h - 2, BLACK);
        ctx.fill(x + 1, y + 1, x + 2, y + 2, BLACK);
        ctx.fill(x + w - 2, y + 1, x + w - 1, y + 2, BLACK);
        ctx.fill(x + 1, y + h - 2, x + 2, y + h - 1, BLACK);
        ctx.fill(x + w - 2, y + h - 2, x + w - 1, y + h - 1, BLACK);
        ctx.fill(x + 1, y + 2, x + w - 1, y + h - 2, BG);
        ctx.fill(x + 2, y + 1, x + w - 2, y + h - 1, BG);
        ctx.fill(x + 2, y + 1, x + w - 3, y + 3, HL);
        ctx.fill(x + 1, y + 2, x + 3, y + h - 3, HL);
        ctx.fill(x + 3, y + h - 3, x + w - 2, y + h - 1, SH);
        ctx.fill(x + w - 3, y + 3, x + w - 1, y + h - 2, SH);
    }

    /** 움푹한 칸 (슬롯): 위왼쪽 어둡게, 아래오른쪽 희게 */
    static void inset(DrawContext ctx, int x, int y, int w, int h, int fill) {
        ctx.fill(x, y, x + w, y + h, fill);
        ctx.fill(x, y, x + w - 1, y + 1, DARK);
        ctx.fill(x, y, x + 1, y + h - 1, DARK);
        ctx.fill(x + 1, y + h - 1, x + w, y + h, HL);
        ctx.fill(x + w - 1, y + 1, x + w, y + h, HL);
    }

    static void slot(DrawContext ctx, int x, int y) { inset(ctx, x, y, 18, 18, SLOT); }

    /** 새긴 가로줄 (회색 바탕 위) */
    static void groove(DrawContext ctx, int x, int y, int w) {
        ctx.fill(x, y, x + w, y + 1, SLOT);
        ctx.fill(x, y + 1, x + w, y + 2, HL);
    }

    private static Identifier mc(String sprite) { return Identifier.ofVanilla(sprite); }

    /** 바닐라 단추 (흰 글자, 마우스 올리면 밝은 테두리, 못 누르면 회색) */
    static void button(DrawContext ctx, TextRenderer tr, int x, int y, int w, int h, String label, boolean hover, boolean active) {
        RenderSystem.enableBlend();
        ctx.drawGuiTexture(mc(!active ? "widget/button_disabled" : hover ? "widget/button_highlighted" : "widget/button"), x, y, w, h);
        ctx.drawCenteredTextWithShadow(tr, label, x + w / 2, y + (h - 8) / 2, active ? WHITE : 0xFFA0A0A0);
    }

    /** 발전과제 테두리 (task 네모 / goal 둥근 / challenge 뾰족), obtained = 노란색 */
    static void frame(DrawContext ctx, String type, boolean obtained, int x, int y, int size) {
        RenderSystem.enableBlend();
        ctx.drawGuiTexture(mc("advancements/" + type + "_frame_" + (obtained ? "obtained" : "unobtained")), x, y, size, size);
    }

    /** 발전과제 알림 바탕 (160x32) */
    static void toast(DrawContext ctx, int x, int y) {
        RenderSystem.enableBlend();
        ctx.drawGuiTexture(mc("toast/advancement"), x, y, 160, 32);
    }

    /** 경험치 막대 (182x5 바닐라 그림, 너비 w 로 늘이지 않고 잘라서) */
    static void xpBar(DrawContext ctx, int x, int y, float frac) {
        RenderSystem.enableBlend();
        ctx.drawGuiTexture(mc("hud/experience_bar_background"), x, y, 182, 5);
        int fw = (int) (182 * Math.max(0, Math.min(1, frac)));
        if (fw > 0) ctx.drawGuiTexture(mc("hud/experience_bar_progress"), 182, 5, 0, 0, x, y, fw, 5);
    }

    /** 보스 막대 (182x5, green / yellow / white / purple ...) */
    static void bossBar(DrawContext ctx, String color, int x, int y, float frac) {
        RenderSystem.enableBlend();
        ctx.drawGuiTexture(mc("boss_bar/" + color + "_background"), x, y, 182, 5);
        int fw = (int) (182 * Math.max(0, Math.min(1, frac)));
        if (fw > 0) ctx.drawGuiTexture(mc("boss_bar/" + color + "_progress"), 182, 5, 0, 0, x, y, fw, 5);
    }

    /** 레시피 책 탭 (왼쪽 탭, 35x27) */
    static void tab(DrawContext ctx, boolean selected, int x, int y) {
        RenderSystem.enableBlend();
        ctx.drawGuiTexture(mc(selected ? "recipe_book/tab_selected" : "recipe_book/tab"), x, y, 35, 27);
    }

    /** 경험치 레벨 숫자처럼: 검은 테두리 + 색 글자 (scale 은 정수 권장) */
    static void outlined(DrawContext ctx, TextRenderer tr, String s, int cx, int y, int scale, int col) {
        var m = ctx.getMatrices();
        m.push();
        m.translate(cx, y, 0);
        m.scale(scale, scale, 1);
        int x = -tr.getWidth(s) / 2;
        ctx.drawText(tr, s, x + 1, 0, BLACK, false);
        ctx.drawText(tr, s, x - 1, 0, BLACK, false);
        ctx.drawText(tr, s, x, 1, BLACK, false);
        ctx.drawText(tr, s, x, -1, BLACK, false);
        ctx.drawText(tr, s, x, 0, col, false);
        m.pop();
    }

    // ═════════ 우리 텍스처 (tools/make_ui_textures.py, 1칸 = 1픽셀 도트) ═════════

    static Identifier id(String path) { return Identifier.of("bbc", "textures/gui/" + path + ".png"); }

    /** 텍스처 전체를 (x, y) 에 원래 크기(tw x th)로 */
    static void tex(DrawContext ctx, String path, int x, int y, int tw, int th) { tex(ctx, path, x, y, tw, th, 1f); }

    static void tex(DrawContext ctx, String path, int x, int y, int tw, int th, float alpha) {
        if (alpha <= 0.01f) return;
        Identifier id = id(path);
        ctx.draw();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1f, 1f, 1f, alpha);
        ctx.drawTexture(id, x, y, 0f, 0f, tw, th, tw, th);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
    }

    /** 수련 도구 아이템 (텍스처 번호 7101 + 스탯 순서) */
    static ItemStack toolStack(int statIndex) {
        var st = new ItemStack(net.minecraft.item.Items.WARPED_FUNGUS_ON_A_STICK);
        st.set(net.minecraft.component.DataComponentTypes.CUSTOM_MODEL_DATA, new net.minecraft.component.type.CustomModelDataComponent(7101 + statIndex));
        return st;
    }

    /** 아이템을 정수배로 (왼쪽 위 기준) */
    static void item(DrawContext ctx, ItemStack st, int x, int y, int scale) {
        var m = ctx.getMatrices();
        m.push();
        m.translate(x, y, 0);
        m.scale(scale, scale, 1);
        ctx.drawItem(st, 0, 0);
        m.pop();
    }

    static int alpha(int argb, float a) { return ((int) (((argb >>> 24) & 0xFF) * Math.max(0, Math.min(1, a))) << 24) | (argb & 0xFFFFFF); }

    static void centered(DrawContext ctx, TextRenderer tr, String s, int cx, int y, int col, boolean shadow) {
        ctx.drawText(tr, s, cx - tr.getWidth(s) / 2, y, col, shadow);
    }

    // ═════════ 도형 (HUD 등) ═════════

    static void quad(DrawContext ctx, float x1, float y1, float x2, float y2, float x3, float y3, float x4, float y4, int col) {
        var vc = ctx.getVertexConsumers().getBuffer(RenderLayer.getGui());
        var m = ctx.getMatrices().peek().getPositionMatrix();
        vc.vertex(m, x1, y1, 0).color(col); vc.vertex(m, x2, y2, 0).color(col); vc.vertex(m, x3, y3, 0).color(col); vc.vertex(m, x4, y4, 0).color(col);
        vc.vertex(m, x4, y4, 0).color(col); vc.vertex(m, x3, y3, 0).color(col); vc.vertex(m, x2, y2, 0).color(col); vc.vertex(m, x1, y1, 0).color(col);
    }

    static void diamond(DrawContext ctx, float cx, float cy, float r, int col) {
        quad(ctx, cx, cy - r, cx + r, cy, cx, cy + r, cx - r, cy, col);
        ctx.draw();
    }

    /** 줄바꿈해서 가운데 정렬, 그린 줄 수를 돌려줌 */
    static int wrapCentered(DrawContext ctx, TextRenderer tr, String s, int cx, int y, int width, int col, int maxLines) {
        List<OrderedText> lines = tr.wrapLines(StringVisitable.plain(s), width);
        int n = Math.min(maxLines, lines.size());
        for (int i = 0; i < n; i++) {
            OrderedText l = lines.get(i);
            ctx.drawText(tr, l, cx - tr.getWidth(l) / 2, y + i * 9, col, false);
        }
        return n;
    }

    static int lines(TextRenderer tr, String s, int width) { return tr.wrapLines(StringVisitable.plain(s), width).size(); }

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
        static boolean inside(double mx, double my, float x, float y, float w, float h) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }
}
