package bbc.combat;

import bbc.core.Tasks;
import bbc.mixin.BlockDisplayAccessor;
import bbc.mixin.DisplayEntityAccessor;
import bbc.mixin.TextDisplayAccessor;
import bbc.net.FxPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.Brightness;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 파티클 말고 다른 연출들.
 *  - 칼자국(스미어): 얇은 반투명 판(블록 디스플레이) 여러 장을 휘두른 호를 따라 차례로 띄웠다가 클라이언트 보간으로 얇아지며 사라짐
 *    (에픽파이트가 무기 끝을 따라 그리는 '트레일'과 같은 역할)
 *  - 충격파: 둥글게 놓은 판들이 보간으로 바깥으로 퍼지며 얇아짐
 *  - 타격 글자: 코믹스 효과음(BAM! THUD!)을 그림 글꼴(붓글씨 + 그라데이션 + 테두리)로, 기울여 툭 튀어나오게
 *  - 화면 흔들림·번쩍임: 클라이언트에 보냄 (CombatHud, CameraMixin)
 *  - 히트스톱: 큰 타격 때 때린 쪽·맞은 쪽을 몇 틱 멈춤 (Combat.hitStop)
 */
public final class Fx {
    private Fx() {}

    public static final BlockState WHITE = Blocks.WHITE_STAINED_GLASS.getDefaultState();
    public static final BlockState GOLD = Blocks.YELLOW_STAINED_GLASS.getDefaultState();
    public static final BlockState RED = Blocks.RED_STAINED_GLASS.getDefaultState();

    // ═════════ 칼자국 ═════════

    /** vertical 이면 위→아래, 아니면 오른쪽→왼쪽 (reverse 면 반대) */
    public static void slash(ServerWorld w, LivingEntity p, double radius, boolean vertical, boolean reverse, BlockState color) {
        slash(w, p, radius, vertical ? 90 : 0, reverse, color);
    }

    /**
     * 내 앞쪽 반원 호를 따라 판을 깔아 칼자국을 그림. tilt = 호가 기운 각 (0 = 가로 오른쪽→왼쪽, 90 = 세로 위→아래,
     * 45 = 오른쪽 위 → 왼쪽 아래 사선). reverse 면 반대 방향으로 그어짐.
     * 판은 0.5틱 간격으로 순서대로 생겨서 휘두르는 방향이 보이고, 5틱 동안 얇아지며 사라진다.
     */
    public static void slash(ServerWorld w, LivingEntity p, double radius, double tilt, boolean reverse, BlockState color) {
        Vec3d f = Combat.horizontal(p.getRotationVec(1f));
        Vec3d r = new Vec3d(-f.z, 0, f.x);
        double t = Math.toRadians(tilt);
        Vec3d side = r.multiply(Math.cos(t)).add(0, Math.sin(t), 0);   // 호가 시작하는 쪽
        Vec3d o = p.getPos().add(0, 1.15, 0);
        int n = 11;
        for (int i = 0; i < n; i++) {
            double u = (reverse ? n - 1 - i : i) / (double) (n - 1);
            double a = Math.PI * (u - 0.5) * 0.95;                      // -85° ~ +85°
            Vec3d radial = f.multiply(Math.cos(a)).add(side.multiply(-Math.sin(a)));
            Vec3d tangent = f.multiply(-Math.sin(a)).add(side.multiply(-Math.cos(a)));
            Vec3d normal = radial.crossProduct(tangent).normalize();
            // 가운데(정면)가 가장 넓고 양끝이 가늘게: 붓으로 그은 듯한 초승달
            float width = (float) (0.2 + 0.45 * Math.cos(a));
            Vec3d at = o.add(radial.multiply(radius));
            double seg = Math.PI * radius / n * 1.15;
            int delay = i / 3;
            Tasks.later(delay, () -> plate(w, at, tangent, normal, radial, (float) seg, 0.05f, width, color, 5));
            // 바깥쪽 가장자리에 가는 먹선: 만화 칼자국처럼 흰 호에 검은 테두리
            Vec3d edge = o.add(radial.multiply(radius + width * 0.5 + 0.02));
            Tasks.later(delay, () -> plate(w, edge, tangent, normal, radial, (float) seg, 0.06f, 0.05f, INK, 4));
        }
    }

    /** 판 하나: 중심 at, 길이 방향 tangent(길이 len), 두께 방향 normal(thick), 폭 방향 radial(width). life 틱 동안 얇아지며 사라짐 */
    static void plate(ServerWorld w, Vec3d at, Vec3d tangent, Vec3d normal, Vec3d radial, float len, float thick, float width, BlockState color, int life) {
        DisplayEntity.BlockDisplayEntity d = EntityType.BLOCK_DISPLAY.create(w);
        if (d == null) return;
        d.refreshPositionAndAngles(at.x, at.y, at.z, 0, 0);
        DisplayEntityAccessor acc = (DisplayEntityAccessor) d;
        ((BlockDisplayAccessor) d).bbc$setBlockState(color);
        acc.bbc$setBrightness(new Brightness(15, 15));
        Quaternionf q = basis(tangent, normal, radial);
        acc.bbc$setTransformation(centered(q, new Vector3f(len, thick, width)));
        w.spawnEntity(d);
        // 다음 틱: 보간을 켜고 길이는 그대로, 두께·폭을 0으로 → 클라이언트가 life 틱 동안 부드럽게 줄임
        Tasks.later(1, () -> {
            if (d.isRemoved()) return;
            acc.bbc$setInterpolationDuration(life);
            acc.bbc$setStartInterpolation(0);
            acc.bbc$setTransformation(centered(q, new Vector3f(len * 1.1f, 0.001f, 0.001f)));
        });
        Tasks.later(life + 2, d::discard);
    }

    // ═════════ 충격파 ═════════

    /** 바닥에 둥근 충격파: 판 16장이 r0 에서 r1 까지 퍼지며 얇아짐 */
    public static void wave(ServerWorld w, Vec3d c, double r0, double r1, BlockState color, int life) {
        int n = 16;
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 * i / n;
            Vec3d radial = new Vec3d(Math.cos(a), 0, Math.sin(a));
            Vec3d tangent = new Vec3d(-Math.sin(a), 0, Math.cos(a));
            Vec3d up = new Vec3d(0, 1, 0);
            DisplayEntity.BlockDisplayEntity d = EntityType.BLOCK_DISPLAY.create(w);
            if (d == null) return;
            d.refreshPositionAndAngles(c.x, c.y, c.z, 0, 0);
            DisplayEntityAccessor acc = (DisplayEntityAccessor) d;
            ((BlockDisplayAccessor) d).bbc$setBlockState(color);
            acc.bbc$setBrightness(new Brightness(15, 15));
            Quaternionf q = basis(tangent, up, radial);
            float seg0 = (float) (Math.PI * 2 * r0 / n * 1.1), seg1 = (float) (Math.PI * 2 * r1 / n * 1.1);
            acc.bbc$setTransformation(at(q, radial.multiply(r0), new Vector3f(seg0, 0.35f, 0.25f)));
            w.spawnEntity(d);
            Tasks.later(1, () -> {
                if (d.isRemoved()) return;
                acc.bbc$setInterpolationDuration(life);
                acc.bbc$setStartInterpolation(0);
                acc.bbc$setTransformation(at(q, radial.multiply(r1), new Vector3f(seg1, 0.01f, 0.05f)));
            });
            Tasks.later(life + 2, d::discard);
        }
    }

    // ═════════ 타격 글자 ═════════

    // ═════════ 일본 만화식 효과음 (描き文字) ═════════

    /** tools/make_sfx_font.py 의 WORDS 와 같은 순서. 단어 하나 = 그림 글꼴 bbc:sfx_jp 의 글자 하나 (U+E000~) */
    public enum Sfx {
        DON, BAKI, DOGO, GA, ZUDON, DOGON, ZAN, ZUBA, GAGIN, BAKIN, GOGO, DODODO, BUON, DOGOOON;
        String glyph() { return String.valueOf((char) (0xE000 + ordinal())); }
    }
    public static final Sfx[] BLUNT = {Sfx.DON, Sfx.BAKI, Sfx.DOGO, Sfx.GA};   // 주먹·둔기 마무리
    public static final Sfx[] BLADE = {Sfx.ZAN, Sfx.ZUBA};                     // 칼·창 마무리
    public static final Sfx[] SLAM = {Sfx.ZUDON, Sfx.DOGON};                   // 내리찍기 착지
    private static final Identifier SFX_FONT = Identifier.of("bbc", "sfx_jp");

    public static void word(ServerWorld w, Vec3d pos, Sfx[] pool, float size) {
        word(w, pos, pool[w.getRandom().nextInt(pool.length)], size);
    }

    /** 효과음 그림을 띄움: ±10° 기울어져 툭 튀어나오듯 커졌다가(2틱) 살짝 줄고, 비스듬히 떠오르며 사라짐 */
    public static void word(ServerWorld w, Vec3d pos, Sfx sfx, float size) {
        float tilt = (float) Math.toRadians((w.getRandom().nextFloat() * 2 - 1) * 10);
        Vec3d at = pos.add((w.getRandom().nextDouble() - 0.5) * 0.6, 0, (w.getRandom().nextDouble() - 0.5) * 0.6);
        text(w, at, sfx.glyph(), SFX_FONT, size, tilt, new Vector3f());
    }

    private static void text(ServerWorld w, Vec3d pos, String s, Identifier font, float size, float tilt, Vector3f off) {  // s = 그림 글자
        DisplayEntity.TextDisplayEntity d = EntityType.TEXT_DISPLAY.create(w);
        if (d == null) return;
        d.refreshPositionAndAngles(pos.x, pos.y, pos.z, 0, 0);
        DisplayEntityAccessor acc = (DisplayEntityAccessor) d;
        TextDisplayAccessor ta = (TextDisplayAccessor) d;
        ta.bbc$setText(Text.literal(s).setStyle(Style.EMPTY.withFont(font).withColor(0xFFFFFF)));
        ta.bbc$setBackground(0);
        ta.bbc$setLineWidth(400);
        ta.bbc$setDisplayFlags(DisplayEntity.TextDisplayEntity.SEE_THROUGH_FLAG);   // 벽·몸에 안 가려짐
        acc.bbc$setBillboardMode(DisplayEntity.BillboardMode.CENTER);
        acc.bbc$setBrightness(new Brightness(15, 15));
        acc.bbc$setTransformation(tilted(size * 0.3f, tilt, off, 0));
        w.spawnEntity(d);
        Tasks.later(1, () -> { if (!d.isRemoved()) { acc.bbc$setInterpolationDuration(2); acc.bbc$setStartInterpolation(0); acc.bbc$setTransformation(tilted(size * 1.35f, tilt, off, 0)); } });
        Tasks.later(3, () -> { if (!d.isRemoved()) { acc.bbc$setInterpolationDuration(9); acc.bbc$setStartInterpolation(0); acc.bbc$setTransformation(tilted(size * 1.1f, tilt * 1.2f, off, 0.25f)); } });
        Tasks.later(12, () -> { if (!d.isRemoved()) { acc.bbc$setInterpolationDuration(4); acc.bbc$setStartInterpolation(0); acc.bbc$setTransformation(tilted(0.01f, tilt, off, 0.45f)); } });
        Tasks.later(17, d::discard);
    }

    private static AffineTransformation tilted(float s, float tilt, Vector3f off, float rise) {
        Vector3f t = new Vector3f(off).mul(s).add(0, rise, 0);
        return new AffineTransformation(t, new Quaternionf().rotateZ(tilt), new Vector3f(s, s, s), new Quaternionf());
    }

    // ═════════ 만화 연출 ═════════

    public static final BlockState INK = Blocks.BLACK_CONCRETE.getDefaultState();
    public static final BlockState PAPER = Blocks.WHITE_CONCRETE.getDefaultState();

    /**
     * 타격 순간의 '파열' (만화에서 맞은 자리에 터지는 뾰족한 흰·검은 가시): 가는 가시 판을 사방으로 뻗었다가 3~4틱 만에 바깥으로 날아가며 사라짐.
     * dir = 때린 방향 (가시가 그쪽으로 더 길게 뻗음)
     */
    public static void burst(ServerWorld w, Vec3d c, Vec3d dir, float size) {
        var rnd = w.getRandom();
        int n = 14;
        for (int i = 0; i < n; i++) {
            Vec3d d = new Vec3d(rnd.nextGaussian(), rnd.nextGaussian() * 0.7, rnd.nextGaussian()).normalize();
            if (dir != null) d = d.add(dir.multiply(0.8)).normalize();
            Vec3d side = d.crossProduct(new Vec3d(0, 1, 0));
            if (side.lengthSquared() < 1e-4) side = new Vec3d(1, 0, 0);
            side = side.normalize();
            Vec3d up = d.crossProduct(side).normalize();
            float len = size * (0.6f + rnd.nextFloat() * 0.9f);
            BlockState col = i % 3 == 0 ? INK : PAPER;
            spike(w, c.add(d.multiply(len * 0.5 + 0.2)), d, up, side, len, size * 0.09f, col, 3 + rnd.nextInt(2));
        }
    }

    /** 가시 하나: 길이 방향 d, 바깥으로 밀려나며 가늘어짐 */
    private static void spike(ServerWorld w, Vec3d at, Vec3d d, Vec3d up, Vec3d side, float len, float width, BlockState color, int life) {
        DisplayEntity.BlockDisplayEntity e = EntityType.BLOCK_DISPLAY.create(w);
        if (e == null) return;
        e.refreshPositionAndAngles(at.x, at.y, at.z, 0, 0);
        DisplayEntityAccessor acc = (DisplayEntityAccessor) e;
        ((BlockDisplayAccessor) e).bbc$setBlockState(color);
        acc.bbc$setBrightness(new Brightness(15, 15));
        Quaternionf q = basis(d, up, side);
        acc.bbc$setTransformation(at(q, Vec3d.ZERO, new Vector3f(len, width, width * 0.4f)));
        w.spawnEntity(e);
        Tasks.later(1, () -> {
            if (e.isRemoved()) return;
            acc.bbc$setInterpolationDuration(life);
            acc.bbc$setStartInterpolation(0);
            acc.bbc$setTransformation(at(q, d.multiply(len * 0.7), new Vector3f(len * 1.3f, 0.002f, 0.002f)));
        });
        Tasks.later(life + 2, e::discard);
    }

    /** 바닥이 깨져 튀는 돌덩이: 땅 블록 조각이 돌면서 솟았다가 떨어짐 */
    public static void debris(ServerWorld w, Vec3d c, int n, double spread) {
        BlockState ground = w.getBlockState(net.minecraft.util.math.BlockPos.ofFloored(c).down());
        if (ground.isAir() || !ground.getFluidState().isEmpty()) ground = Blocks.COBBLESTONE.getDefaultState();
        var rnd = w.getRandom();
        for (int i = 0; i < n; i++) {
            BlockState st = ground;
            float s = 0.18f + rnd.nextFloat() * 0.3f;
            double a = rnd.nextDouble() * Math.PI * 2, r = spread * (0.4 + rnd.nextDouble() * 0.8);
            Vec3d out = new Vec3d(Math.cos(a) * r, 0, Math.sin(a) * r);
            double h = 0.8 + rnd.nextDouble() * 1.4;
            DisplayEntity.BlockDisplayEntity e = EntityType.BLOCK_DISPLAY.create(w);
            if (e == null) return;
            e.refreshPositionAndAngles(c.x, c.y, c.z, 0, 0);
            DisplayEntityAccessor acc = (DisplayEntityAccessor) e;
            ((BlockDisplayAccessor) e).bbc$setBlockState(st);
            Quaternionf q0 = new Quaternionf().rotateXYZ(rnd.nextFloat() * 6f, rnd.nextFloat() * 6f, rnd.nextFloat() * 6f);
            Quaternionf q1 = new Quaternionf(q0).rotateXYZ(2.5f, 1.7f, 0.9f);
            Quaternionf q2 = new Quaternionf(q1).rotateXYZ(2.0f, 1.2f, 1.5f);
            Vector3f sz = new Vector3f(s, s * (0.6f + rnd.nextFloat() * 0.5f), s);
            acc.bbc$setTransformation(at(q0, Vec3d.ZERO, sz));
            w.spawnEntity(e);
            Vec3d peak = out.multiply(0.6).add(0, h, 0), land = out.add(0, 0.05, 0);
            Tasks.later(1, () -> { if (!e.isRemoved()) { acc.bbc$setInterpolationDuration(5); acc.bbc$setStartInterpolation(0); acc.bbc$setTransformation(at(q1, peak, sz)); } });
            Tasks.later(6, () -> { if (!e.isRemoved()) { acc.bbc$setInterpolationDuration(6); acc.bbc$setStartInterpolation(0); acc.bbc$setTransformation(at(q2, land, sz)); } });
            Tasks.later(16, () -> { if (!e.isRemoved()) { acc.bbc$setInterpolationDuration(6); acc.bbc$setStartInterpolation(0); acc.bbc$setTransformation(at(q2, land.add(0, -0.1, 0), new Vector3f(0.01f))); } });
            Tasks.later(23, e::discard);
        }
        w.spawnParticles(net.minecraft.particle.ParticleTypes.CLOUD, c.x, c.y + 0.2, c.z, 18, spread * 0.5, 0.1, spread * 0.5, 0.06);
    }

    /**
     * 날아가는 상대 뒤에 남는 속도선 (만화의 흐름선): ticks 동안 매 틱 몸 뒤로 가늘고 긴 흰·검은 선을 몇 개 그림
     */
    public static void streak(LivingEntity t, int ticks) {
        ServerWorld w = (ServerWorld) t.getWorld();
        for (int k = 1; k <= ticks; k++) {
            final int kk = k;
            Tasks.later(k, () -> {
                if (!t.isAlive()) return;
                Vec3d v = t.getVelocity();
                if (v.lengthSquared() < 0.04) return;
                Vec3d d = v.normalize();
                Vec3d side = d.crossProduct(new Vec3d(0, 1, 0));
                if (side.lengthSquared() < 1e-4) side = new Vec3d(1, 0, 0);
                side = side.normalize();
                Vec3d up = d.crossProduct(side).normalize();
                var rnd = w.getRandom();
                for (int i = 0; i < 3; i++) {
                    Vec3d off = side.multiply((rnd.nextDouble() - 0.5) * 1.0).add(up.multiply((rnd.nextDouble() - 0.5) * 1.4));
                    float len = 1.2f + rnd.nextFloat() * 1.6f;
                    Vec3d at = t.getBoundingBox().getCenter().add(off).subtract(d.multiply(len * 0.5 + 0.4));
                    plate(w, at, d, up, side, len, 0.015f, 0.04f, (kk + i) % 3 == 0 ? INK : PAPER, 3);
                }
            });
        }
    }

    /** 집중선 (화면 가장자리에서 가운데로 몰리는 검은 선) */
    public static void lines(LivingEntity e, float strength) {
        if (e instanceof ServerPlayerEntity p && ServerPlayNetworking.canSend(p, FxPayload.ID))
            ServerPlayNetworking.send(p, new FxPayload(FxPayload.LINES, strength));
    }

    /** 큰 타격 한 번에 쓰는 만화 연출 묶음: 파열 + 효과음 + 집중선 (때린 쪽·맞은 쪽) */
    public static void mangaHit(ServerWorld w, LivingEntity atk, LivingEntity t, Sfx[] words, float power) {
        Vec3d c = t.getBoundingBox().getCenter();
        Vec3d dir = c.subtract(atk.getBoundingBox().getCenter());
        dir = dir.lengthSquared() > 1e-4 ? dir.normalize() : null;
        burst(w, c, dir, 1.6f * power);
        word(w, c.add(0, 1.2, 0), words, 1.6f * power);
        lines(atk, 0.7f * power);
        lines(t, power);
        streak(t, 6);
    }

    // ═════════ 화면 ═════════

    public static void shake(LivingEntity e, float strength) {
        if (e instanceof ServerPlayerEntity p && ServerPlayNetworking.canSend(p, FxPayload.ID))
            ServerPlayNetworking.send(p, new FxPayload(FxPayload.SHAKE, strength));
    }

    /** 주변 플레이어 모두 (거리에 따라 약해짐) */
    public static void shakeAround(ServerWorld w, Vec3d c, double radius, float strength) {
        for (ServerPlayerEntity p : w.getPlayers()) {
            double d = p.getPos().distanceTo(c);
            if (d < radius) shake(p, (float) (strength * (1 - d / radius * 0.7)));
        }
    }

    public static void flash(LivingEntity e, float strength) {
        if (e instanceof ServerPlayerEntity p && ServerPlayNetworking.canSend(p, FxPayload.ID))
            ServerPlayNetworking.send(p, new FxPayload(FxPayload.FLASH, strength));
    }

    // ═════════ 도우미 ═════════

    /** 열 벡터 (x=tangent, y=normal, z=radial) 로 회전 */
    private static Quaternionf basis(Vec3d x, Vec3d y, Vec3d z) {
        Matrix3f m = new Matrix3f(
                (float) x.x, (float) x.y, (float) x.z,
                (float) y.x, (float) y.y, (float) y.z,
                (float) z.x, (float) z.y, (float) z.z);
        return new Quaternionf().setFromNormalized(m);
    }

    /** 단위 블록(0~1)을 크기 s 로 늘린 뒤 회전 q, 중심이 원점에 오게 */
    private static AffineTransformation centered(Quaternionf q, Vector3f s) {
        return at(q, Vec3d.ZERO, s);
    }

    private static AffineTransformation at(Quaternionf q, Vec3d offset, Vector3f s) {
        Vector3f half = new Vector3f(s).mul(0.5f);
        Vector3f t = q.transform(new Vector3f(half)).negate().add((float) offset.x, (float) offset.y, (float) offset.z);
        return new AffineTransformation(t, new Quaternionf(q), s, new Quaternionf());
    }

    private static AffineTransformation scaleOnly(float s) {
        return new AffineTransformation(new Vector3f(), new Quaternionf(), new Vector3f(s, s, s), new Quaternionf());
    }
}
