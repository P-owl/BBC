package bbc.client;

import bbc.net.CutscenePayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;

/**
 * 컷신 (각성·각성기 연출). 서버가 CutscenePayload 로 대본 이름을 보내면 그 동안 카메라를 대본대로 움직이고
 * 위아래 검은 띠 + 기술 이름을 띄운다. 카메라는 CameraMixin 이 매 프레임 여기서 받아 감.
 *
 * 대본 = 키 목록. 위치는 시전자 기준 (앞 f, 위 u, 오른쪽 r) — 시전자를 따라다니고 방향은 시작할 때 시선으로 고정.
 * look 도 같은 기준이고, lookTarget 이면 대상의 발 + (0, u, 0) 을 바라봄. cut = 앞 키에서 이어지지 않고 뚝 끊어 넘어감.
 * 끝에서 blendOut 틱 동안 원래 카메라로 부드럽게 돌아옴. lock = 그 동안 움직임 입력을 막음
 */
public final class Cutscene {
    private Cutscene() {}

    record Key(int t, double f, double u, double r, double lf, double lu, double lr, boolean cut, boolean lookTarget) {}
    record Shot(int len, int blendOut, boolean lock, String title, String sub, int titleAt, Key... keys) {}

    private static Key k(int t, double f, double u, double r, double lf, double lu, double lr) { return new Key(t, f, u, r, lf, lu, lr, false, false); }
    private static Key cut(int t, double f, double u, double r, double lf, double lu, double lr) { return new Key(t, f, u, r, lf, lu, lr, true, false); }
    private static Key kT(int t, double f, double u, double r, double lu) { return new Key(t, f, u, r, 0, lu, 0, false, true); }
    private static Key cutT(int t, double f, double u, double r, double lu) { return new Key(t, f, u, r, 0, lu, 0, true, true); }

    private static final Map<String, Shot> SHOTS = new HashMap<>();
    static {
        // 연금술사 각성 (ABA: 오른 어깨 너머 오토메일 팔 클로즈업 → 앞에서 코트가 찢겨 날아감)
        SHOTS.put("al_awaken", new Shot(40, 9, true, "손뼉 연성", "연금술사 · 각성", 15,
                cut(0, -0.85, 1.75, 0.8, 0.9, 1.3, 0.35),
                k(13, -0.6, 1.6, 0.95, 0.9, 1.25, 0.3),
                cut(14, 2.3, 1.35, 0.7, 0, 1.15, 0),
                k(31, 3.4, 1.7, -0.5, 0, 1.2, 0)));
        // 연금술사 각성기 '대지의 심판' (아래에서 손을 드는 모습 → 높이 올라가 대상 위로 바위 손이 덮치는 장면)
        SHOTS.put("al_ult", new Shot(44, 10, true, "대지의 심판", "연금술사 · 각성기", 3,
                cut(0, 2.2, 0.7, 0.9, 0, 1.7, 0),
                k(10, 1.7, 0.9, 0.5, 0, 1.4, 0),
                cutT(11, -3.2, 6.5, 2.4, 0.6),
                kT(34, -4.6, 8.5, 3.6, 0.6)));
        // 가시밭 마무리: 시전자 뒤 낮은 곳에서 가시 숲 위로 날아가는 대상을 올려다봄
        SHOTS.put("al_forest", new Shot(22, 7, true, null, null, 0,
                cutT(0, -1.7, 0.7, 0.7, 1.0),
                kT(16, -2.4, 0.5, 1.1, 1.0)));
    }

    private static Shot shot;
    private static int actorId, targetId;
    private static double clock;   // 클라이언트 틱
    private static double start;
    private static float baseYaw;
    private static Vec3d lastActor, lastTarget;

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(CutscenePayload.ID, (p, ctx) -> ctx.client().execute(() -> play(ctx.client(), p)));
        ClientTickEvents.END_CLIENT_TICK.register(c -> {
            clock++;
            if (shot != null && clock - start > shot.len()) shot = null;
        });
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> shot = null);
        HudRenderCallback.EVENT.register((ctx, counter) -> hud(ctx, counter.getTickDelta(false)));
    }

    private static void play(MinecraftClient c, CutscenePayload p) {
        Shot s = SHOTS.get(p.shot());
        if (s == null || c.world == null) return;
        Entity a = c.world.getEntityById(p.actor());
        if (a == null) return;
        shot = s;
        actorId = p.actor();
        targetId = p.target();
        start = clock;
        baseYaw = a.getYaw();
        lastActor = a.getPos();
        Entity t = targetId >= 0 ? c.world.getEntityById(targetId) : null;
        lastTarget = t != null ? t.getPos() : null;
    }

    public static boolean active() { return shot != null; }
    public static boolean locked() { return shot != null && shot.lock(); }

    private static double time(float delta) { return clock - start + delta; }

    private static Vec3d frame(Vec3d base, double f, double u, double r) {
        double yr = Math.toRadians(baseYaw);
        double fx = -Math.sin(yr), fz = Math.cos(yr);   // 앞
        double rx = -Math.cos(yr), rz = -Math.sin(yr);  // 오른쪽
        return base.add(fx * f + rx * r, u, fz * f + rz * r);
    }

    private static Vec3d[] eval(Key key, Vec3d actor, Vec3d target) {
        Vec3d cam = frame(actor, key.f(), key.u(), key.r());
        Vec3d look = key.lookTarget() && target != null ? target.add(0, key.lu(), 0)
                : frame(actor, key.lookTarget() ? 2.5 : key.lf(), key.lu(), key.lookTarget() ? 0 : key.lr());
        return new Vec3d[]{cam, look};
    }

    /** CameraMixin 이 부름: 원래 카메라(pos, yaw, pitch)를 받아 컷신 카메라 {x, y, z, yaw, pitch} 를 돌려줌. 컷신이 아니면 null */
    public static double[] camera(Vec3d vPos, float vYaw, float vPitch, float delta) {
        Shot s = shot;
        MinecraftClient c = MinecraftClient.getInstance();
        if (s == null || c.world == null) return null;
        double t = time(delta);
        if (t > s.len()) return null;
        Entity a = c.world.getEntityById(actorId);
        if (a != null) lastActor = a.getLerpedPos(delta);
        Entity tg = targetId >= 0 ? c.world.getEntityById(targetId) : null;
        if (tg != null) lastTarget = tg.getLerpedPos(delta);
        Key[] ks = s.keys();
        int i = 0;
        while (i + 1 < ks.length && ks[i + 1].t() <= t) i++;
        Vec3d[] cur = eval(ks[i], lastActor, lastTarget);
        if (i + 1 < ks.length && !ks[i + 1].cut()) {
            Vec3d[] nxt = eval(ks[i + 1], lastActor, lastTarget);
            double f = smooth((t - ks[i].t()) / Math.max(1e-3, ks[i + 1].t() - ks[i].t()));
            cur = new Vec3d[]{cur[0].lerp(nxt[0], f), cur[1].lerp(nxt[1], f)};
        }
        Vec3d d = cur[1].subtract(cur[0]);
        double yaw = Math.toDegrees(Math.atan2(-d.x, d.z));
        double pitch = -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        Vec3d pos = cur[0];
        double bo = s.len() - s.blendOut();
        if (t > bo) {   // 원래 카메라로 돌아옴
            double w = smooth((t - bo) / s.blendOut());
            pos = pos.lerp(vPos, w);
            yaw = yaw + MathHelper.wrapDegrees(vYaw - yaw) * w;
            pitch = pitch + (vPitch - pitch) * w;
        }
        return new double[]{pos.x, pos.y, pos.z, yaw, pitch};
    }

    private static double smooth(double x) { x = MathHelper.clamp(x, 0, 1); return x * x * (3 - 2 * x); }

    /** 위아래 검은 띠 + 기술 이름 */
    private static void hud(DrawContext ctx, float delta) {
        Shot s = shot;
        if (s == null) return;
        double t = time(delta);
        int W = ctx.getScaledWindowWidth(), H = ctx.getScaledWindowHeight();
        double in = smooth(t / 5.0), out = smooth((s.len() - t) / 6.0);
        int bar = (int) Math.round(H * 0.13 * Math.min(in, out));
        if (bar > 0) {
            ctx.fill(0, 0, W, bar, 0xFF000000);
            ctx.fill(0, H - bar, W, H, 0xFF000000);
        }
        if (s.title() == null || t < s.titleAt()) return;
        double a = Math.min(smooth((t - s.titleAt()) / 4.0), out);
        int alpha = (int) (a * 255);
        if (alpha < 8) return;
        var tr = MinecraftClient.getInstance().textRenderer;
        // 기술 이름: 아래 띠 위쪽에 크게, 금색. 살짝 오른쪽에서 밀려 들어옴
        float slide = (float) ((1 - smooth((t - s.titleAt()) / 6.0)) * 24);
        ctx.getMatrices().push();
        float sc = 2.4f;
        Text title = Text.literal(s.title());
        ctx.getMatrices().translate(W / 2f + slide, H - bar - 34, 0);
        ctx.getMatrices().scale(sc, sc, 1);
        int tw = tr.getWidth(title);
        ctx.drawText(tr, title, -tw / 2 + 1, 1, (alpha << 24) | 0x3A2A08, false);
        ctx.drawText(tr, title, -tw / 2, 0, (alpha << 24) | 0xF0C850, false);
        ctx.getMatrices().pop();
        if (s.sub() != null) {
            Text sub = Text.literal(s.sub());
            ctx.drawText(tr, sub, (int) (W / 2f + slide) - tr.getWidth(sub) / 2, H - bar - 10, (alpha << 24) | 0xE6E6E6, true);
        }
        // 금색 가는 줄
        int lw = (int) (W * 0.28 * a);
        ctx.fill(W / 2 - lw, H - bar - 13, W / 2 + lw, H - bar - 12, (alpha << 24) | 0x8C6A1E);
    }
}
