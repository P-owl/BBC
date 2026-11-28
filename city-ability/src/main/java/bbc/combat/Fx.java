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
 * 파티클 말고 다른 연출들. 칼자국·충격파·고리·파열·번쩍·먹 초승달·소용돌이·속도선·드롭킥은 이제 클라이언트 모델 (WorldFx → client/FxShapes),
 * 흙덩이(블록 디스플레이)·효과음 글자(글자 디스플레이)는 그대로.
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
        // 클라이언트가 붓으로 그은 초승달 띠로 그림 (client/FxShapes.Smear — 바깥 날 진하게, 흰색은 먹선 테두리, 2틱에 걸쳐 그어 나감)
        WorldFx.slash(w, p.getPos().add(0, 1.15, 0), Combat.horizontal(p.getRotationVec(1f)), radius, tilt, reverse, WorldFx.rgb(color));
    }

    /** 판 하나: 중심 at, 길이 방향 tangent(길이 len), 두께 방향 normal(thick), 폭 방향 radial(width). life 틱 동안 얇아지며 사라짐 */
    static void plate(ServerWorld w, Vec3d at, Vec3d tangent, Vec3d normal, Vec3d radial, float len, float thick, float width, BlockState color, int life) {
        // 클라이언트가 가늘어지며 사라지는 띠로 그림 (찌르기 줄기 등)
        Vec3d half = tangent.normalize().multiply(len / 2.0);
        WorldFx.path(w, java.util.List.of(at.subtract(half), at.add(half)), Math.max(0.04, width), life + 1, WorldFx.rgb(color), false);
    }

    // ═════════ 충격파 ═════════

    /** 바닥에 둥근 충격파: 판 16장이 r0 에서 r1 까지 퍼지며 얇아짐 */
    public static void wave(ServerWorld w, Vec3d c, double r0, double r1, BlockState color, int life) {
        WorldFx.wave(w, c, r0, r1, life, WorldFx.rgb(color));
    }

    // ═════════ 타격 글자 ═════════

    // ═════════ 일본 만화식 효과음 (描き文字) ═════════

    /** tools/make_sfx_font.py 의 WORDS 와 같은 순서. 단어 하나 = 그림 글꼴 bbc:sfx_jp 의 글자 하나 (U+E000~) */
    public enum Sfx {
        DON, BAKI, DOGO, GA, ZUDON, DOGON, ZAN, ZUBA, GAGIN, BAKIN, GOGO, DODODO, BUON, DOGOOON, MEKYAN, GOOA, DOGUSHA, PIKIN;
        String glyph() { return SfxGlyphs.WORDS[ordinal()]; }
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
    public static void burst(ServerWorld w, Vec3d c, Vec3d dir, float size) { burst(w, c, dir, size, PAPER, INK); }

    /** 색을 고른 파열 (예: 금빛 가시 = 올스타 배틀식 타격 줄기) */
    public static void burst(ServerWorld w, Vec3d c, Vec3d dir, float size, BlockState main, BlockState accent) {
        WorldFx.burst(w, c, dir, size, WorldFx.rgb(main), WorldFx.rgb(accent));
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
        if (t.getWorld() instanceof ServerWorld w) WorldFx.lines(w, t, ticks);
    }

    /**
     * 드롭킥 (ABA): 맞은 자리에서 뒤쪽(차는 쪽)으로 길게 뻗는 흰 원뿔 줄기 + 날아가는 방향에 수직인 고리
     */
    public static void kickStreak(ServerWorld w, Vec3d at, Vec3d dir) {
        WorldFx.kick(w, at, dir);
    }

    /** 집중선 (화면 가장자리에서 가운데로 몰리는 검은 선) */
    public static void lines(LivingEntity e, float strength) {
        if (e instanceof ServerPlayerEntity p && ServerPlayNetworking.canSend(p, FxPayload.ID))
            ServerPlayNetworking.send(p, new FxPayload(FxPayload.LINES, strength));
    }

    // ═════════ 카메라를 보는 그림 이펙트 (ABA 의 흰 충격 고리·번쩍 원, 만화의 타격 별·먹 초승달) ═════════

    public enum Shape { RING, FLASH, STAR, CRESCENT, SWISH;
        String glyph() { return SfxGlyphs.SHAPES[ordinal()]; } }

    /** 그림 하나를 띄워 s0 → s1 로 grow 틱 동안 커지고, hold 틱 버틴 뒤 shrink 틱 동안 줄며 사라짐. rot = 화면에서 돌린 각(도) */
    public static void pop(ServerWorld w, Vec3d pos, Shape sh, float s0, float s1, int grow, int hold, int shrink, float rot) {
        DisplayEntity.TextDisplayEntity d = EntityType.TEXT_DISPLAY.create(w);
        if (d == null) return;
        d.refreshPositionAndAngles(pos.x, pos.y, pos.z, 0, 0);
        DisplayEntityAccessor acc = (DisplayEntityAccessor) d;
        TextDisplayAccessor ta = (TextDisplayAccessor) d;
        ta.bbc$setText(Text.literal(sh.glyph()).setStyle(Style.EMPTY.withFont(SFX_FONT).withColor(0xFFFFFF)));
        ta.bbc$setBackground(0);
        ta.bbc$setDisplayFlags(DisplayEntity.TextDisplayEntity.SEE_THROUGH_FLAG);
        acc.bbc$setBillboardMode(DisplayEntity.BillboardMode.CENTER);
        acc.bbc$setBrightness(new Brightness(15, 15));
        float r = (float) Math.toRadians(rot);
        acc.bbc$setTransformation(tilted(s0, r, new Vector3f(), 0));
        w.spawnEntity(d);
        Tasks.later(1, () -> { if (!d.isRemoved()) { acc.bbc$setInterpolationDuration(grow); acc.bbc$setStartInterpolation(0); acc.bbc$setTransformation(tilted(s1, r, new Vector3f(), 0)); } });
        if (shrink > 0)
            Tasks.later(1 + grow + hold, () -> { if (!d.isRemoved()) { acc.bbc$setInterpolationDuration(shrink); acc.bbc$setStartInterpolation(0); acc.bbc$setTransformation(tilted(0.01f, r, new Vector3f(), 0)); } });
        Tasks.later(2 + grow + hold + shrink, d::discard);
    }

    /**
     * 충격 고리 (ABA): 세상 속에 놓인 흰 고리가 퍼져 나감. axis = 고리 면에 수직인 방향
     * (올려치기·슬램 = 위, 넉백 = 날아가는 방향). n 겹이면 2틱씩 늦게 더 크게
     */
    public static void rings(ServerWorld w, Vec3d c, Vec3d axis, float size, int n) {
        Vec3d ax = axis == null || axis.lengthSquared() < 1e-4 ? new Vec3d(0, 1, 0) : axis.normalize();
        for (int i = 0; i < n; i++) {
            final int k = i;
            Tasks.later(i * 2, () -> ring3d(w, c, ax, 0.3, size * (1f + 0.35f * k), 0.22f, 6 + k, PAPER));
        }
    }

    /** 고리 하나: 판 20장을 axis 둘레로 둥글게 놓고 r0 → r1 로 퍼지며 얇아짐 */
    public static void ring3d(ServerWorld w, Vec3d c, Vec3d axis, double r0, double r1, float width, int life, BlockState color) {
        WorldFx.ring(w, c, axis, r0, r1, width, life, WorldFx.rgb(color));
    }

    /** 맞은 자리 번쩍 (ABA 의 흰 구가 확 커졌다 사라짐) */
    public static void impactFlash(ServerWorld w, Vec3d c, float size) {
        WorldFx.flash(w, c, size);
    }

    /** 먹 초승달 칼자국 (만화식 베기): 맞은 자리 둘레로 2~3개가 여러 각도로 그어짐 */
    public static void inkSlashes(ServerWorld w, Vec3d c, int n, float size) {
        WorldFx.ink(w, c, n, size);
    }

    /** 흰 바람 소용돌이 (ABA 가프 영상의 회오리 붓자국): 몸 둘레로 흰 초승달 n 개가 여러 각도로 돌며 퍼짐 */
    public static void swirl(ServerWorld w, Vec3d c, int n, float size) {
        WorldFx.swirl(w, c, n, size);
    }

    /** ABA 처럼 큰 타격 뒤에 남는 흙먼지 구름 (낮게 퍼져 1~2초 머묾) */
    public static void smoke(ServerWorld w, Vec3d c, int n, double spread) {
        w.spawnParticles(net.minecraft.particle.ParticleTypes.CAMPFIRE_COSY_SMOKE, c.x, c.y + 0.2, c.z, n, spread, 0.15, spread, 0.012);
        w.spawnParticles(net.minecraft.particle.ParticleTypes.POOF, c.x, c.y + 0.3, c.z, n, spread * 0.8, 0.2, spread * 0.8, 0.08);
    }

    /** 큰 타격 한 번에 쓰는 만화 연출 묶음: 파열 + 효과음 + 집중선 (때린 쪽·맞은 쪽) */
    public static void mangaHit(ServerWorld w, LivingEntity atk, LivingEntity t, Sfx[] words, float power) {
        Vec3d c = t.getBoundingBox().getCenter();
        Vec3d dir = c.subtract(atk.getBoundingBox().getCenter());
        dir = dir.lengthSquared() > 1e-4 ? dir.normalize() : null;
        burst(w, c, dir, 1.3f * power);
        impactFlash(w, c, power);
        rings(w, c, dir, 2.6f * power, 1);
        smoke(w, t.getPos(), (int) (8 * power), 0.9);
        word(w, c.add(0, 1.1, 0), words, 1.1f * power);
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
