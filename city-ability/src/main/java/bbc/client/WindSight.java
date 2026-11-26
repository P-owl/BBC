package bbc.client;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;

/**
 * 기둥 속 사내 · 바람 각성 '바람만을 느껴 사물을 보겠다' 중 내 화면: 푸르게 물들고 가장자리가 어두워지며,
 * 32칸 안 생물이 윤곽으로 보임 (MinecraftClientMixin.hasOutline). 서버가 2초마다 "#windsight:on" 을 보내고, 3초 소식이 없으면 저절로 꺼짐
 */
public final class WindSight {
    private WindSight() {}
    private static long until;
    private static float fade;

    public static void set(boolean on) { until = on ? System.currentTimeMillis() + 3000 : 0; }
    public static boolean on() { return System.currentTimeMillis() < until; }

    /** 윤곽을 그릴 생물인지 */
    public static boolean outline(Entity e) {
        if (!on() || !(e instanceof LivingEntity) || !e.isAlive()) return false;
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.player != null && e != mc.player && e.squaredDistanceTo(mc.player) < 32 * 32;
    }

    public static void init() {
        HudRenderCallback.EVENT.register((ctx, counter) -> render(ctx));
    }

    private static void render(DrawContext ctx) {
        fade = on() ? Math.min(1f, fade + 0.05f) : Math.max(0f, fade - 0.05f);
        if (fade <= 0) return;
        int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();
        int a = (int) (fade * 70);
        ctx.fill(0, 0, w, h, (a << 24) | 0x1E5A78);                       // 푸른 바람 빛
        int edge = Math.max(12, w / 10), ea = (int) (fade * 120);
        for (int i = 0; i < edge; i += 2) {                                 // 가장자리로 갈수록 어둡게
            int al = (int) (ea * (1 - i / (float) edge));
            int c = (al << 24) | 0x081820;
            ctx.fill(i, 0, i + 2, h, c); ctx.fill(w - i - 2, 0, w - i, h, c);
            ctx.fill(0, i, w, i + 2, c); ctx.fill(0, h - i - 2, w, h - i, c);
        }
    }
}
