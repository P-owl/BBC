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
import net.minecraft.util.Identifier;
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
    /** 자막 한 토막: at 틱부터 앞 토막 뒤에 이어 붙어 한 글자씩 나옴 */
    record Line(int at, String text) {}
    record Shot(int len, int blendOut, boolean lock, String title, String sub, int titleAt, Line[] lines, Key... keys) {}
    private static final Line[] NO_LINES = {};

    private static Key k(int t, double f, double u, double r, double lf, double lu, double lr) { return new Key(t, f, u, r, lf, lu, lr, false, false); }
    private static Key cut(int t, double f, double u, double r, double lf, double lu, double lr) { return new Key(t, f, u, r, lf, lu, lr, true, false); }
    private static Key kT(int t, double f, double u, double r, double lu) { return new Key(t, f, u, r, 0, lu, 0, false, true); }
    private static Key cutT(int t, double f, double u, double r, double lu) { return new Key(t, f, u, r, 0, lu, 0, true, true); }

    private static final Map<String, Shot> SHOTS = new HashMap<>();
    static {
        // 연금술사 각성 (170틱 ≈ 8.5초, 참고: 다른 게임의 에드워드 연출 + 각성 음성 길이)
        //   먼지 낀 발 → 얼굴까지 천천히 훑어 올라감 → 얼굴 클로즈업(합장 손뼉, 대사) → 앞 위에서 두 팔을 들어 땅을 내리치는 모습 →
        //   바로 위에서 내려다본 거대한 연성진 → 멀리서 사방으로 가시 숲이 솟는 장면 → 가시 숲 속에서 일어나는 모습(낮은 앞) →
        //   한 발 내딛으며 손바닥을 내미는 모습으로 천천히 다가감
        SHOTS.put("al_awaken", new Shot(170, 10, true, "손뼉 연성", "연금술사 · 각성", 118, NO_LINES,
                cut(0, 1.25, 0.15, 0.35, 0, 0.2, 0),
                k(24, 1.4, 0.45, 0.32, 0, 0.5, 0),
                k(34, 1.35, 1.6, 0.15, 0, 1.55, 0),
                k(58, 1.15, 1.62, 0.08, 0, 1.58, 0),
                cut(60, 3.0, 2.6, 1.0, 0, 1.0, 0),
                k(67, 3.3, 2.9, 1.1, 0, 0.8, 0),
                cut(68, 0.6, 7.5, 0.4, 0, 0, 0),
                k(80, 0.8, 8.5, 0.5, 0, 0, 0),
                cut(81, 8.0, 1.2, 3.5, 0, 1.6, 0),
                k(100, 9.0, 1.6, 1.0, 0, 1.2, 0),
                cut(101, 2.4, 0.6, 0.9, 0, 1.0, 0),
                k(118, 2.8, 1.3, 0.5, 0, 1.4, 0),
                k(136, 2.2, 1.45, 0.25, 0, 1.45, 0),
                k(160, 1.6, 1.5, 0.15, 0, 1.45, 0)));
        // 연금술사 각성기 '대지의 심판' (100틱, 대사 두 토막에 맞춤): 아래에서 두 팔을 든 모습을 천천히 돌아봄 → 손뼉 클로즈업 →
        //   높이 올라가 대상 위로 바위 손이 덮치는 장면. 기술 이름 대신 대사 자막
        SHOTS.put("al_ult", new Shot(100, 10, true, null, null, 0,
                new Line[]{new Line(4, "망할 진리와 함께.."), new Line(57, " 네놈들도 날려주마!")},
                cut(0, 2.2, 0.6, 0.9, 0, 1.8, 0),
                k(30, 2.6, 0.8, -0.6, 0, 1.8, 0),
                k(48, 2.0, 1.2, -0.3, 0, 1.5, 0),
                cut(50, 1.4, 1.5, 0.2, 0, 1.3, 0),
                k(59, 1.5, 1.4, 0.3, 0, 1.0, 0),
                cutT(60, -3.2, 6.5, 2.4, 0.6),
                kT(90, -4.6, 8.5, 3.6, 0.6)));
        // 가시밭 마무리: 시전자 뒤 낮은 곳에서 가시 숲 위로 날아가는 대상을 올려다봄
        SHOTS.put("al_forest", new Shot(22, 7, true, null, null, 0, NO_LINES,
                cutT(0, -1.7, 0.7, 0.7, 1.0),
                kT(16, -2.4, 0.5, 1.1, 1.0)));
    }

    private static Shot shot;
    private static int actorId, targetId;
    private static double clock;   // 클라이언트 틱
    private static double start;
    private static float baseYaw, basePitch;
    private static Vec3d lastActor, lastTarget;

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(CutscenePayload.ID, (p, ctx) -> ctx.client().execute(() -> {
            if (p.shot().startsWith("#music:")) music(ctx.client(), p.shot().substring(7));
            else if (p.shot().equals("#music_stop")) music(ctx.client(), null);
            else play(ctx.client(), p);
        }));
        ClientTickEvents.END_CLIENT_TICK.register(c -> {
            clock++;
            if (shot != null && clock - start > shot.len()) shot = null;
            // 시전자 본인이면 컷신 동안 시선을 고정 (마우스로 돌려도 몸이 카메라 대본에서 벗어나지 않게)
            if (shot != null && shot.lock() && c.player != null && c.player.getId() == actorId) {
                c.player.setYaw(baseYaw);
                c.player.setPitch(basePitch);
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> { shot = null; music(c, null); });
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
        basePitch = a.getPitch();
        lastActor = a.getPos();
        Entity t = targetId >= 0 ? c.world.getEntityById(targetId) : null;
        lastTarget = t != null ? t.getPos() : null;
    }

    /** 각성 테마곡 등: 서버가 "#music:<소리 id>" / "#music_stop" 으로 보냄. 바닐라 배경음은 멈추고 음악 볼륨으로 틂 */
    private static net.minecraft.client.sound.SoundInstance musicNow;
    private static void music(MinecraftClient c, String id) {
        if (musicNow != null) { c.getSoundManager().stop(musicNow); musicNow = null; }
        if (id == null) return;
        Identifier sid = Identifier.tryParse(id);
        if (sid == null) return;
        c.getMusicTracker().stop();
        musicNow = net.minecraft.client.sound.PositionedSoundInstance.music(net.minecraft.sound.SoundEvent.of(sid));
        c.getSoundManager().play(musicNow);
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
        if (s.lines().length > 0) subtitle(ctx, s, t, W, H, bar, out);
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

    /** 대사 자막: 아래 검은 띠 가운데, 흰 글씨. 토막마다 정해진 틱부터 한 글자씩 (틱당 0.6자) 이어 붙음 */
    private static void subtitle(DrawContext ctx, Shot s, double t, int W, int H, int bar, double out) {
        StringBuilder b = new StringBuilder();
        for (Line l : s.lines()) {
            if (t < l.at()) break;
            int n = (int) Math.min(l.text().length(), Math.floor((t - l.at()) * 0.6) + 1);
            b.append(l.text(), 0, n);
        }
        if (b.length() == 0) return;
        int alpha = (int) (255 * Math.max(0, Math.min(1, out)));
        if (alpha < 8) return;
        var tr = MinecraftClient.getInstance().textRenderer;
        Text line = Text.literal(b.toString());
        float sc = 1.5f;
        ctx.getMatrices().push();
        ctx.getMatrices().translate(W / 2f, H - bar / 2f - 4 * sc, 0);
        ctx.getMatrices().scale(sc, sc, 1);
        ctx.drawText(tr, line, -tr.getWidth(line) / 2, 0, (alpha << 24) | 0xFFFFFF, true);
        ctx.getMatrices().pop();
    }
}
