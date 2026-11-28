package bbc.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 완전생물 이펙트 (클라이언트에서 그리는 모델 · 빛줄기). 서버가 "#ulfx:종류:값,…" 을 보내면 (MotionPayload — 시전자를 보는 사람 모두에게) 세상에 그림.
 * 참고: 올스타 배틀 R 카즈 영상의 파문(금빛·푸른 지직거리는 번개 + 충격 고리), 생물 창조(발밑에서 솟는 검은 가시, 기어 붙는 생물), 변형 팔(살점) — 원리만, 우리 모델.
 *   zap    x,y,z,size            파문이 터짐: 사방으로 갈라지는 번개 (2틱마다 다시 그어 깜빡임) + 가운데 섬광 + 퍼지는 고리
 *   shock  id,ticks              파문 마비: 몸 위를 번개가 기어다님
 *   charge ticks                 파문을 모음: 두 손 둘레에 작은 번개 + 빛 (보낸 사람 = 시전자)
 *   arc    x,y,z,x2,y2,z2        두 점 사이 굵은 번개 (손 → 상대)
 *   wave   x,y,z,radius          땅을 따라 퍼지는 파문 고리 + 고리 앞 번개
 *   crit   x,y,z,id,dx,dz,delay  생물 하나: 땅을 기어 id 에게 → 붙어서 물어뜯음 (id -1 = 앞으로 기어가다 땅으로 숨음)
 *   thorn  x,y,z                 발밑에서 검은 가시가 솟음
 *   flesh  x,y,z,size            살점이 튐
 *   drain  id,ticks              id 에게서 시전자에게 붉은 생명 줄기가 흘러듦
 * 날개 끝 바람 자국은 UltimateRender 가 날개를 그릴 때 날개 끝 자리를 알려 줌 (trail)
 */
public final class UltimateFx {
    private UltimateFx() {}

    private static final Identifier TEX = Identifier.of("bbc", "textures/entity/ultimate.png");
    private static final List<Fx> LIVE = new ArrayList<>();
    private static final Random R = new Random();

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(c -> {
            if (c.world == null || c.isPaused()) return;
            synchronized (LIVE) {
                for (Iterator<Fx> it = LIVE.iterator(); it.hasNext(); ) {
                    Fx f = it.next();
                    f.age++;
                    f.tick(c.world);
                    if (f.age >= f.ttl) it.remove();
                }
            }
            long now = c.world.getTime();
            TRAILS.values().forEach(d -> { while (!d.isEmpty() && now - d.peekFirst().t > 10) d.pollFirst(); });
            TRAILS.values().removeIf(ArrayDeque::isEmpty);
        });
        WorldRenderEvents.AFTER_ENTITIES.register(UltimateFx::render);
    }

    public static void clear() { synchronized (LIVE) { LIVE.clear(); } TRAILS.clear(); }

    /** "#ulfx:…" 처리했으면 true */
    public static boolean handle(int sender, String msg) {
        if (!msg.startsWith("#ulfx:")) return false;
        try {
            String[] kv = msg.substring(6).split(":", 2);
            String[] a = kv.length > 1 ? kv[1].split(",") : new String[0];
            Fx f = switch (kv[0]) {
                case "zap" -> new Zap(v(a, 0), f(a, 3));
                case "shock" -> new Shock(i(a, 0), i(a, 1));
                case "charge" -> new Charge(sender, i(a, 0));
                case "arc" -> new Arc(v(a, 0), v(a, 3));
                case "wave" -> new Wave(v(a, 0), f(a, 3));
                case "crit" -> new Crit(v(a, 0), i(a, 3), new Vec3d(f(a, 4), 0, f(a, 5)), i(a, 6));
                case "thorn" -> new Thorn(v(a, 0));
                case "flesh" -> new Flesh(v(a, 0), f(a, 3));
                case "drain" -> new Drain(i(a, 0), sender, i(a, 1));
                default -> null;
            };
            if (f != null) synchronized (LIVE) { if (LIVE.size() < 400) LIVE.add(f); }
        } catch (RuntimeException ignored) { }
        return true;
    }
    private static float f(String[] a, int k) { return Float.parseFloat(a[k]); }
    private static int i(String[] a, int k) { return Integer.parseInt(a[k]); }
    private static Vec3d v(String[] a, int k) { return new Vec3d(f(a, k), f(a, k + 1), f(a, k + 2)); }

    // ───────── 날개 끝 바람 자국 ─────────
    record TP(Vec3d p, long t) {}
    private static final Map<Long, ArrayDeque<TP>> TRAILS = new ConcurrentHashMap<>();
    /** side: 0 오른쪽 · 1 왼쪽 */
    public static void trail(int entity, int side, Vec3d p) {
        ClientWorld w = MinecraftClient.getInstance().world;
        if (w == null) return;
        ArrayDeque<TP> d = TRAILS.computeIfAbsent(((long) entity << 1) | side, k -> new ArrayDeque<>());
        long now = w.getTime();
        if (!d.isEmpty() && d.peekLast().t == now) d.pollLast();
        d.addLast(new TP(p, now));
        while (d.size() > 12) d.pollFirst();
    }

    // ═════════ 그리기 ═════════
    private static Vec3d cam;
    private static float delta;

    private static void render(WorldRenderContext ctx) {
        VertexConsumerProvider vcp = ctx.consumers();
        if (vcp == null || ctx.world() == null) return;
        cam = ctx.camera().getPos();
        delta = ctx.tickCounter().getTickDelta(false);
        MatrixStack ms = ctx.matrixStack();
        ms.push();
        ms.translate(-cam.x, -cam.y, -cam.z);
        VertexConsumer glow = vcp.getBuffer(RenderLayer.getLightning());
        Matrix4f m = ms.peek().getPositionMatrix();
        long now = ctx.world().getTime();
        for (ArrayDeque<TP> d : TRAILS.values()) {   // 바람 자국: 오래될수록 옅고 가늘게
            TP prev = null;
            for (TP q : d) {
                if (prev != null) {
                    float k = 1f - Math.min(1f, (now - q.t + delta) / 10f);
                    ribbon(glow, m, prev.p, q.p, 0.14f * k, 0.85f, 0.9f, 1f, 0.35f * k);
                }
                prev = q;
            }
        }
        synchronized (LIVE) {
            for (Fx f : LIVE) f.glow(glow, m, ctx.world());
            // 빛을 다 그린 뒤에 모델 층으로 (층을 바꾸면 앞 층은 그려져 끝남 — 다시 쓰면 안 됨)
            VertexConsumer solid = vcp.getBuffer(RenderLayer.getEntityCutoutNoCull(TEX));
            for (Fx f : LIVE) f.solid(ms, solid, ctx.world());
        }
        ms.pop();
    }

    abstract static class Fx {
        int age, ttl;
        Fx(int ttl) { this.ttl = ttl; }
        float life() { return Math.min(1f, (age + delta) / ttl); }
        void tick(ClientWorld w) {}
        void glow(VertexConsumer vc, Matrix4f m, ClientWorld w) {}
        void solid(MatrixStack ms, VertexConsumer vc, ClientWorld w) {}
    }

    // ───────── 빛 도구 ─────────
    // 참고 영상의 파문: 번개는 가늘고 옅은 청백색 (금빛 아님) · 맞은 자리는 카메라를 향한 둥근 물결 고리 · 금빛은 맞을 때 뻗는 가는 빛줄기 · 큰 타격만 노란 섬광
    static final float[] GOLD = {1f, 0.8f, 0.3f}, ORANGE = {1f, 0.62f, 0.18f}, CORE = {0.92f, 0.98f, 1f}, BLUE = {0.5f, 0.82f, 1f}, PALE = {0.75f, 0.9f, 1f}, RING = {0.5f, 0.75f, 1f}, RED = {1f, 0.12f, 0.08f};

    /** 카메라를 보는 띠 (양면) */
    static void ribbon(VertexConsumer vc, Matrix4f m, Vec3d a, Vec3d b, float w, float r, float g, float bl, float al) {
        Vec3d d = b.subtract(a);
        Vec3d view = a.add(b).multiply(0.5).subtract(cam);
        Vec3d s = d.crossProduct(view);
        if (s.lengthSquared() < 1e-9) return;
        s = s.normalize().multiply(w / 2);
        quad(vc, m, a.subtract(s), a.add(s), b.add(s), b.subtract(s), r, g, bl, al, al, al, al);
    }
    static void quad(VertexConsumer vc, Matrix4f m, Vec3d p0, Vec3d p1, Vec3d p2, Vec3d p3, float r, float g, float b, float a0, float a1, float a2, float a3) {
        put(vc, m, p0, r, g, b, a0); put(vc, m, p1, r, g, b, a1); put(vc, m, p2, r, g, b, a2); put(vc, m, p3, r, g, b, a3);
        put(vc, m, p3, r, g, b, a3); put(vc, m, p2, r, g, b, a2); put(vc, m, p1, r, g, b, a1); put(vc, m, p0, r, g, b, a0);
    }
    static void put(VertexConsumer vc, Matrix4f m, Vec3d p, float r, float g, float b, float a) {
        vc.vertex(m, (float) p.x, (float) p.y, (float) p.z).color(r, g, b, a);
    }

    /** 번개 한 줄기: a→b 를 가운데 점 흔들기로 꺾음 (+ 곁가지) */
    static List<Vec3d> bolt(Vec3d a, Vec3d b, double jag, int depth, Random r) {
        List<Vec3d> pts = new ArrayList<>(List.of(a, b));
        double j = jag;
        for (int d = 0; d < depth; d++) {
            List<Vec3d> nx = new ArrayList<>();
            for (int i = 0; i + 1 < pts.size(); i++) {
                Vec3d p = pts.get(i), q = pts.get(i + 1);
                nx.add(p);
                Vec3d mid = p.add(q).multiply(0.5).add(r.nextGaussian() * j, r.nextGaussian() * j, r.nextGaussian() * j);
                nx.add(mid);
            }
            nx.add(pts.get(pts.size() - 1));
            pts = nx;
            j *= 0.55;
        }
        return pts;
    }
    static void drawBolt(VertexConsumer vc, Matrix4f m, List<Vec3d> pts, float w, float[] col, float al) {
        for (int i = 0; i + 1 < pts.size(); i++) {
            ribbon(vc, m, pts.get(i), pts.get(i + 1), w * 3.2f, col[0], col[1], col[2], al * 0.35f);   // 번짐
            ribbon(vc, m, pts.get(i), pts.get(i + 1), w, CORE[0], CORE[1], CORE[2], al);              // 심
        }
    }
    /** 섬광 (가운데 밝고 가장자리 투명한 별) */
    static void blob(VertexConsumer vc, Matrix4f m, Vec3d c, float rad, float[] col, float al) {
        if (rad <= 0.01f) return;
        Vec3d f = c.subtract(cam).normalize();
        Vec3d rt = f.crossProduct(new Vec3d(0, 1, 0));
        if (rt.lengthSquared() < 1e-6) rt = new Vec3d(1, 0, 0);
        rt = rt.normalize();
        Vec3d up = rt.crossProduct(f).normalize();
        int n = 12;
        for (int i = 0; i < n; i++) {
            double a0 = Math.PI * 2 * i / n, a1 = Math.PI * 2 * (i + 1) / n;
            double r0 = rad * (i % 2 == 0 ? 1.0 : 0.55), r1 = rad * ((i + 1) % 2 == 0 ? 1.0 : 0.55);
            Vec3d e0 = c.add(rt.multiply(Math.cos(a0) * r0)).add(up.multiply(Math.sin(a0) * r0));
            Vec3d e1 = c.add(rt.multiply(Math.cos(a1) * r1)).add(up.multiply(Math.sin(a1) * r1));
            quad(vc, m, c, e0, e1, e1, col[0], col[1], col[2], al, 0, 0, 0);
        }
    }
    /** 카메라를 향한 둥근 고리 (테두리가 밝고 안쪽은 옅게 차오름 — 물결/구 느낌) */
    static void cring(VertexConsumer vc, Matrix4f m, Vec3d c, double rad, double width, float[] col, float rim, float fill) {
        Vec3d f = c.subtract(cam).normalize();
        Vec3d rt = f.crossProduct(new Vec3d(0, 1, 0));
        if (rt.lengthSquared() < 1e-6) rt = new Vec3d(1, 0, 0);
        rt = rt.normalize();
        Vec3d up = rt.crossProduct(f).normalize();
        int n = 48;
        double ri = Math.max(0, rad - width), ro = rad + width * 0.6, rf = rad * 0.55;
        for (int i = 0; i < n; i++) {
            double a0 = Math.PI * 2 * i / n, a1 = Math.PI * 2 * (i + 1) / n;
            Vec3d d0 = rt.multiply(Math.cos(a0)).add(up.multiply(Math.sin(a0))), d1 = rt.multiply(Math.cos(a1)).add(up.multiply(Math.sin(a1)));
            if (fill > 0) quad(vc, m, c.add(d0.multiply(rf)), c.add(d1.multiply(rf)), c.add(d1.multiply(ri)), c.add(d0.multiply(ri)), col[0], col[1], col[2], 0, 0, fill, fill);
            quad(vc, m, c.add(d0.multiply(ri)), c.add(d1.multiply(ri)), c.add(d1.multiply(rad)), c.add(d0.multiply(rad)), col[0], col[1], col[2], fill, fill, rim, rim);
            quad(vc, m, c.add(d0.multiply(rad)), c.add(d1.multiply(rad)), c.add(d1.multiply(ro)), c.add(d0.multiply(ro)), col[0], col[1], col[2], rim, rim, 0, 0);
        }
    }
    /** 카메라를 향한 호 (a0~a1 라디안, 양끝이 옅어짐) — 여러 겹을 돌리면 소용돌이치는 물결 구 */
    static void carc(VertexConsumer vc, Matrix4f m, Vec3d c, double rad, double width, double a0, double a1, float[] col, float al) {
        Vec3d f = c.subtract(cam).normalize();
        Vec3d rt = f.crossProduct(new Vec3d(0, 1, 0));
        if (rt.lengthSquared() < 1e-6) rt = new Vec3d(1, 0, 0);
        rt = rt.normalize();
        Vec3d up = rt.crossProduct(f).normalize();
        int n = Math.max(6, (int) ((a1 - a0) / (Math.PI * 2) * 48));
        for (int i = 0; i < n; i++) {
            double u0 = i / (double) n, u1 = (i + 1) / (double) n;
            double b0 = a0 + (a1 - a0) * u0, b1 = a0 + (a1 - a0) * u1;
            float e0 = (float) Math.sin(Math.PI * u0) * al, e1 = (float) Math.sin(Math.PI * u1) * al;
            Vec3d d0 = rt.multiply(Math.cos(b0)).add(up.multiply(Math.sin(b0))), d1 = rt.multiply(Math.cos(b1)).add(up.multiply(Math.sin(b1)));
            double ri = rad - width, ro = rad + width;
            quad(vc, m, c.add(d0.multiply(ri)), c.add(d1.multiply(ri)), c.add(d1.multiply(rad)), c.add(d0.multiply(rad)), col[0], col[1], col[2], 0, 0, e1, e0);
            quad(vc, m, c.add(d0.multiply(rad)), c.add(d1.multiply(rad)), c.add(d1.multiply(ro)), c.add(d0.multiply(ro)), col[0], col[1], col[2], e0, e1, 0, 0);
        }
    }

    /** 끝으로 갈수록 가늘어지는 곧은 빛줄기 */
    static void streak(VertexConsumer vc, Matrix4f m, Vec3d a, Vec3d b, float w, float[] col, float al) {
        Vec3d d = b.subtract(a), view = a.add(b).multiply(0.5).subtract(cam), s = d.crossProduct(view);
        if (s.lengthSquared() < 1e-9) return;
        s = s.normalize().multiply(w / 2);
        quad(vc, m, a.subtract(s), a.add(s), b, b, col[0], col[1], col[2], al, al, 0, 0);
    }

    /** 땅과 나란한 고리 (안팎이 투명) */
    static void ring(VertexConsumer vc, Matrix4f m, Vec3d c, double rad, double width, float[] col, float al) {
        int n = 40;
        for (int i = 0; i < n; i++) {
            double a0 = Math.PI * 2 * i / n, a1 = Math.PI * 2 * (i + 1) / n;
            Vec3d d0 = new Vec3d(Math.cos(a0), 0, Math.sin(a0)), d1 = new Vec3d(Math.cos(a1), 0, Math.sin(a1));
            double ri = Math.max(0, rad - width), ro = rad + width;
            quad(vc, m, c.add(d0.multiply(ri)), c.add(d1.multiply(ri)), c.add(d1.multiply(rad)), c.add(d0.multiply(rad)), col[0], col[1], col[2], 0, 0, al, al);
            quad(vc, m, c.add(d0.multiply(rad)), c.add(d1.multiply(rad)), c.add(d1.multiply(ro)), c.add(d0.multiply(ro)), col[0], col[1], col[2], al, al, 0, 0);
        }
    }
    static Vec3d rand(Random r) { Vec3d v = new Vec3d(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()); return v.lengthSquared() < 1e-6 ? new Vec3d(0, 1, 0) : v.normalize(); }

    // ───────── 파문 ─────────
    /** 파문이 터짐 (참고 3:21.4): 소용돌이치는 물결 구 (옅은 청백색 호 여러 겹이 돌며 퍼짐, 안은 옅게 차오름) + 가운데에 몰린 밝은 청백색 번개 +
     *  맞는 순간 한쪽으로 굵은 금빛 빛줄기 두셋. 큰 타격(size ≥ 1.4, 참고 3:27.7)은 노란 가시 조각이 사방으로 터지고 크고 옅은 고리가 퍼짐 */
    static final class Zap extends Fx {
        final Vec3d c; final float size; final boolean big; final Vec3d[] rays, shards; final double[][] arcs;
        List<List<Vec3d>> bolts = new ArrayList<>();
        Zap(Vec3d c, float size) {
            super(11); this.c = c; this.size = Math.max(0.3f, size); big = size >= 1.4f;
            Vec3d main = rand(R);   // 금빛 줄기는 한쪽으로 몰림
            rays = new Vec3d[big ? 4 : 2 + R.nextInt(2)];
            for (int i = 0; i < rays.length; i++) rays[i] = main.add(rand(R).multiply(0.35)).normalize().multiply((1.3 + R.nextDouble() * 0.8) * this.size);
            shards = new Vec3d[big ? 14 : 0];
            for (int i = 0; i < shards.length; i++) shards[i] = rand(R).multiply((0.9 + R.nextDouble() * 0.9) * this.size);
            arcs = new double[4][];
            for (int i = 0; i < 4; i++) arcs[i] = new double[]{0.9 + R.nextDouble() * 0.16, R.nextDouble() * Math.PI * 2, Math.PI * (1.1 + R.nextDouble() * 0.6), (R.nextBoolean() ? 1 : -1) * (0.18 + R.nextDouble() * 0.12)};
            regen();
        }
        double rad() { double x = Math.min(1, (age + delta) / 7.0); return (0.4 + 1.0 * (1 - (1 - x) * (1 - x))) * size; }
        void regen() {
            bolts.clear();
            double r = (0.4 + 0.85 * Math.min(1, age / 7.0)) * size;
            int n = 4 + (int) (3 * size);
            for (int k = 0; k < n; k++) {   // 가운데에서 시작해 고리 안쪽까지
                Vec3d p0 = c.add(rand(R).multiply(r * 0.1)), p1 = c.add(rand(R).multiply(r * (0.45 + R.nextDouble() * 0.5)));
                List<Vec3d> b = bolt(p0, p1, p0.distanceTo(p1) * 0.17, 3, R);
                bolts.add(b);
                if (R.nextBoolean()) { Vec3d q = b.get(b.size() / 2), e2 = q.add(rand(R).multiply(r * 0.3)); bolts.add(bolt(q, e2, q.distanceTo(e2) * 0.18, 2, R)); }
            }
        }
        @Override void tick(ClientWorld w) { if (age % 2 == 0) regen(); }
        @Override void glow(VertexConsumer vc, Matrix4f m, ClientWorld w) {
            float t = age + delta, k = 1f - life();
            double r = rad();
            cring(vc, m, c, r * 0.97, 0.02 * size, PALE, 0, 0.1f * k);   // 옅게 차오른 안쪽
            for (double[] a : arcs) {   // 소용돌이 호 (돌며 퍼짐)
                double st = a[1] + a[3] * t;
                carc(vc, m, c, r * a[0], 0.065 * size, st, st + a[2], RING, 0.85f * k);
                carc(vc, m, c, r * a[0] * 0.96, 0.025 * size, st + 0.3, st + a[2] * 0.8, CORE, 0.6f * k);
            }
            float bk = t < 6 ? 1f : k * 1.6f;
            for (List<Vec3d> b : bolts) drawBolt(vc, m, b, 0.016f * size, BLUE, Math.min(1f, bk));
            blob(vc, m, c, 0.35f * size * (0.5f + k * 0.5f), BLUE, 0.5f * k);
            if (t < 2) blob(vc, m, c, 0.5f * size, CORE, 0.9f * (1 - t / 2f));
            if (t < 5) {   // 금빛 빛줄기 (한쪽으로, 굵게)
                float a = 1f - t / 5f;
                for (Vec3d d : rays) streak(vc, m, c.subtract(d.multiply(0.25)), c.add(d.multiply(0.6 + t * 0.2)), 0.13f * size, GOLD, 0.95f * a);
            }
            if (big && t < 5) {   // 큰 타격: 노란 가시 조각 + 주황 가운데
                float a = 1f - t / 5f;
                for (Vec3d d : shards) streak(vc, m, c.add(d.multiply(0.15)), c.add(d.multiply(0.55 + t * 0.12)), 0.22f * size, GOLD, 0.9f * a);
                blob(vc, m, c, 0.55f * size, new float[]{1f, 0.78f, 0.25f}, 0.7f * a);
            }
            if (big) for (int i = 0; i < 2; i++) { double rr = 0.8 * size + 2.6 * size * life(); carc(vc, m, c, rr, 0.05 * size, i * 3.3 + t * 0.1, i * 3.3 + t * 0.1 + 2.6, new float[]{0.86f, 0.86f, 0.9f}, 0.5f * k); }
        }
    }

    /** 파문 마비: 몸 위를 번개가 기어다님 */
    static final class Shock extends Fx {
        final int id; List<List<Vec3d>> bolts = new ArrayList<>();
        Shock(int id, int ticks) { super(Math.min(100, ticks)); this.id = id; }
        @Override void tick(ClientWorld w) {
            if (age % 2 != 0) return;
            bolts.clear();
            Entity e = w.getEntityById(id);
            if (e == null) return;
            Box b = e.getBoundingBox().expand(0.08);
            for (int k = 0; k < 3; k++) { Vec3d p0 = onBox(b), p1 = onBox(b); bolts.add(bolt(p0, p1, p0.distanceTo(p1) * 0.12, 3, R)); }
        }
        @Override void glow(VertexConsumer vc, Matrix4f m, ClientWorld w) {
            Entity e = w.getEntityById(id);
            if (e == null) return;
            Vec3d off = e.getLerpedPos(delta).subtract(e.getPos());   // 몸을 따라감
            float k = 0.6f + 0.4f * R.nextFloat();
            for (int i = 0; i < bolts.size(); i++) {
                List<Vec3d> b = new ArrayList<>(); for (Vec3d p : bolts.get(i)) b.add(p.add(off));
                drawBolt(vc, m, b, 0.014f, i == 0 ? PALE : BLUE, k);
            }
        }
        static Vec3d onBox(Box b) {
            double x = b.minX + R.nextDouble() * (b.maxX - b.minX), y = b.minY + R.nextDouble() * (b.maxY - b.minY), z = b.minZ + R.nextDouble() * (b.maxZ - b.minZ);
            switch (R.nextInt(4)) { case 0 -> x = b.minX; case 1 -> x = b.maxX; case 2 -> z = b.minZ; default -> z = b.maxZ; }
            return new Vec3d(x, y, z);
        }
    }

    /** 파문을 모음: 두 손 둘레 */
    static final class Charge extends Fx {
        final int id; List<List<Vec3d>> bolts = new ArrayList<>();
        Charge(int id, int ticks) { super(Math.min(60, ticks)); this.id = id; }
        Vec3d hand(Entity e, int side, float d) {
            float yaw = (float) Math.toRadians(e.getYaw(d));
            Vec3d fw = new Vec3d(-Math.sin(yaw), 0, Math.cos(yaw)), rt = new Vec3d(-Math.cos(yaw), 0, -Math.sin(yaw));
            return e.getLerpedPos(d).add(0, 1.15, 0).add(fw.multiply(0.5)).add(rt.multiply(0.36 * side));
        }
        @Override void tick(ClientWorld w) {
            if (age % 2 != 0) return;
            bolts.clear();
            Entity e = w.getEntityById(id);
            if (e == null) return;
            for (int s = -1; s <= 1; s += 2) { Vec3d h = hand(e, s, 1f); for (int k = 0; k < 2; k++) { Vec3d p0 = h.add(rand(R).multiply(0.1)), p1 = h.add(rand(R).multiply(0.4)); bolts.add(bolt(p0, p1, p0.distanceTo(p1) * 0.15, 2, R)); } }
        }
        @Override void glow(VertexConsumer vc, Matrix4f m, ClientWorld w) {
            Entity e = w.getEntityById(id);
            if (e == null) return;
            Vec3d off = e.getLerpedPos(delta).subtract(e.getPos());
            for (int i = 0; i < bolts.size(); i++) { List<Vec3d> b = new ArrayList<>(); for (Vec3d p : bolts.get(i)) b.add(p.add(off)); drawBolt(vc, m, b, 0.012f, i % 2 == 0 ? PALE : BLUE, 0.9f); }
            float pulse = 0.75f + 0.25f * (float) Math.sin((age + delta) * 1.3f);
            for (int s = -1; s <= 1; s += 2) { blob(vc, m, hand(e, s, delta), 0.2f * pulse, BLUE, 0.45f); blob(vc, m, hand(e, s, delta), 0.07f, CORE, 0.8f); }
        }
    }

    /** 두 점 사이 굵은 번개 */
    static final class Arc extends Fx {
        final Vec3d a, b; List<Vec3d> pts;
        Arc(Vec3d a, Vec3d b) { super(6); this.a = a; this.b = b; pts = bolt(a, b, a.distanceTo(b) * 0.12, 4, R); }
        @Override void tick(ClientWorld w) { pts = bolt(a, b, a.distanceTo(b) * 0.12, 4, R); }
        @Override void glow(VertexConsumer vc, Matrix4f m, ClientWorld w) {
            float k = 1f - life();
            drawBolt(vc, m, pts, 0.03f, PALE, k);
            blob(vc, m, b, 0.35f * k, CORE, 0.7f * k);
        }
    }

    /** 땅을 따라 퍼지는 파문 고리 + 고리 앞 번개 */
    static final class Wave extends Fx {
        final Vec3d c; final float rad; List<List<Vec3d>> bolts = new ArrayList<>();
        Wave(Vec3d c, float rad) { super(12); this.c = c; this.rad = rad; }
        @Override void tick(ClientWorld w) {
            bolts.clear();
            double r = rad * life();
            for (int k = 0; k < 6; k++) {
                double a = R.nextDouble() * Math.PI * 2;
                Vec3d p = c.add(Math.cos(a) * r, 0.1, Math.sin(a) * r);
                Vec3d p1 = p.add(Math.cos(a + 1.2) * 0.5, 0.3 + R.nextDouble() * 0.5, Math.sin(a + 1.2) * 0.5); bolts.add(bolt(p, p1, p.distanceTo(p1) * 0.15, 2, R));
            }
        }
        @Override void glow(VertexConsumer vc, Matrix4f m, ClientWorld w) {
            float k = 1f - life();
            ring(vc, m, c.add(0, 0.08, 0), rad * life(), 0.3, PALE, 0.7f * k);
            ring(vc, m, c.add(0, 0.1, 0), rad * life() * 0.8, 0.12, BLUE, 0.5f * k);
            for (int i = 0; i < bolts.size(); i++) drawBolt(vc, m, bolts.get(i), 0.016f, i % 2 == 0 ? PALE : BLUE, k);
            if (age < 4) { blob(vc, m, c.add(0, 0.6, 0), 2.0f * (1 - age / 4f), ORANGE, 0.6f); blob(vc, m, c.add(0, 0.6, 0), 1.2f * (1 - age / 4f), GOLD, 0.8f); }
        }
    }

    // ───────── 생명 창조 ─────────
    // 생물 모델 (세상 기준, y 위): 몸통 · 머리 · 턱 · 붉은 눈 둘 · 등 가시 셋 · 다리 넷 · 꼬리 두 마디 — 픽셀 단위, 앞 = -z
    private static final int TW = 64, TH = 48;
    private static final int C_BODY = 0, C_DARK = 1, C_EYE = 2, C_BLACK = 3, C_SPIKE = 4, C_RED = 5, C_RED_DARK = 6;
    private static final int[][] UV = {{0, 32}, {16, 32}, {32, 32}, {48, 32}, {48, 16}, {16, 16}, {32, 16}};
    static ModelPart part(int region, float x, float y, float z, float sx, float sy, float sz) {
        int[] uv = UV[region];
        return new ModelPart(List.of(new ModelPart.Cuboid(uv[0], uv[1], x, y, z, sx, sy, sz, 0, 0, 0, false, TW, TH, EnumSet.allOf(Direction.class))), Map.of());
    }
    private static final ModelPart C_TORSO = part(C_BODY, -1.6f, 1.6f, -3f, 3.2f, 2.2f, 6f);
    private static final ModelPart C_HEAD = part(C_DARK, -1.4f, -0.9f, -2.6f, 2.8f, 1.9f, 2.8f);
    private static final ModelPart C_JAW = part(C_BODY, -1.1f, -0.3f, -2.4f, 2.2f, 0.7f, 2.4f);
    private static final ModelPart C_EYE_L = part(C_EYE, 0.5f, 0.3f, -2.75f, 0.6f, 0.5f, 0.3f), C_EYE_R = part(C_EYE, -1.1f, 0.3f, -2.75f, 0.6f, 0.5f, 0.3f);
    private static final ModelPart C_SPINE = part(C_SPIKE, -0.3f, 0, -0.3f, 0.6f, 1.3f, 0.6f);
    private static final ModelPart C_LEG = part(C_DARK, -0.45f, -1.8f, -0.45f, 0.9f, 1.9f, 0.9f);
    private static final ModelPart C_TAIL = part(C_DARK, -0.6f, -0.5f, 0, 1.2f, 1.0f, 2.6f);
    private static final ModelPart UNIT_BLACK = part(C_BLACK, -0.5f, 0, -0.5f, 1, 1, 1), UNIT_RED = part(C_RED, -0.5f, -0.5f, -0.5f, 1, 1, 1), UNIT_RED_DARK = part(C_RED_DARK, -0.5f, -0.5f, -0.5f, 1, 1, 1);

    static void render(ModelPart p, MatrixStack ms, VertexConsumer vc, int light) { p.setPivot(0, 0, 0); p.setAngles(0, 0, 0); p.render(ms, vc, light, OverlayTexture.DEFAULT_UV); }
    static int light(ClientWorld w, Vec3d p) { return WorldRenderer.getLightmapCoordinates(w, BlockPos.ofFloored(p)); }

    static double groundY(ClientWorld w, Vec3d q) {
        BlockPos b = BlockPos.ofFloored(q.x, q.y + 1.5, q.z);
        for (int i = 0; i < 6; i++) {
            if (!w.getBlockState(b.down()).getCollisionShape(w, b.down()).isEmpty()) return b.getY();
            b = b.down();
        }
        return q.y;
    }

    /** 생물 하나 (서버의 critter 와 같은 속도로 기어감 — 0.8칸/틱, 좌우로 꿈틀) */
    static final class Crit extends Fx {
        Vec3d pos, prev, dir; final int target, delay; int latchedAt = -1; Vec3d latchOff; float yaw;
        Crit(Vec3d p, int target, Vec3d dir, int delay) { super(delay + 22 + 24); pos = prev = p; this.target = target; this.dir = dir.lengthSquared() > 1e-4 ? dir.normalize() : new Vec3d(0, 0, 1); this.delay = delay; yaw = (float) Math.atan2(this.dir.x, this.dir.z); }
        @Override void tick(ClientWorld w) {
            prev = pos;
            int t = age - delay;
            if (t < 0) return;
            Entity e = target >= 0 ? w.getEntityById(target) : null;
            if (latchedAt >= 0) { if (age - latchedAt > 22) ttl = Math.min(ttl, age + 3); return; }
            if (t > 22) { ttl = Math.min(ttl, age + 4); return; }
            Vec3d goal = e != null && e.isAlive() ? e.getPos() : pos.add(dir);
            Vec3d d = goal.subtract(pos).multiply(1, 0, 1);
            double len = d.length();
            Vec3d step = len > 1e-3 ? d.multiply(Math.min(0.8, len) / len) : Vec3d.ZERO;
            Vec3d side = new Vec3d(-step.z, 0, step.x).multiply(Math.sin(t * 1.3) * 0.25);
            Vec3d nx = pos.add(step).add(side);
            pos = new Vec3d(nx.x, groundY(w, nx), nx.z);
            if (step.lengthSquared() > 1e-4) yaw = (float) Math.atan2(step.x, step.z);
            if (e != null && e.isAlive() && e.squaredDistanceTo(pos) < 1.1) {
                latchedAt = age;
                double a = R.nextDouble() * Math.PI * 2;
                latchOff = new Vec3d(Math.cos(a) * e.getWidth() * 0.55, 0.3 + R.nextDouble() * e.getHeight() * 0.6, Math.sin(a) * e.getWidth() * 0.55);
            }
        }
        @Override void solid(MatrixStack ms, VertexConsumer vc, ClientWorld w) {
            int t = age - delay;
            if (t < 0) return;
            Entity e = target >= 0 ? w.getEntityById(target) : null;
            Vec3d p; float y = yaw, pitch = 0; float bite = 0;
            if (latchedAt >= 0 && e != null) {   // 몸에 붙어서 물어뜯음
                p = e.getLerpedPos(delta).add(latchOff);
                Vec3d in = new Vec3d(-latchOff.x, 0, -latchOff.z);
                y = (float) Math.atan2(in.x, in.z) + (float) Math.PI;
                pitch = -50;
                bite = (float) Math.abs(Math.sin((age + delta) * 1.6f));
            } else p = prev.lerp(pos, delta);
            float sc = Math.min(1f, (t + delta) / 3f) * (age > ttl - 4 ? Math.max(0, (ttl - age - delta) / 4f) : 1f) * 1.5f;
            if (sc <= 0.01f) return;
            int li = light(w, p.add(0, 0.3, 0));
            float run = (age + delta) * 1.8f;
            ms.push();
            ms.translate(p.x, p.y, p.z);
            ms.multiply(RotationAxis.POSITIVE_Y.rotation(y + (float) Math.PI));
            ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(pitch));
            ms.scale(sc, sc, sc);
            ms.translate(0, (latchedAt < 0 ? Math.abs(Math.sin(run)) * 0.6f : 0) / 16f, 0);
            render(C_TORSO, ms, vc, li);
            for (int k = 0; k < 3; k++) { ms.push(); ms.translate(0, 3.8f / 16f, (-1.6f + k * 1.6f) / 16f); ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-25)); render(C_SPINE, ms, vc, li); ms.pop(); }
            ms.push(); ms.translate(0, 2.8f / 16f, -3f / 16f);   // 머리 + 턱 (물 때 벌어짐)
            render(C_HEAD, ms, vc, li); render(C_EYE_L, ms, vc, 0xF000F0); render(C_EYE_R, ms, vc, 0xF000F0);
            ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-10 - bite * 35)); render(C_JAW, ms, vc, li);
            ms.pop();
            for (int k = 0; k < 4; k++) {   // 다리 (번갈아 휘저음)
                float sx = k % 2 == 0 ? 1.4f : -1.4f, sz = k < 2 ? -2f : 2f;
                ms.push(); ms.translate(sx / 16f, 2.0f / 16f, sz / 16f);
                ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees((float) Math.sin(run + (k == 0 || k == 3 ? 0 : Math.PI)) * 40));
                ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(sx > 0 ? -25 : 25));
                render(C_LEG, ms, vc, li); ms.pop();
            }
            ms.push(); ms.translate(0, 2.6f / 16f, 3f / 16f); ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees((float) Math.sin(run * 0.7f) * 25));
            render(C_TAIL, ms, vc, li); ms.translate(0, 0, 2.4f / 16f); ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees((float) Math.sin(run * 0.7f - 0.8f) * 30)); ms.scale(0.7f, 0.7f, 0.9f); render(C_TAIL, ms, vc, li); ms.pop();
            ms.pop();
        }
    }

    /** 발밑에서 검은 가시가 솟음 (3틱 솟고 · 8틱 버티고 · 6틱 가라앉음) */
    static final class Thorn extends Fx {
        final Vec3d c; final float[][] sp = new float[9][4];
        Thorn(Vec3d c) {
            super(17); this.c = c;
            for (float[] s : sp) { s[0] = (float) (R.nextDouble() * Math.PI * 2); s[1] = 0.15f + R.nextFloat() * 0.5f; s[2] = 1.1f + R.nextFloat() * 1.1f; s[3] = 10 + R.nextFloat() * 25; }
        }
        @Override void solid(MatrixStack ms, VertexConsumer vc, ClientWorld w) {
            float t = age + delta, h = t < 3 ? t / 3f : t < 11 ? 1f : Math.max(0, 1f - (t - 11) / 6f);
            if (h <= 0) return;
            int li = light(w, c.add(0, 0.5, 0));
            for (float[] s : sp) {
                ms.push();
                ms.translate(c.x + Math.cos(s[0]) * s[1], c.y, c.z + Math.sin(s[0]) * s[1]);
                ms.multiply(RotationAxis.POSITIVE_Y.rotation(-s[0]));
                ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(-s[3]));
                float len = s[2] * h;
                for (int k = 0; k < 5; k++) {   // 끝으로 갈수록 가늘게
                    float wdt = (2.6f - k * 0.5f) / 16f;
                    ms.push(); ms.translate(0, len * k / 5f, 0); ms.scale(wdt, len / 5f + 0.02f, wdt); render(UNIT_BLACK, ms, vc, li); ms.pop();
                }
                ms.pop();
            }
        }
    }

    /** 살점이 튐 (땅에 떨어지면 튕기고 사라짐) */
    static final class Flesh extends Fx {
        final List<double[]> bits = new ArrayList<>();
        Flesh(Vec3d c, float size) {
            super(18);
            int n = (int) (8 + 6 * size);
            for (int i = 0; i < n; i++) {
                Vec3d v = rand(R).multiply(0.15 + R.nextDouble() * 0.25 * size).add(0, 0.18, 0);
                bits.add(new double[]{c.x, c.y, c.z, v.x, v.y, v.z, c.x, c.y, c.z, R.nextDouble() * 360, (0.12 + R.nextDouble() * 0.16) * Math.max(0.7, size), R.nextInt(2), -1});
            }
        }
        @Override void tick(ClientWorld w) {
            for (double[] b : bits) {
                b[6] = b[0]; b[7] = b[1]; b[8] = b[2];
                b[0] += b[3]; b[1] += b[4]; b[2] += b[5]; b[4] -= 0.05; b[9] += 25;
                if (b[12] < 0) b[12] = groundY(w, new Vec3d(b[0], b[1], b[2]));
                if (b[1] < b[12] + 0.05) { b[1] = b[12] + 0.05; b[4] = Math.abs(b[4]) * 0.3; b[3] *= 0.5; b[5] *= 0.5; }
            }
        }
        @Override void solid(MatrixStack ms, VertexConsumer vc, ClientWorld w) {
            float k = age > ttl - 5 ? Math.max(0, (ttl - age - delta) / 5f) : 1f;
            for (double[] b : bits) {
                double x = b[6] + (b[0] - b[6]) * delta, y = b[7] + (b[1] - b[7]) * delta, z = b[8] + (b[2] - b[8]) * delta;
                float s = (float) b[10] * k;
                ms.push(); ms.translate(x, y, z); ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees((float) b[9])); ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees((float) b[9] * 0.7f));
                ms.scale(s, s * 0.8f, s); render(b[11] == 0 ? UNIT_RED : UNIT_RED_DARK, ms, vc, light(w, new Vec3d(x, y, z))); ms.pop();
            }
        }
    }

    /** 생명을 빨아들임: 상대 몸에서 시전자 가슴으로 붉은 줄기 셋이 물결치며 흐름 */
    static final class Drain extends Fx {
        final int from, to;
        Drain(int from, int to, int ticks) { super(Math.min(60, ticks)); this.from = from; this.to = to; }
        @Override void glow(VertexConsumer vc, Matrix4f m, ClientWorld w) {
            Entity a = w.getEntityById(from), b = w.getEntityById(to);
            if (a == null || b == null) return;
            Vec3d s = a.getLerpedPos(delta).add(0, a.getHeight() * 0.55, 0), e = b.getLerpedPos(delta).add(0, 1.1, 0);
            Vec3d d = e.subtract(s), side = d.crossProduct(new Vec3d(0, 1, 0));
            side = side.lengthSquared() < 1e-6 ? new Vec3d(1, 0, 0) : side.normalize();
            float t = age + delta, k = Math.min(1f, (ttl - t) / 6f);
            for (int strand = 0; strand < 3; strand++) {
                Vec3d prev = null;
                for (int i = 0; i <= 14; i++) {
                    double u = i / 14.0, ph = t * 0.6 + strand * 2.1 - u * 8;
                    Vec3d q = s.add(d.multiply(u)).add(side.multiply(Math.sin(ph) * 0.18 * Math.sin(Math.PI * u))).add(0, Math.cos(ph * 0.8) * 0.12 * Math.sin(Math.PI * u), 0);
                    if (prev != null) { ribbon(vc, m, prev, q, 0.16f, RED[0], RED[1], RED[2], 0.35f * k); ribbon(vc, m, prev, q, 0.05f, 1f, 0.55f, 0.45f, 0.8f * k); }
                    prev = q;
                }
            }
            blob(vc, m, s, 0.35f, RED, 0.6f * k);
        }
    }
}
