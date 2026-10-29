package bus.muhyeon.duel;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtFloat;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 신의 손 연출: 대상 바로 뒤 땅속에서 거대한 바위 손 하나가 솟아오르고,
 * 손가락 마디(3마디 + 엄지 2마디)가 구부러지며 대상을 통째로 움켜쥔다.
 * 전부 블록 디스플레이라 지형은 건드리지 않음.
 */
public final class GodHandFx {
    private GodHandFx() {}

    private static final BlockState PALM = Blocks.POLISHED_ANDESITE.getDefaultState();
    private static final BlockState KNUCKLE = Blocks.STONE.getDefaultState();
    private static final BlockState TIP = Blocks.ANDESITE.getDefaultState();
    private static final DustParticleEffect BLUE = new DustParticleEffect(new Vector3f(0.4f, 0.8f, 1.0f), 1.0f);

    // 손 치수 (블록 단위, K 배 확대)
    private static final float K = 1.8f;
    private static final float PALM_W = 2.4f * K, PALM_H = 2.2f * K, PALM_D = 0.8f * K;
    private static final float[] FINGER_X = {-0.9f * K, -0.3f * K, 0.3f * K, 0.9f * K};
    private static final float[][] FINGER_LEN = {{0.8f * K, 0.6f * K, 0.5f * K}, {0.95f * K, 0.7f * K, 0.55f * K},
            {0.9f * K, 0.7f * K, 0.55f * K}, {0.7f * K, 0.5f * K, 0.45f * K}};
    private static final float FINGER_W = 0.52f * K, FINGER_D = 0.6f * K;
    private static final float THUMB_W = 0.6f * K, THUMB_D = 0.62f * K;
    private static final float[] THUMB_LEN = {0.8f * K, 0.6f * K};
    private static final float WRIST_LEN = 1.1f * K;

    /** 한 마디 */
    private record Seg(Entity e, BlockState state, float w, float h, float d) {}
    private record Hand(List<Seg> segs, double x, double y, double z, float yaw) {}

    /** at = 대상 발 위치. close 틱에 움켜쥠, hold 틱 동안 유지 후 가라앉음 */
    public static void play(ServerWorld w, Vec3d at, double groundY, int close, int hold) {
        // 대상 뒤쪽(무작위 방향)에서 손바닥이 대상을 향하도록
        double ang = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        double dx = Math.cos(ang), dz = Math.sin(ang);
        double hx = at.x - dx * 1.6, hz = at.z - dz * 1.6;
        float yaw = (float) Math.atan2(at.x - hx, at.z - hz);
        // 공중에 있는 상대면 손이 그 높이까지 솟고, 아래로 손목·팔뚝이 땅까지 이어짐
        double liftY = Math.max(groundY - 1.0, at.y - 1.4);
        float armLen = (float) Math.max(8.0, liftY - groundY + 3.0);
        Hand h = spawn(w, hx, groundY - 7.5, hz, yaw, armLen);

        // 1) 땅을 뚫고 솟아오름 + 푸른 연성 스파크
        Tasks.later(1, () -> moveAll(h, hx, liftY, hz));
        w.playSound(null, BlockPos.ofFloored(at), SoundEvents.BLOCK_DEEPSLATE_BREAK, SoundCategory.PLAYERS, 2.0f, 0.4f);
        w.playSound(null, BlockPos.ofFloored(at), SoundEvents.ENTITY_WARDEN_DIG, SoundCategory.PLAYERS, 1.2f, 0.8f);
        for (int t = 0; t < close; t += 2) {
            Tasks.later(t, () -> {
                ThreadLocalRandom r = ThreadLocalRandom.current();
                for (int i = 0; i < 8; i++)
                    w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, hx + r.nextGaussian() * 1.5, groundY + r.nextDouble() * (liftY - groundY + 6), hz + r.nextGaussian() * 1.5, 1, 0, 0, 0, 0.05);
                w.spawnParticles(BLUE, hx, groundY + 0.2, hz, 10, 1.8, 0.1, 1.8, 0);
                w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, KNUCKLE), hx, groundY + 0.1, hz, 20, 2.0, 0.2, 2.0, 0.3);
            });
        }

        // 2) 손가락 마디가 구부러지며 대상을 통째로 움켜쥠
        Tasks.later(close, () -> {
            pose(h, true);
            w.playSound(null, BlockPos.ofFloored(at), SoundEvents.ENTITY_IRON_GOLEM_ATTACK, SoundCategory.PLAYERS, 1.6f, 0.35f);
            w.playSound(null, BlockPos.ofFloored(at), SoundEvents.BLOCK_STONE_BREAK, SoundCategory.PLAYERS, 2.0f, 0.4f);
            w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, PALM), at.x, at.y + 1.5, at.z, 60, 0.8, 1.0, 0.8, 0.3);
            w.spawnParticles(ParticleTypes.EXPLOSION, at.x, at.y + 1.5, at.z, 1, 0, 0, 0, 0);
        });

        // 3) 풀리면서 가라앉고 사라짐
        Tasks.later(close + hold, () -> { pose(h, false); moveAll(h, hx, groundY - 8.0 - (liftY - groundY), hz); });
        Tasks.later(close + hold + 12, () -> { for (Seg sg : h.segs()) sg.e().discard(); });
    }

    // ───────── 손 만들기 ─────────

    private static final int SEGS = 17; // 손바닥 1 + 손가락 12 + 엄지 2 + 손목 1 + 팔뚝 1

    private static Hand spawn(ServerWorld w, double x, double y, double z, float yaw, float armLen) {
        List<Seg> segs = new ArrayList<>();
        segs.add(new Seg(display(w, PALM, x, y, z), PALM, PALM_W, PALM_H, PALM_D));
        for (int f = 0; f < 4; f++)
            for (int j = 0; j < 3; j++)
                segs.add(new Seg(display(w, j == 2 ? TIP : KNUCKLE, x, y, z), j == 2 ? TIP : KNUCKLE, FINGER_W, FINGER_LEN[f][j], FINGER_D));
        for (int j = 0; j < 2; j++)
            segs.add(new Seg(display(w, j == 1 ? TIP : KNUCKLE, x, y, z), j == 1 ? TIP : KNUCKLE, THUMB_W, THUMB_LEN[j], THUMB_D));
        segs.add(new Seg(display(w, PALM, x, y, z), PALM, PALM_W * 0.74f, WRIST_LEN, PALM_D * 0.95f));   // 손목
        segs.add(new Seg(display(w, KNUCKLE, x, y, z), KNUCKLE, PALM_W * 0.82f, armLen, PALM_D * 1.08f)); // 팔뚝 (땅속까지)
        segs.removeIf(sg -> sg.e() == null);
        Hand h = new Hand(segs, x, y, z, yaw);
        if (segs.size() == SEGS) pose(h, false);
        return h;
    }

    /**
     * 손 모양 계산 (정기구학). 손 좌표계: 손바닥 아래 가운데가 원점, 손가락은 +Y, 손바닥 면은 +Z.
     * 마디마다 X축으로 꺾어서 +Z(대상 쪽)로 구부림.
     */
    private static void pose(Hand h, boolean closed) {
        if (h.segs().size() != SEGS) return;
        Quaternionf handRot = new Quaternionf().rotateY(h.yaw()).rotateX((float) Math.toRadians(closed ? 22 : 4)); // 움켜쥘 때 대상 쪽으로 숙임
        int i = 0;
        // 손바닥
        place(h.segs().get(i++), handRot, new Vector3f(0, 0, 0));
        // 손가락 4개 × 3마디
        float[] curl = closed ? new float[]{60, 68, 60} : new float[]{6, 10, 8};
        for (int f = 0; f < 4; f++) {
            Vector3f base = new Vector3f(FINGER_X[f], PALM_H - 0.05f, 0.05f * K);
            Quaternionf q = new Quaternionf();
            float spread = (float) Math.toRadians((f - 1.5f) * (closed ? 2 : 6)); // 펼치면 손가락이 살짝 벌어짐
            q.rotateZ(-spread);
            for (int j = 0; j < 3; j++) {
                q.rotateX((float) Math.toRadians(curl[j]));
                Seg s = h.segs().get(i++);
                place(s, new Quaternionf(handRot).mul(q), new Vector3f(base).rotate(handRot));
                base.add(new Vector3f(0, s.h() * 0.96f, 0).rotate(q));
            }
        }
        // 엄지: 손바닥 옆 아래쪽에서 비스듬히
        Vector3f tb = new Vector3f(PALM_W / 2 - 0.15f * K, 0.9f * K, 0.25f * K);
        Quaternionf tq = new Quaternionf().rotateZ((float) Math.toRadians(-35)).rotateX((float) Math.toRadians(closed ? 55 : 12));
        for (int j = 0; j < 2; j++) {
            Seg s = h.segs().get(i++);
            place(s, new Quaternionf(handRot).mul(tq), new Vector3f(tb).rotate(handRot));
            tb.add(new Vector3f(0, s.h() * 0.95f, 0).rotate(tq));
            tq.rotateX((float) Math.toRadians(closed ? 45 : 8));
        }
        // 손목 → 팔뚝: 손바닥 아래로 곧게 (손이 숙여지면 같이 기울어짐)
        Seg wrist = h.segs().get(i++);
        place(wrist, handRot, new Vector3f(0, -wrist.h(), 0.02f * K).rotate(handRot));
        Seg fore = h.segs().get(i);
        place(fore, handRot, new Vector3f(0, -wrist.h() - fore.h() + 0.05f, 0.04f * K).rotate(handRot));
    }

    /** 마디 하나: base 에서 rot 방향으로 서는 상자. 디스플레이 변환 = T + R·(S·p) 이므로 가운데 정렬 보정 */
    private static void place(Seg s, Quaternionf rot, Vector3f base) {
        Vector3f c = new Vector3f(s.w() / 2, 0, s.d() / 2).rotate(new Quaternionf(rot));
        Vector3f t = new Vector3f(base).sub(c);
        NbtCompound tr = new NbtCompound();
        tr.put("translation", floats(t.x, t.y, t.z));
        tr.put("left_rotation", floats(rot.x, rot.y, rot.z, rot.w));
        tr.put("scale", floats(s.w(), s.h(), s.d()));
        tr.put("right_rotation", floats(0, 0, 0, 1));
        NbtCompound nbt = new NbtCompound();
        s.e().writeNbt(nbt);
        nbt.put("transformation", tr);
        nbt.putInt("interpolation_duration", 4);
        nbt.putInt("start_interpolation", 0);
        s.e().readNbt(nbt);
    }

    private static void moveAll(Hand h, double x, double y, double z) {
        for (Seg s : h.segs()) s.e().requestTeleport(x, y, z);
    }

    /** 다른 연출에서도 쓰는 공용: 블록 디스플레이 하나 생성 */
    static Entity display(ServerWorld w, BlockState state, double x, double y, double z) {
        NbtCompound n = new NbtCompound();
        n.putString("id", "minecraft:block_display");
        n.put("block_state", NbtHelper.fromBlockState(state));
        n.putInt("teleport_duration", 6);
        n.putFloat("view_range", 3.0f);
        Entity e = EntityType.loadEntityWithPassengers(n, w, ent -> {
            ent.refreshPositionAndAngles(x, y, z, 0, 0);
            return ent;
        });
        if (e != null) w.spawnEntity(e);
        return e;
    }

    /** 공용: 디스플레이 변환(위치·회전·크기)을 바꾸고 interp 틱 동안 부드럽게 보간 */
    static void transform(Entity e, Quaternionf rot, Vector3f translation, Vector3f scale, int interp) {
        if (e == null) return;
        NbtCompound tr = new NbtCompound();
        tr.put("translation", floats(translation.x, translation.y, translation.z));
        tr.put("left_rotation", floats(rot.x, rot.y, rot.z, rot.w));
        tr.put("scale", floats(scale.x, scale.y, scale.z));
        tr.put("right_rotation", floats(0, 0, 0, 1));
        NbtCompound nbt = new NbtCompound();
        e.writeNbt(nbt);
        nbt.put("transformation", tr);
        nbt.putInt("interpolation_duration", interp);
        nbt.putInt("start_interpolation", 0);
        e.readNbt(nbt);
    }

    private static NbtList floats(float... v) {
        NbtList l = new NbtList();
        for (float f : v) l.add(NbtFloat.of(f));
        return l;
    }
}
