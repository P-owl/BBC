package bbc.client;

import bbc.net.GrowthActionPayload;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 재능 카드 고르기 (Deepwoken 처럼 파워가 오르면 바로 뜸) — 마인크래프트 GUI 모양.
 *  발전과제 알림처럼 '파워 상승!' 이 내려오고, 카드 뒷면이 깔린 뒤 한 장씩 뒤집힘 (책장 넘기는 소리)
 *  카드 = 회색 상자 + 등급 테두리(일반 회색 · 희귀 다이아몬드색 · 전설 금색) + 그림 칸(분류별 도트 배경 + 바닐라 아이템 3배)
 *  왼쪽 클릭 = 고르기 (카드가 가운데로 올라가며 번쩍 → 닫힘), 오른쪽 클릭 또는 '소각 모드' = 태우기
 *  (아래부터 타 들어가며 불씨가 튐, 남은 카드가 가운데로 모임. 토큰 1, 다시는 안 나옴, 마지막 한 장은 못 태움)
 *  ESC = 나중에 (성장 창 K 에서 다시)
 */
public class TalentScreen extends Screen {
    static final int CW = 100, CH = 136, GAP = 8;
    private final long openedAt = System.currentTimeMillis();
    private final boolean fresh;
    private boolean burnMode;
    private final Ui.Hits hits = new Ui.Hits();
    private float sc = 1;
    private int ox, oy, vw;
    private final Map<String, Float> posX = new HashMap<>();
    private record Burning(JsonObject card, int x, long at) {}
    private final List<Burning> burning = new ArrayList<>();
    private final Set<String> burnedIds = new HashSet<>();
    private JsonObject chosen;
    private int chosenX;
    private long chosenAt = -1;
    private JsonArray snapshot = new JsonArray();
    private int flippedSound = 0;
    private boolean toastSound;

    public TalentScreen(boolean fresh) { super(Text.literal("재능 카드")); this.fresh = fresh; }

    /** 연출 중이면 서버가 후보를 비워도 바로 닫지 않음 */
    boolean busy() { return chosenAt >= 0; }

    @Override
    public boolean shouldPause() { return false; }

    private static float now() { return System.currentTimeMillis() / 50f; }
    private float t() { return (System.currentTimeMillis() - openedAt) / 50f; }

    static void play(SoundEvent s, float pitch) {
        MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(s, pitch));
    }

    /** 카드 그림에 쓰는 바닐라 아이템 */
    static ItemStack icon(String id, int rarity) {
        Item it = switch (id) {
            case "hunter" -> Items.BONE;
            case "rhythm" -> Items.NOTE_BLOCK;
            case "heavy_finish" -> Items.MACE;
            case "sharp_switch" -> Items.DIAMOND_SWORD;
            case "provoker" -> Items.GOAT_HORN;
            case "breath" -> Items.WIND_CHARGE;
            case "thick_skin" -> Items.ARMADILLO_SCUTE;
            case "leftover" -> Items.CLOCK;
            case "tumbler" -> Items.SLIME_BALL;
            case "shadow_step" -> Items.ENDER_PEARL;
            case "once_more" -> Items.TOTEM_OF_UNDYING;
            case "lumberjack" -> Items.IRON_AXE;
            case "self_taught" -> Items.BOOK;
            case "i_am_ahead" -> Items.NETHER_STAR;
            default -> Items.PAPER;
        };
        ItemStack st = new ItemStack(it);
        if (rarity == 2) st.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        return st;
    }

    static String artOf(String group) {
        return switch (group) { case "전투" -> "art_combat"; case "생존" -> "art_survival"; case "생활" -> "art_life"; default -> "art_other"; };
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderBackground(ctx, mouseX, mouseY, delta);
        JsonArray live = GrowthClient.state.has("pick") ? GrowthClient.state.getAsJsonArray("pick") : new JsonArray();
        if (chosenAt < 0) {
            JsonArray a = new JsonArray();
            for (var el : live) if (!burnedIds.contains(el.getAsJsonObject().get("id").getAsString())) a.add(el);
            snapshot = a;
        }
        JsonArray pick = snapshot;
        int n = Math.max(1, pick.size());
        vw = Math.max(n * (CW + GAP) - GAP + 20, 360);
        int vh = 252;
        sc = Math.min(1f, Math.min(width / (float) vw, height / (float) vh));
        ox = Math.round((width - vw * sc) / 2f); oy = Math.round((height - vh * sc) / 2f);
        double mx = (mouseX - ox) / sc, my = (mouseY - oy) / sc;
        hits.clear();
        var m = ctx.getMatrices();
        m.push();
        m.translate(ox, oy, 0);
        m.scale(sc, sc, 1);
        TextRenderer tr = textRenderer;
        float t = fresh ? t() : 100;
        int cx = vw / 2;
        float fadeAll = chosenAt >= 0 ? Math.max(0, 1 - (now() - chosenAt) / 14f) : 1f;

        // ── 글귀
        if (t > 4) {
            Ui.centered(ctx, tr, "수련이 마침내 결실을 맺는다...", cx, 40, Ui.alpha(Ui.WHITE, fadeAll), true);
            Ui.centered(ctx, tr, "재능 카드 한 장을 고르세요", cx, 52, Ui.alpha(Ui.GRAY, fadeAll), true);
        }
        // ── 카드
        int total = pick.size() * CW + Math.max(0, pick.size() - 1) * GAP;
        int x0 = cx - total / 2, y0 = 68;
        int burns = GrowthClient.i("burns");
        boolean canBurn = burns > 0 && pick.size() > 1;
        int flippedNow = 0;
        for (int i = 0; i < pick.size(); i++) {
            JsonObject c = pick.get(i).getAsJsonObject();
            String id = c.get("id").getAsString();
            int target = x0 + i * (CW + GAP);
            float px = posX.containsKey(id) ? posX.get(id) + (target - posX.get(id)) * 0.3f : target;
            if (Math.abs(px - target) < 0.5f) px = target;
            posX.put(id, px);
            int x = Math.round(px);
            float appear = t - 10 - i * 3;
            float flip = t - 26 - i * 5;
            if (appear < 0) continue;
            if (flip >= 3) flippedNow++;
            boolean isChosen = chosen != null && id.equals(chosen.get("id").getAsString());
            float a = chosen == null || isChosen ? 1 : fadeAll;
            if (a <= 0.01f) continue;
            boolean hover = chosenAt < 0 && flip > 6 && Ui.Hits.inside(mx, my, x, y0 - 4, CW, CH + 4);
            int y = y0 + (hover ? -4 : 0) + (int) (Math.max(0, 1 - appear / 6f) * 24);
            if (isChosen) {
                float k = Math.min(1, (now() - chosenAt) / 6f);
                x = Math.round(chosenX + (cx - CW / 2f - chosenX) * k);
                y = y0 - (int) (8 * k);
            }
            float fx = flip < 0 ? 1 : flip < 3 ? 1 - flip / 3 : flip < 6 ? (flip - 3) / 3 : 1;
            m.push();
            m.translate(x + CW / 2f, 0, 0);
            m.scale(Math.max(0.02f, fx), 1, 1);
            m.translate(-CW / 2f, 0, 0);
            if (flip >= 3) drawCard(ctx, tr, c, 0, y, hover && burnMode && canBurn, hover, a);
            else drawBack(ctx, 0, y, a);
            if (isChosen) ctx.fill(0, y, CW, y + CH, Ui.alpha(0xFFFFFFFF, Math.max(0, 1 - (now() - chosenAt) / 8f) * 0.8f));
            m.pop();
            if (flip > 6 && chosenAt < 0) {
                final int fxx = x;
                Runnable choose = () -> {
                    chosen = c; chosenX = fxx; chosenAt = (long) now();
                    play(SoundEvents.ENTITY_PLAYER_LEVELUP, 1.4f);
                    GrowthClient.send(GrowthActionPayload.CHOOSE, id);
                };
                Runnable burn = canBurn ? () -> {
                    burning.add(new Burning(c, fxx, (long) now()));
                    burnedIds.add(id);
                    burnMode = false;
                    play(SoundEvents.ITEM_FIRECHARGE_USE, 1.0f);
                    GrowthClient.send(GrowthActionPayload.BURN, id);
                } : null;
                hits.add(x, y0 - 4, CW, CH + 4, burnMode ? burn : choose, burn,
                        List.of(c.get("name").getAsString(), burnMode ? (canBurn ? "클릭: 태우기 (다시는 안 나옴)" : "태울 수 없음") : "왼쪽 클릭: 고르기", burnMode ? "" : (canBurn ? "오른쪽 클릭: 태우기" : "")));
            }
        }
        if (flippedNow > flippedSound) { flippedSound = flippedNow; play(SoundEvents.ITEM_BOOK_PAGE_TURN, 1f); }
        // ── 불타는 카드
        burning.removeIf(b -> now() - b.at > 20);
        for (Burning b : burning) drawBurning(ctx, tr, b.card, b.x, y0, (now() - b.at) / 20f);
        // ── 아래: 소각 토큰 + 소각 모드 단추 + 안내
        if (t > 30 && chosenAt < 0) {
            int bx = cx - 108, by = y0 + CH + 10;
            for (int i = 0; i < 3; i++) {
                Ui.slot(ctx, bx + i * 18, by + 1);
                if (i < burns) Ui.item(ctx, new ItemStack(Items.BLAZE_POWDER), bx + i * 18 + 1, by + 2, 1);
            }
            ctx.drawTextWithShadow(tr, "소각 " + burns + " / 3", bx + 60, by + 6, burns > 0 ? Ui.GOLD : Ui.GRAY);
            hits.add(bx, by, 54, 20, null, null, List.of("소각 토큰 " + burns + "개", "파워가 오를 때마다 +1 (최대 3)", "태운 카드는 다시는 안 나옴"));
            int bw = 100, bbx = cx + 108 - bw;
            boolean hov = Ui.Hits.inside(mx, my, bbx, by, bw, 20);
            Ui.button(ctx, tr, bbx, by, bw, 20, burnMode ? "§c소각 모드: 켜짐" : "소각 모드: 꺼짐", hov, canBurn);
            if (canBurn) hits.add(bbx, by, bw, 20, () -> { burnMode = !burnMode; play(SoundEvents.UI_BUTTON_CLICK.value(), 1f); }, null, List.of("켜면 왼쪽 클릭으로도 태움"));
            Ui.centered(ctx, tr, "왼쪽 클릭: 고르기  ·  오른쪽 클릭: 태우기  ·  ESC: 나중에", cx, by + 28, Ui.GRAY, true);
        }
        // ── 발전과제 알림처럼 '파워 상승!'
        if (fresh && t < 84) {
            if (!toastSound) { toastSound = true; play(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1f); }
            float k = t < 8 ? t / 8 : t > 76 ? (84 - t) / 8 : 1;
            int tx = cx - 80, ty = (int) (-32 + 36 * k);
            Ui.toast(ctx, tx, ty);
            Ui.item(ctx, new ItemStack(Items.NETHER_STAR), tx + 8, ty + 8, 1);
            ctx.drawText(tr, "파워 상승!", tx + 30, ty + 7, 0xFFFF88FF, false);
            ctx.drawText(tr, "파워 " + GrowthClient.i("power") + " 달성", tx + 30, ty + 18, Ui.WHITE, false);
        }
        m.pop();
        List<String> tip = hits.tip(mx, my);
        if (tip != null) {
            List<Text> lines = new ArrayList<>();
            for (int i = 0; i < tip.size(); i++) if (!tip.get(i).isEmpty()) lines.add(i == 0 ? Text.literal(tip.get(i)).formatted(net.minecraft.util.Formatting.YELLOW) : Text.literal(tip.get(i)).formatted(net.minecraft.util.Formatting.GRAY));
            ctx.drawTooltip(tr, lines, mouseX, mouseY);
        }
        if (chosenAt >= 0 && now() - chosenAt > 16) close();
    }

    /** 불타는 카드: 아래에서 2픽셀씩 타 들어가며 불씨(2x2)가 튐 */
    private void drawBurning(DrawContext ctx, TextRenderer tr, JsonObject c, int x, int y, float p) {
        int line = ((int) (CH * (1 - Math.min(1, p * 1.15f)))) & ~1;
        if (line > 0) {
            ctx.enableScissor((int) (ox + (x - 1) * sc), (int) (oy + (y - 1) * sc), (int) (ox + (x + CW + 1) * sc), (int) (oy + (y + line) * sc));
            drawCard(ctx, tr, c, x, y, true, false, 1f);
            ctx.disableScissor();
        }
        int[] fire = {0xFFFFFF55, 0xFFFFAA00, 0xFFFF5500, 0xFF3A3A3A};
        for (int k = 0; k < 4; k++) ctx.fill(x, y + line - 2 * k - 2, x + CW, y + line - 2 * k, Ui.alpha(fire[k], 0.9f - k * 0.2f));
        Random r = new Random(c.get("id").getAsString().hashCode());
        for (int k = 0; k < 30; k++) {
            int ex = x + (r.nextInt(CW / 2)) * 2;
            int ey = y + line - 2 - (((int) (p * 70 * (0.6f + r.nextFloat())) + r.nextInt(12)) & ~1);
            ctx.fill(ex, ey, ex + 2, ey + 2, Ui.alpha(fire[r.nextInt(3)], 1 - p));
        }
    }

    /** 카드 뒷면 (도트 텍스처) + 가운데 엔더의 눈 */
    static void drawBack(DrawContext ctx, int x, int y, float alpha) {
        Ui.tex(ctx, "card_back", x, y, CW, CH, alpha);
        Ui.item(ctx, new ItemStack(Items.ENDER_EYE), x + CW / 2 - 16, y + CH / 2 - 16, 2);
    }

    /** 등급 테두리: 위왼쪽 밝게 · 아래오른쪽 어둡게 · 바탕색 한 줄 (바닐라 상자와 같은 깎은 모서리) */
    static void cardFrame(DrawContext ctx, int x, int y, int r) {
        int hl = r == 2 ? 0xFFFFFFA0 : r == 1 ? 0xFFB5FFF6 : 0xFFFFFFFF;
        int base = r == 2 ? 0xFFFCDB43 : r == 1 ? 0xFF4AEDD9 : Ui.BG;
        int sh = r == 2 ? 0xFFA06A10 : r == 1 ? 0xFF1F8A80 : Ui.SH;
        int w = CW, h = CH;
        ctx.fill(x + 2, y, x + w - 2, y + 1, Ui.BLACK);
        ctx.fill(x + 2, y + h - 1, x + w - 2, y + h, Ui.BLACK);
        ctx.fill(x, y + 2, x + 1, y + h - 2, Ui.BLACK);
        ctx.fill(x + w - 1, y + 2, x + w, y + h - 2, Ui.BLACK);
        ctx.fill(x + 1, y + 1, x + 2, y + 2, Ui.BLACK); ctx.fill(x + w - 2, y + 1, x + w - 1, y + 2, Ui.BLACK);
        ctx.fill(x + 1, y + h - 2, x + 2, y + h - 1, Ui.BLACK); ctx.fill(x + w - 2, y + h - 2, x + w - 1, y + h - 1, Ui.BLACK);
        ctx.fill(x + 1, y + 2, x + w - 1, y + h - 2, base);
        ctx.fill(x + 2, y + 1, x + w - 2, y + h - 1, base);
        ctx.fill(x + 2, y + 1, x + w - 3, y + 2, hl);
        ctx.fill(x + 1, y + 2, x + 2, y + h - 3, hl);
        ctx.fill(x + 3, y + h - 2, x + w - 2, y + h - 1, sh);
        ctx.fill(x + w - 2, y + 3, x + w - 1, y + h - 2, sh);
        ctx.fill(x + 3, y + 3, x + w - 3, y + h - 3, Ui.BG);
        if (r > 0) {   // 안쪽 한 줄: 회색 상자처럼 위왼쪽 흰색 · 아래오른쪽 어두운색
            ctx.fill(x + 3, y + 3, x + w - 4, y + 4, Ui.HL);
            ctx.fill(x + 3, y + 3, x + 4, y + h - 4, Ui.HL);
            ctx.fill(x + 4, y + h - 4, x + w - 3, y + h - 3, Ui.SH);
            ctx.fill(x + w - 4, y + 4, x + w - 3, y + h - 3, Ui.SH);
        }
        if (r == 2) {   // 전설: 테두리에 반짝이는 점
            long tk = System.currentTimeMillis() / 120;
            Random rnd = new Random(tk);
            for (int k = 0; k < 3; k++) {
                boolean side = rnd.nextBoolean();
                int px = side ? x + 1 + rnd.nextInt(w - 2) : (rnd.nextBoolean() ? x + 1 : x + w - 2);
                int py = side ? (rnd.nextBoolean() ? y + 1 : y + h - 2) : y + 1 + rnd.nextInt(h - 2);
                ctx.fill(px, py, px + 1, py + 1, 0xFFFFFFFF);
            }
        }
    }

    /** 카드 앞면 (성장 창의 카드 탭에서도 씀) */
    static void drawCard(DrawContext ctx, TextRenderer tr, JsonObject c, int x, int y, boolean burnHover, boolean hover, float alpha) {
        int r = c.get("rarity").getAsInt();
        String id = c.get("id").getAsString(), group = c.get("group").getAsString();
        cardFrame(ctx, x, y, r);
        // 이름
        String name = c.get("name").getAsString();
        Ui.centered(ctx, tr, name, x + CW / 2, y + 5, r == 2 ? 0xFF7A4A00 : r == 1 ? 0xFF006A6A : Ui.TEXT, false);
        // 그림 칸: 움푹한 칸 + 분류별 도트 배경 + 아이템 3배
        Ui.inset(ctx, x + 7, y + 15, 86, 54, Ui.SLOT);
        Ui.tex(ctx, artOf(group), x + 8, y + 16, 84, 52);
        Ui.item(ctx, icon(id, r), x + CW / 2 - 24, y + 18, 3);
        if (hover && !burnHover) ctx.fill(x + 8, y + 16, x + 92, y + 68, 0x40FFFFFF);
        // 분류·등급
        String g = group + " · " + (r == 2 ? "전설" : r == 1 ? "희귀" : "일반");
        Ui.centered(ctx, tr, g, x + CW / 2, y + 72, r == 2 ? 0xFFB07800 : r == 1 ? 0xFF008B8B : Ui.TEXT_DIM, false);
        Ui.groove(ctx, x + 7, y + 82, CW - 14);
        // 효과 (최대 3줄) + 조건 (아래)
        Ui.wrapCentered(ctx, tr, c.get("effect").getAsString(), x + CW / 2, y + 87, CW - 12, Ui.TEXT, 3);
        String cond = c.get("cond").getAsString();
        int cl = Math.min(2, Ui.lines(tr, cond, CW - 12));
        Ui.wrapCentered(ctx, tr, cond, x + CW / 2, y + CH - 13 - (cl - 1) * 9, CW - 12, Ui.TEXT_DIM, 2);
        if (burnHover) {   // 태울 카드: 붉게 + 아래에서 일렁이는 도트 불
            ctx.fill(x + 1, y + 1, x + CW - 1, y + CH - 1, 0x50FF2000);
            long tk = System.currentTimeMillis() / 80;
            for (int k = 0; k < CW / 4; k++) {
                int fh = 4 + (int) (Math.abs(Math.sin(k * 1.7 + tk * 0.6)) * 10) & ~1;
                int fx = x + 2 + k * 4;
                ctx.fill(fx, y + CH - 2 - fh, fx + 4, y + CH - 2, 0xC0FF5500);
                ctx.fill(fx + 1, y + CH - 2 - fh / 2, fx + 3, y + CH - 2, 0xE0FFAA00);
            }
        }
        if (alpha < 1f) ctx.fill(x, y, x + CW, y + CH, Ui.alpha(0xFF101010, (1 - alpha) * 0.85f));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (hits.click((mouseX - ox) / sc, (mouseY - oy) / sc, button)) return true;
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
