package bbc.client;

import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static bbc.client.UltimateFx.*;

/**
 * 모든 직업이 같이 쓰는 이펙트 모델 (서버 combat/WorldFx 가 "종류:값,…" 을 보내면 그림 — 그리는 틀은 UltimateFx 와 같음).
 * 예전엔 서버가 블록 디스플레이 판을 수십 장 띄워 만들던 것들을 매끈한 띠·고리·가시·빛으로:
 *   slash 칼자국 (붓으로 그은 초승달 — 바깥 날이 진하고 안쪽으로 옅어짐, 흰색은 먹선 테두리) · wave 바닥 충격파 · ring 고리 · burst 파열 가시 ·
 *   flash 번쩍 · ink 먹 초승달 · swirl 흰 소용돌이 · lines 날아가는 몸 뒤 속도선 · kick 드롭킥 원뿔 ·
 *   path 혈관 같은 굵은 선 · helix 바람 나선 · beam 빛줄기 · glint 반짝 별 · bolt 번개 · sparks 사방 번개 · circle 연성진 · puff 김
 */
public final class FxShapes {
    private FxShapes() {}

    private static final Random R = new Random();

    public static void handle(String spec) {
        try {
            int c = spec.indexOf(':');
            String k = spec.substring(0, c);
            String[] a = spec.substring(c + 1).split(",");
            Fx f = switch (k) {
                case "slash" -> new Smear(v(a, 0), new Vec3d(d(a, 3), 0, d(a, 4)), d(a, 5), d(a, 6), i(a, 7) == 1, col(a, 8));
                case "wave" -> new GroundWave(v(a, 0), d(a, 3), d(a, 4), i(a, 5), col(a, 6));
                case "ring" -> new Ring3(v(a, 0), v(a, 3), d(a, 6), d(a, 7), d(a, 8), i(a, 9), col(a, 10));
                case "burst" -> new Spikes(v(a, 0), v(a, 3), d(a, 6), col(a, 7), col(a, 8), i(a, 9));
                case "flash" -> new Flash(v(a, 0), d(a, 3));
                case "ink" -> new Crescents(v(a, 0), i(a, 3), d(a, 4), i(a, 5), false);
                case "swirl" -> new Crescents(v(a, 0), i(a, 3), d(a, 4), i(a, 5), true);
                case "lines" -> new SpeedLines(i(a, 0), i(a, 1));
                case "kick" -> new Kick(v(a, 0), v(a, 3), i(a, 6));
                case "path" -> { List<Vec3d> pts = new ArrayList<>(); for (int q = 4; q + 2 < a.length; q += 3) pts.add(v(a, q)); yield new Path(pts, d(a, 0), i(a, 1), col(a, 2), i(a, 3) == 1); }
                case "helix" -> new Helix(v(a, 0), v(a, 3), d(a, 6), d(a, 7), d(a, 8), d(a, 9), i(a, 10), col(a, 11), d(a, 12));
                case "beam" -> new Beam(v(a, 0), v(a, 3), d(a, 6), i(a, 7), col(a, 8));
                case "glint" -> new Glint(v(a, 0), d(a, 3), col(a, 4), i(a, 5));
                case "bolt" -> new Bolt(v(a, 0), v(a, 3), i(a, 6), col(a, 7));
                case "sparks" -> new Sparks(v(a, 0), d(a, 3), i(a, 4), i(a, 5), col(a, 6));
                case "circle" -> new Circle(v(a, 0), d(a, 3), i(a, 4), col(a, 5), i(a, 6));
                case "puff" -> new Puff(v(a, 0), d(a, 3), i(a, 4), col(a, 5), i(a, 6));
                default -> null;
            };
            if (f != null) add(f);
        } catch (RuntimeException ignored) { }
    }
    private static double d(String[] a, int k) { return Double.parseDouble(a[k]); }
    private static int i(String[] a, int k) { return Integer.parseInt(a[k]); }
    private static Vec3d v(String[] a, int k) { return new Vec3d(d(a, k), d(a, k + 1), d(a, k + 2)); }
    private static float[] col(String[] a, int k) { int c = Integer.parseInt(a[k]); return new float[]{((c >> 16) & 255) / 255f, ((c >> 8) & 255) / 255f, (c & 255) / 255f}; }
    static float lum(float[] c) { return 0.3f * c[0] + 0.59f * c[1] + 0.11f * c[2]; }
    static final float[] INK = {0.06f, 0.05f, 0.07f}, WHITE = {1f, 1f, 1f};

    /** 카메라를 보는 오른쪽·위 벡터 */
    static Vec3d[] billboard(Vec3d c) {
        Vec3d f = c.subtract(cam).normalize();
        Vec3d rt = f.crossProduct(new Vec3d(0, 1, 0));
        if (rt.lengthSquared() < 1e-6) rt = new Vec3d(1, 0, 0);
        rt = rt.normalize();
        return new Vec3d[]{rt, rt.crossProduct(f).normalize()};
    }
    /** 한 면 위의 고리 띠 (u·v 가 펼친 면): 안쪽 투명 → 가운데 진하게 → 바깥 투명 */
    static void bandRing(VertexConsumer vc, Matrix4f m, Vec3d c, Vec3d u, Vec3d v, double rad, double width, float[] col, float al, int n) {
        for (int i = 0; i < n; i++) {
            double a0 = Math.PI * 2 * i / n, a1 = Math.PI * 2 * (i + 1) / n;
            Vec3d d0 = u.multiply(Math.cos(a0)).add(v.multiply(Math.sin(a0))), d1 = u.multiply(Math.cos(a1)).add(v.multiply(Math.sin(a1)));
            double ri = Math.max(0, rad - width), ro = rad + width * 0.5;
            quad(vc, m, c.add(d0.multiply(ri)), c.add(d1.multiply(ri)), c.add(d1.multiply(rad)), c.add(d0.multiply(rad)), col[0], col[1], col[2], 0, 0, al, al);
            quad(vc, m, c.add(d0.multiply(rad)), c.add(d1.multiply(rad)), c.add(d1.multiply(ro)), c.add(d0.multiply(ro)), col[0], col[1], col[2], al, al, 0, 0);
        }
    }
    /** 땅에 눕힌 굵은 선 */
    static void flatLine(VertexConsumer vc, Matrix4f m, Vec3d a, Vec3d b, double w, float[] col, float al) {
        Vec3d d = b.subtract(a);
        Vec3d s = new Vec3d(-d.z, 0, d.x);
        if (s.lengthSquared() < 1e-9) return;
        s = s.normalize().multiply(w / 2);
        quad(vc, m, a.subtract(s), a.add(s), b.add(s), b.subtract(s), col[0], col[1], col[2], al, al, al, al);
    }
    /** 카메라를 보는 초승달: 가운데가 두껍고 양끝이 뾰족 (rot = 화면에서 돌린 각) */
    static void crescent(VertexConsumer vc, Matrix4f m, Vec3d c, double size, double rot, double span, double thick, float[] col, float al) {
        Vec3d[] b = billboard(c);
        int n = 16;
        for (int i = 0; i < n; i++) {
            double u0 = i / (double) n, u1 = (i + 1) / (double) n;
            double a0 = rot + span * (u0 - 0.5), a1 = rot + span * (u1 - 0.5);
            double w0 = thick * Math.sin(Math.PI * u0), w1 = thick * Math.sin(Math.PI * u1);
            Vec3d d0 = b[0].multiply(Math.cos(a0)).add(b[1].multiply(Math.sin(a0))), d1 = b[0].multiply(Math.cos(a1)).add(b[1].multiply(Math.sin(a1)));
            quad(vc, m, c.add(d0.multiply(size - w0)), c.add(d1.multiply(size - w1)), c.add(d1.multiply(size)), c.add(d0.multiply(size)), col[0], col[1], col[2], al, al, al, al);
        }
    }
    /** 카메라를 보는 둥근 판 (가운데 진하고 가장자리 투명) */
    static void disc(VertexConsumer vc, Matrix4f m, Vec3d c, double rad, float[] col, float al, float edge) {
        Vec3d[] b = billboard(c);
        int n = 20;
        for (int i = 0; i < n; i++) {
            double a0 = Math.PI * 2 * i / n, a1 = Math.PI * 2 * (i + 1) / n;
            Vec3d e0 = c.add(b[0].multiply(Math.cos(a0) * rad)).add(b[1].multiply(Math.sin(a0) * rad));
            Vec3d e1 = c.add(b[0].multiply(Math.cos(a1) * rad)).add(b[1].multiply(Math.sin(a1) * rad));
            quad(vc, m, c, e0, e1, e1, col[0], col[1], col[2], al, al * edge, al * edge, al * edge);
        }
    }

    // ───────── 칼자국 ─────────
    /** 붓으로 그은 초승달: 0~2틱 동안 끝이 그어 나가고 (휘두르는 방향이 보임) 3~8틱 얇아지며 사라짐. 바깥 날이 진하고 안쪽으로 옅어짐 */
    static final class Smear extends Fx {
        final Vec3d o, f, side; final double radius; final boolean rev; final float[] col; final boolean light;
        Smear(Vec3d o, Vec3d f0, double radius, double tilt, boolean rev, float[] col) {
            super(9);
            this.o = o; this.radius = radius; this.rev = rev; this.col = col; light = lum(col) > 0.75f;
            f = f0.lengthSquared() < 1e-6 ? new Vec3d(0, 0, 1) : f0.normalize();
            Vec3d r = new Vec3d(-f.z, 0, f.x);
            double t = Math.toRadians(tilt);
            side = r.multiply(Math.cos(t)).add(0, Math.sin(t), 0);
        }
        Vec3d at(double a, double rad) { return o.add(f.multiply(Math.cos(a) * rad)).add(side.multiply(-Math.sin(a) * rad)); }
        /** 폭 방향: 호 바깥쪽(반지름)을 카메라 쪽으로 돌려서 — 휘두른 면을 옆에서 봐도(뒤 3인칭) 띠가 실처럼 가늘어지지 않음 */
        Vec3d wdir(double a) {
            Vec3d radial = f.multiply(Math.cos(a)).add(side.multiply(-Math.sin(a)));
            Vec3d tangent = f.multiply(-Math.sin(a)).add(side.multiply(-Math.cos(a)));
            Vec3d face = tangent.crossProduct(at(a, radius).subtract(cam));
            if (face.lengthSquared() < 1e-9) return radial;
            face = face.normalize();
            if (face.dotProduct(radial) < 0) face = face.multiply(-1);
            Vec3d w = radial.multiply(0.35).add(face);
            return w.normalize();
        }
        void draw(VertexConsumer vc, Matrix4f m, float[] c, float alpha, double widthMul, boolean rim) {
            float t = age + delta;
            double head = Math.min(1, t / 2.2), thin = t < 3 ? 1 : Math.max(0, 1 - (t - 3) / 6.0);
            if (thin <= 0) return;
            int n = 26;
            for (int i = 0; i < n; i++) {
                double u0 = i / (double) n, u1 = (i + 1) / (double) n;
                if (u1 > head) break;
                double s0 = rev ? 1 - u0 : u0, s1 = rev ? 1 - u1 : u1;
                double a0 = Math.PI * (s0 - 0.5) * 0.95, a1 = Math.PI * (s1 - 0.5) * 0.95;
                double w0 = (0.2 + 0.45 * Math.cos(a0)) * thin * widthMul, w1 = (0.2 + 0.45 * Math.cos(a1)) * thin * widthMul;
                float tail0 = (float) (0.55 + 0.45 * (u0 / Math.max(0.01, head))), tail1 = (float) (0.55 + 0.45 * (u1 / Math.max(0.01, head)));
                float al0 = alpha * tail0 * (float) thin, al1 = alpha * tail1 * (float) thin;
                Vec3d c0 = at(a0, radius), c1 = at(a1, radius), d0 = wdir(a0), d1 = wdir(a1);
                if (rim) {
                    Vec3d o0 = c0.add(d0.multiply(w0 * 0.5)), o1 = c1.add(d1.multiply(w1 * 0.5));
                    quad(vc, m, o0, o1, o1.add(d1.multiply(0.04)), o0.add(d0.multiply(0.04)), c[0], c[1], c[2], al0, al1, al1, al0);
                } else {
                    quad(vc, m, c0.subtract(d0.multiply(w0 * 0.5)), c1.subtract(d1.multiply(w1 * 0.5)), c1.add(d1.multiply(w1 * 0.5)), c0.add(d0.multiply(w0 * 0.5)), c[0], c[1], c[2], 0, 0, al1, al0);
                }
            }
        }
        @Override void tl(VertexConsumer vc, Matrix4f m, ClientWorld w) {
            draw(vc, m, col, 0.95f, 1, false);
            if (light) draw(vc, m, INK, 0.9f, 1, true);   // 흰 칼자국: 바깥에 가는 먹선
        }
        @Override void glow(VertexConsumer vc, Matrix4f m, ClientWorld w) { if (!light) draw(vc, m, col, 0.4f, 1.15, false); }
    }

    // ───────── 충격파·고리 ─────────
    static final class GroundWave extends Fx {
        final Vec3d c; final double r0, r1; final float[] col;
        GroundWave(Vec3d c, double r0, double r1, int life, float[] col) { super(Math.max(3, life + 1)); this.c = c.add(0, 0.04, 0); this.r0 = r0; this.r1 = r1; this.col = col; }
        @Override void tl(VertexConsumer vc, Matrix4f m, ClientWorld w) {
            float x = life(), e = 1 - (1 - x) * (1 - x), k = 1 - x;
            double r = r0 + (r1 - r0) * e;
            bandRing(vc, m, c, new Vec3d(1, 0, 0), new Vec3d(0, 0, 1), r, 0.3 * k + 0.05, col, 0.8f * k, 40);
            double h = 0.4 * k;   // 낮은 벽 (안쪽 위로 투명)
            int n = 40;
            for (int i = 0; i < n; i++) {
                double a0 = Math.PI * 2 * i / n, a1 = Math.PI * 2 * (i + 1) / n;
                Vec3d p0 = c.add(Math.cos(a0) * r, 0, Math.sin(a0) * r), p1 = c.add(Math.cos(a1) * r, 0, Math.sin(a1) * r);
                quad(vc, m, p0, p1, p1.add(0, h, 0), p0.add(0, h, 0), col[0], col[1], col[2], 0.6f * k, 0.6f * k, 0, 0);
            }
        }
    }
    static final class Ring3 extends Fx {
        final Vec3d c, u, v; final double r0, r1, width; final float[] col;
        Ring3(Vec3d c, Vec3d axis, double r0, double r1, double width, int life, float[] col) {
            super(Math.max(3, life + 1)); this.c = c; this.r0 = r0; this.r1 = r1; this.width = width; this.col = col;
            Vec3d ax = axis.lengthSquared() < 1e-6 ? new Vec3d(0, 1, 0) : axis.normalize();
            u = ax.crossProduct(Math.abs(ax.y) > 0.9 ? new Vec3d(1, 0, 0) : new Vec3d(0, 1, 0)).normalize();
            v = ax.crossProduct(u).normalize();
        }
        double rad() { float x = life(); return r0 + (r1 - r0) * (1 - (1 - x) * (1 - x)); }
        @Override void tl(VertexConsumer vc, Matrix4f m, ClientWorld w) { float k = 1 - life(); bandRing(vc, m, c, u, v, rad(), width * (0.3 + 0.7 * k) + 0.03, col, 0.85f * k, 48); }
        @Override void glow(VertexConsumer vc, Matrix4f m, ClientWorld w) { if (lum(col) < 0.75f) { float k = 1 - life(); bandRing(vc, m, c, u, v, rad(), width * 1.6 * k + 0.05, col, 0.35f * k, 48); } }
    }

    // ───────── 파열 가시 ─────────
    static final class Spikes extends Fx {
        final Vec3d c; final Vec3d[] dirs; final double[] lens; final float[][] cols;
        Spikes(Vec3d c, Vec3d dir, double size, float[] main, float[] accent, int seed) {
            super(5); this.c = c;
            Random r = new Random(seed);
            int n = 11;
            dirs = new Vec3d[n]; lens = new double[n]; cols = new float[n][];
            for (int i = 0; i < n; i++) {
                Vec3d d = new Vec3d(r.nextGaussian(), r.nextGaussian() * 0.7, r.nextGaussian()).normalize();
                if (dir.lengthSquared() > 1e-4) d = d.add(dir.normalize().multiply(0.8)).normalize();
                dirs[i] = d; lens[i] = size * (0.6 + r.nextDouble() * 0.9); cols[i] = i % 5 == 0 ? accent : main;
            }
        }
        @Override void tl(VertexConsumer vc, Matrix4f m, ClientWorld w) {
            float x = life(), k = 1 - x;
            for (int i = 0; i < dirs.length; i++) {
                Vec3d base = c.add(dirs[i].multiply(0.2 + lens[i] * 0.7 * x)), tip = base.add(dirs[i].multiply(lens[i] * (1 + 0.3 * x)));
                streak(vc, m, base, tip, (float) (lens[i] * 0.16 * k), cols[i], 0.95f * Math.min(1f, k * 1.6f));
            }
        }
    }

    // ───────── 번쩍 · 먹 초승달 · 소용돌이 ─────────
    static final class Flash extends Fx {
        final Vec3d c; final double size;
        Flash(Vec3d c, double size) { super(5); this.c = c; this.size = size; }
        double rad() { float t = age + delta; return t < 2 ? size * (0.5 + 0.75 * t) : size * 2.0 * Math.max(0, 1 - (t - 2) / 3); }
        @Override void tl(VertexConsumer vc, Matrix4f m, ClientWorld w) { disc(vc, m, c, rad() * 0.5, WHITE, 0.85f, 0.25f); }
        @Override void glow(VertexConsumer vc, Matrix4f m, ClientWorld w) {
            disc(vc, m, c, rad() * 0.7, WHITE, 0.5f, 0f);
            float k = 1 - life();
            cring(vc, m, c, size * (0.4 + 1.4 * life()), 0.05 * size, WHITE, 0.7f * k, 0);
        }
    }
    static final class Crescents extends Fx {
        final Vec3d[] at; final double[] rot; final double size; final boolean swish;
        Crescents(Vec3d c, int n, double size, int seed, boolean swish) {
            super(n + 9); this.size = size; this.swish = swish;
            Random r = new Random(seed);
            at = new Vec3d[n]; rot = new double[n];
            for (int i = 0; i < n; i++) { at[i] = c.add((r.nextDouble() - 0.5) * (swish ? 0.8 : 0.5), (r.nextDouble() - (swish ? 0.3 : 0.5)) * (swish ? 0.8 : 0.5), (r.nextDouble() - 0.5) * (swish ? 0.8 : 0.5)); rot[i] = r.nextDouble() * Math.PI * 2; }
        }
        @Override void tl(VertexConsumer vc, Matrix4f m, ClientWorld w) {
            for (int i = 0; i < at.length; i++) {
                float t = age + delta - (swish ? 0 : i);
                if (t < 0) continue;
                double s; float al;
                if (swish) { s = size * (0.8 + 0.8 * Math.min(1, t / 3)); al = t < 4 ? 0.9f : Math.max(0, 0.9f * (1 - (t - 4) / 2f)); }
                else { s = t < 2 ? size * (0.5 + 0.95 * t) : t < 4 ? size * 2.4 : size * 2.4 * Math.max(0, 1 - (t - 4) / 3); al = 0.92f; }
                if (s <= 0.01 || al <= 0) continue;
                double r = rot[i] + (swish ? t * 0.35 : 0);
                crescent(vc, m, at[i], s * 0.35, r, swish ? Math.PI * 1.1 : Math.PI * 0.8, s * (swish ? 0.05 : 0.09), swish ? WHITE : INK, al);
            }
        }
    }

    // ───────── 속도선 · 드롭킥 ─────────
    static final class SpeedLines extends Fx {
        final int id; final List<double[]> lines = new ArrayList<>(); Vec3d last;
        SpeedLines(int id, int ticks) { super(ticks + 4); this.id = id; }
        @Override void tick(ClientWorld w) {
            lines.removeIf(l -> age - l[7] > 3);
            Entity e = w.getEntityById(id);
            if (e == null || age > ttl - 4) return;
            Vec3d now = e.getBoundingBox().getCenter();
            if (last != null) {
                Vec3d v = now.subtract(last);
                if (v.lengthSquared() > 0.04) {
                    Vec3d d = v.normalize(), side = d.crossProduct(new Vec3d(0, 1, 0));
                    if (side.lengthSquared() < 1e-4) side = new Vec3d(1, 0, 0);
                    side = side.normalize();
                    Vec3d up = d.crossProduct(side).normalize();
                    for (int i = 0; i < 3; i++) {
                        Vec3d off = side.multiply((R.nextDouble() - 0.5) * 1.0).add(up.multiply((R.nextDouble() - 0.5) * 1.4));
                        double len = 1.2 + R.nextDouble() * 1.6;
                        Vec3d a = now.add(off).subtract(d.multiply(0.4)), b = a.subtract(d.multiply(len));
                        lines.add(new double[]{a.x, a.y, a.z, b.x, b.y, b.z, (age + i) % 3 == 0 ? 1 : 0, age});
                    }
                }
            }
            last = now;
        }
        @Override void tl(VertexConsumer vc, Matrix4f m, ClientWorld w) {
            for (double[] l : lines) {
                float k = 1 - Math.min(1f, (age + delta - (float) l[7]) / 3f);
                streak(vc, m, new Vec3d(l[0], l[1], l[2]), new Vec3d(l[3], l[4], l[5]), 0.05f, l[6] > 0 ? INK : WHITE, 0.9f * k);
            }
        }
    }
    static final class Kick extends Fx {
        final Vec3d at, d; final Vec3d[] offs; final double[] lens; Ring3 ring;
        Kick(Vec3d at, Vec3d dir, int seed) {
            super(9); this.at = at; d = dir.lengthSquared() < 1e-6 ? new Vec3d(0, 0, 1) : dir.normalize();
            Random r = new Random(seed);
            Vec3d side = d.crossProduct(new Vec3d(0, 1, 0)); side = side.lengthSquared() < 1e-6 ? new Vec3d(1, 0, 0) : side.normalize();
            Vec3d up = d.crossProduct(side).normalize();
            offs = new Vec3d[9]; lens = new double[9];
            for (int i = 0; i < 9; i++) { double sp = 0.15 + 0.5 * (i / 9.0); offs[i] = side.multiply((r.nextDouble() - 0.5) * sp * 2).add(up.multiply((r.nextDouble() - 0.5) * sp * 1.6)); lens[i] = 2.5 + r.nextDouble() * 3.0; }
        }
        @Override void tick(ClientWorld w) { if (age == 2) add(new Ring3(at.add(d.multiply(0.6)), d, 0.4, 2.4, 0.25, 6, WHITE)); }
        @Override void tl(VertexConsumer vc, Matrix4f m, ClientWorld w) {
            float k = 1 - life();
            for (int i = 0; i < offs.length; i++) { Vec3d a = at.add(offs[i]); streak(vc, m, a, a.subtract(d.multiply(lens[i] * (0.7 + 0.3 * k))), 0.16f * k + 0.02f, WHITE, 0.9f * k); }
        }
    }

    // ───────── 선 · 나선 · 빛줄기 · 반짝 ─────────
    static final class Path extends Fx {
        final List<Vec3d> pts; final double w; final float[] col; final boolean glow;
        Path(List<Vec3d> pts, double w, int life, float[] col, boolean glow) { super(Math.max(2, life)); this.pts = pts; this.w = w; this.col = col; this.glow = glow; }
        float fade() { float left = ttl - age - delta; return Math.min(1f, Math.max(0, left / 3f)) * Math.min(1f, (age + delta + 0.5f) / 1.5f); }
        @Override void tl(VertexConsumer vc, Matrix4f m, ClientWorld wd) {
            float k = fade();
            for (int i = 0; i + 1 < pts.size(); i++) ribbon(vc, m, pts.get(i), pts.get(i + 1), (float) w, col[0], col[1], col[2], 0.95f * k);
        }
        @Override void glow(VertexConsumer vc, Matrix4f m, ClientWorld wd) {
            if (!glow) return;
            float k = fade();
            for (int i = 0; i + 1 < pts.size(); i++) ribbon(vc, m, pts.get(i), pts.get(i + 1), (float) w * 2.6f, col[0], col[1], col[2], 0.35f * k);
        }
    }
    static final class Helix extends Fx {
        final Vec3d from, d, u, v; final double len, r, turns, phase, width; final float[] col;
        Helix(Vec3d from, Vec3d dir, double len, double r, double turns, double phase, int life, float[] col, double width) {
            super(Math.max(3, life)); this.from = from; this.len = len; this.r = r; this.turns = turns; this.phase = phase; this.col = col; this.width = width;
            d = dir.lengthSquared() < 1e-6 ? new Vec3d(0, 1, 0) : dir.normalize();
            Vec3d a = Math.abs(d.y) > 0.9 ? new Vec3d(1, 0, 0) : new Vec3d(0, 1, 0);
            u = d.crossProduct(a).normalize(); v = d.crossProduct(u).normalize();
        }
        Vec3d at(double f, double ph) { double ang = ph + f * turns * Math.PI * 2; return from.add(d.multiply(f * len)).add(u.multiply(Math.cos(ang) * r)).add(v.multiply(Math.sin(ang) * r)); }
        void draw(VertexConsumer vc, Matrix4f m, float wmul, float al) {
            float t = age + delta, k = 1 - life();
            double ph = phase + t * 0.6;
            int n = (int) Math.max(12, turns * 14);
            Vec3d prev = at(0, ph);
            for (int i = 1; i <= n; i++) {
                double f = i / (double) n;
                Vec3d q = at(f, ph);
                float taper = (float) Math.sin(Math.PI * f);
                ribbon(vc, m, prev, q, (float) width * wmul * (0.4f + 0.6f * taper), col[0], col[1], col[2], al * k * (0.3f + 0.7f * taper));
                prev = q;
            }
        }
        @Override void tl(VertexConsumer vc, Matrix4f m, ClientWorld w) { draw(vc, m, 1f, 0.75f); }
        @Override void glow(VertexConsumer vc, Matrix4f m, ClientWorld w) { draw(vc, m, 2.2f, 0.25f); }
    }
    static final class Beam extends Fx {
        final Vec3d a, b; final double w; final float[] col;
        Beam(Vec3d a, Vec3d b, double w, int life, float[] col) { super(Math.max(2, life)); this.a = a; this.b = b; this.w = w; this.col = col; }
        @Override void glow(VertexConsumer vc, Matrix4f m, ClientWorld wd) {
            float t = age + delta, k = Math.min(1f, (ttl - t) / Math.max(1f, ttl * 0.4f));
            float fl = 0.85f + 0.15f * R.nextFloat();
            ribbon(vc, m, a, b, (float) (w * 3.2) * fl, col[0], col[1], col[2], 0.3f * k);
            ribbon(vc, m, a, b, (float) (w * 1.3) * fl, col[0], col[1], col[2], 0.7f * k);
            ribbon(vc, m, a, b, (float) (w * 0.45), 0.5f + col[0] * 0.5f, 0.5f + col[1] * 0.5f, 0.5f + col[2] * 0.5f, k);
            blob(vc, m, b, (float) w * 3f, col, 0.6f * k);
        }
    }
    static final class Glint extends Fx {
        final Vec3d c; final double size; final float[] col; final double rot;
        Glint(Vec3d c, double size, float[] col, int seed) { super(7); this.c = c; this.size = size; this.col = col; rot = new Random(seed).nextDouble() * 0.6 + Math.PI / 8; }
        @Override void glow(VertexConsumer vc, Matrix4f m, ClientWorld w) {
            float t = age + delta, s = (float) (size * Math.min(1, t / 1.2) * (1 - life() * 0.6)), k = 1 - life();
            Vec3d[] b = billboard(c);
            for (int i = 0; i < 4; i++) {
                double a = rot + Math.PI / 2 * i, L = (i % 2 == 0 ? 0.9 : 0.5) * s;
                Vec3d dir = b[0].multiply(Math.cos(a)).add(b[1].multiply(Math.sin(a)));
                streak(vc, m, c, c.add(dir.multiply(L)), (float) (0.09 * s), col, 0.95f * k);
                streak(vc, m, c, c.add(dir.multiply(L * 0.75)), (float) (0.035 * s), WHITE, k);
            }
            blob(vc, m, c, s * 0.35f, col, 0.7f * k);
            blob(vc, m, c, s * 0.14f, WHITE, k);
        }
    }

    // ───────── 번개 ─────────
    static final class Bolt extends Fx {
        final Vec3d a, b; final float[] col; List<Vec3d> pts;
        Bolt(Vec3d a, Vec3d b, int life, float[] col) { super(Math.max(2, life)); this.a = a; this.b = b; this.col = col; pts = bolt(a, b, a.distanceTo(b) * 0.13, 4, R); }
        @Override void tick(ClientWorld w) { if (age % 2 == 0) pts = bolt(a, b, a.distanceTo(b) * 0.13, 4, R); }
        @Override void glow(VertexConsumer vc, Matrix4f m, ClientWorld w) { drawBolt(vc, m, pts, 0.03f, col, 1f - life() * 0.6f); }
    }
    static final class Sparks extends Fx {
        final Vec3d c; final double r; final int n; final float[] col; final List<List<Vec3d>> bolts = new ArrayList<>();
        Sparks(Vec3d c, double r, int n, int life, float[] col) { super(Math.max(2, life)); this.c = c; this.r = r; this.n = n; this.col = col; regen(); }
        void regen() {
            bolts.clear();
            for (int k = 0; k < n; k++) {
                Vec3d p0 = c.add(rand(R).multiply(r * R.nextDouble() * 0.6)), p1 = p0.add(rand(R).multiply(r * (0.3 + R.nextDouble() * 0.5)));
                bolts.add(bolt(p0, p1, p0.distanceTo(p1) * 0.16, 3, R));
            }
        }
        @Override void tick(ClientWorld w) { if (age % 2 == 0) regen(); }
        @Override void glow(VertexConsumer vc, Matrix4f m, ClientWorld w) { float k = 1 - life() * 0.7f; for (List<Vec3d> b : bolts) drawBolt(vc, m, b, 0.018f, col, k); }
    }

    // ───────── 연성진 ─────────
    /** 바닥 원 셋 + (기본: 삼각 / 현자의 돌·의식: 육각별) + 두 원 사이 글자 눈금 + 가운데 작은 원. 처음 30% 동안 그어지고 안쪽 무늬가 천천히 돎, 끝 25% 동안 사라짐 */
    static final class Circle extends Fx {
        final Vec3d c; final double R0; final float[] col; final int style;
        Circle(Vec3d c, double radius, int life, float[] col, int style) { super(Math.max(6, life)); this.c = c.add(0, 0.03, 0); R0 = radius; this.col = col; this.style = style; }
        void ringLine(VertexConsumer vc, Matrix4f m, double r, double prog, double w, float al) {
            int n = 56, upto = (int) (n * prog);
            for (int i = 0; i < upto; i++) {
                double a0 = Math.PI * 2 * i / n, a1 = Math.PI * 2 * (i + 1) / n;
                flatLine(vc, m, c.add(Math.cos(a0) * r, 0, Math.sin(a0) * r), c.add(Math.cos(a1) * r, 0, Math.sin(a1) * r), w, col, al);
            }
        }
        void poly(VertexConsumer vc, Matrix4f m, double r, int sides, double rot, double prog, double w, float al) {
            for (int i = 0; i < sides; i++) {
                double p = Math.min(1, Math.max(0, prog * sides - i));
                if (p <= 0) break;
                double a0 = rot + Math.PI * 2 * i / sides, a1 = rot + Math.PI * 2 * (i + 1) / sides;
                Vec3d p0 = c.add(Math.cos(a0) * r, 0, Math.sin(a0) * r), p1 = c.add(Math.cos(a1) * r, 0, Math.sin(a1) * r);
                flatLine(vc, m, p0, p0.lerp(p1, p), w, col, al);
            }
        }
        void draw(VertexConsumer vc, Matrix4f m, double wmul, float amul) {
            float t = age + delta, x = t / ttl;
            double prog = Math.min(1, x / 0.3);
            float al = (x > 0.75f ? Math.max(0, (1 - x) / 0.25f) : 1f) * amul * (0.85f + 0.15f * (float) Math.sin(t * 0.6));
            double w = (0.045 + R0 * 0.012) * wmul, rot = t * 0.025, rot2 = -t * 0.04;
            ringLine(vc, m, R0, prog, w * 1.3, al);
            ringLine(vc, m, R0 * 0.84, prog, w, al);
            ringLine(vc, m, R0 * 0.3, prog, w, al);
            poly(vc, m, R0 * 0.84, 3, rot, prog, w, al);
            if (style >= 1) poly(vc, m, R0 * 0.84, 3, rot + Math.PI, prog, w, al);   // 육각별
            if (style == 2) poly(vc, m, R0 * 0.42, 6, rot2, prog, w * 0.8, al);
            int ticks = style == 2 ? 36 : 24;   // 두 원 사이 글자 눈금 (짧은 선 · 점)
            for (int i = 0; i < ticks * prog; i++) {
                double a = rot2 + Math.PI * 2 * i / ticks;
                Vec3d d = new Vec3d(Math.cos(a), 0, Math.sin(a)), s = new Vec3d(-d.z, 0, d.x);
                Vec3d mid = c.add(d.multiply(R0 * 0.92));
                if (i % 3 == 0) flatLine(vc, m, mid.subtract(d.multiply(R0 * 0.05)), mid.add(d.multiply(R0 * 0.05)), w * 0.8, col, al);
                else flatLine(vc, m, mid.subtract(s.multiply(R0 * 0.025)), mid.add(s.multiply(R0 * 0.025)), w * 0.8, col, al);
            }
        }
        @Override void glow(VertexConsumer vc, Matrix4f m, ClientWorld w) { draw(vc, m, 2.6, 0.3f); draw(vc, m, 1, 0.95f); }
    }

    // ───────── 김 ─────────
    static final class Puff extends Fx {
        final Vec3d[] at; final double[] sz; final float[] col;
        Puff(Vec3d c, double size, int life, float[] col, int seed) {
            super(Math.max(4, life)); this.col = col;
            Random r = new Random(seed);
            at = new Vec3d[5]; sz = new double[5];
            for (int i = 0; i < 5; i++) { at[i] = c.add(r.nextGaussian() * 0.3 * size, r.nextDouble() * 0.3 * size, r.nextGaussian() * 0.3 * size); sz[i] = size * (0.5 + r.nextDouble() * 0.5); }
        }
        @Override void tl(VertexConsumer vc, Matrix4f m, ClientWorld w) {
            float x = life(), k = 1 - x;
            for (int i = 0; i < at.length; i++) disc(vc, m, at[i].add(0, x * 0.6, 0), sz[i] * (0.6 + 0.8 * x), col, 0.45f * k, 0f);
        }
    }
}
