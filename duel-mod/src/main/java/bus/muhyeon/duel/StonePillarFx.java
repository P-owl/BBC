package bus.muhyeon.duel;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtFloat;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * 바위 기둥 연성: 시전자 앞 땅에서 네모난 바위 기둥이 솟아,
 * 투사체처럼 대상을 추적해 날아간다. 지나간 궤적은 그대로 기둥으로 남았다가 부서져 사라짐.
 */
public final class StonePillarFx {
    private StonePillarFx() {}

    private static final BlockState BODY = Blocks.POLISHED_ANDESITE.getDefaultState();
    private static final BlockState HEAD = Blocks.STONE.getDefaultState();
    private static final float WIDTH = 0.75f;
    private static final double SPEED = 0.55;    // 블록/틱
    private static final double TURN = 0.18;     // 틱당 방향 꺾임 정도 (추적 세기)
    private static final int MAX_TICKS = 70;
    private static final int TRAIL_LIFE = 30;    // 궤적 기둥이 남아 있는 시간

    public static void launch(ServerPlayerEntity caster, LivingEntity target, double groundY, float damage) {
        ServerWorld w = caster.getServerWorld();
        Vec3d look = new Vec3d(caster.getRotationVector().x, 0, caster.getRotationVector().z).normalize();
        Vec3d start = new Vec3d(caster.getX() + look.x * 1.2, groundY - 0.4, caster.getZ() + look.z * 1.2);
        Vec3d dir0 = look.multiply(0.6).add(0, 0.8, 0).normalize(); // 처음엔 땅에서 비스듬히 솟아오름
        w.playSound(null, BlockPos.ofFloored(start), SoundEvents.BLOCK_DEEPSLATE_BREAK, SoundCategory.PLAYERS, 1.5f, 0.6f);
        w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, BODY), start.x, groundY + 0.1, start.z, 20, 0.4, 0.1, 0.4, 0.2);

        List<Entity> trail = new ArrayList<>();
        Vec3d[] pos = {start};
        Vec3d[] dir = {dir0};
        boolean[] done = {false};
        for (int t = 1; t <= MAX_TICKS; t++) {
            final int tick = t;
            Tasks.later(t, () -> {
                if (done[0]) return;
                Vec3d aim = target.isAlive() ? target.getPos().add(0, target.getHeight() * 0.5, 0) : pos[0].add(dir[0]);
                Vec3d want = aim.subtract(pos[0]).normalize();
                Vec3d d = dir[0].multiply(1 - TURN).add(want.multiply(TURN)).normalize();
                double step = Math.min(SPEED, aim.distanceTo(pos[0]));
                Vec3d next = pos[0].add(d.multiply(Math.max(step, 0.2)));

                // 지나간 구간을 기둥 한 토막으로
                Entity seg = segment(w, pos[0], next, tick % 4 == 0 ? HEAD : BODY);
                if (seg != null) trail.add(seg);
                if (tick % 3 == 0) w.playSound(null, BlockPos.ofFloored(next), SoundEvents.BLOCK_STONE_PLACE, SoundCategory.PLAYERS, 0.6f, 0.6f);
                w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, BODY), next.x, next.y, next.z, 3, 0.2, 0.2, 0.2, 0.05);

                pos[0] = next;
                dir[0] = d;

                boolean hit = target.isAlive() && next.distanceTo(aim) < 1.3;
                boolean blocked = !w.getBlockState(BlockPos.ofFloored(next)).getCollisionShape(w, BlockPos.ofFloored(next)).isEmpty() && tick > 3;
                if (hit || blocked || tick == MAX_TICKS) {
                    done[0] = true;
                    impact(caster, w, next, hit ? target : null, damage, d);
                    Tasks.later(TRAIL_LIFE, () -> crumble(w, trail));
                }
            });
        }
    }

    private static void impact(ServerPlayerEntity caster, ServerWorld w, Vec3d at, LivingEntity target, float damage, Vec3d dir) {
        w.playSound(null, BlockPos.ofFloored(at), SoundEvents.ENTITY_IRON_GOLEM_ATTACK, SoundCategory.PLAYERS, 1.4f, 0.5f);
        w.playSound(null, BlockPos.ofFloored(at), SoundEvents.BLOCK_STONE_BREAK, SoundCategory.PLAYERS, 1.6f, 0.6f);
        w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, HEAD), at.x, at.y, at.z, 40, 0.5, 0.5, 0.5, 0.3);
        w.spawnParticles(ParticleTypes.EXPLOSION, at.x, at.y, at.z, 1, 0, 0, 0, 0);
        if (target != null && target.isAlive()) {
            if (Targets.damage(caster, target, damage)) {
                target.addVelocity(dir.x * 1.0, 0.45, dir.z * 1.0);
                target.velocityModified = true;
            }
        }
    }

    private static void crumble(ServerWorld w, List<Entity> trail) {
        for (int i = 0; i < trail.size(); i++) {
            Entity e = trail.get(i);
            Tasks.later(i / 2, () -> {
                w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, BODY), e.getX(), e.getY(), e.getZ(), 6, 0.3, 0.3, 0.3, 0.05);
                e.discard();
            });
        }
    }

    /** from → to 를 잇는 네모난 기둥 토막 (블록 디스플레이) */
    private static Entity segment(ServerWorld w, Vec3d from, Vec3d to, BlockState state) { return segment(w, from, to, state, WIDTH); }

    /** from → to 를 잇는 네모난 토막 (다른 연출에서도 씀) */
    static Entity segment(ServerWorld w, Vec3d from, Vec3d to, BlockState state, float width) {
        Vec3d d = to.subtract(from);
        float len = (float) d.length() + 0.08f; // 살짝 겹쳐서 이음새가 안 보이게
        if (len < 0.05f) return null;
        Quaternionf rot = new Quaternionf().rotationTo(0, 1, 0, (float) d.x, (float) d.y, (float) d.z);
        Vector3f c = new Vector3f(width / 2, 0, width / 2).rotate(new Quaternionf(rot));
        Vector3f tr = new Vector3f(-c.x, -c.y, -c.z);

        NbtCompound n = new NbtCompound();
        n.putString("id", "minecraft:block_display");
        n.put("block_state", NbtHelper.fromBlockState(state));
        NbtCompound t = new NbtCompound();
        t.put("translation", floats(tr.x, tr.y, tr.z));
        t.put("left_rotation", floats(rot.x, rot.y, rot.z, rot.w));
        t.put("scale", floats(width, len, width));
        t.put("right_rotation", floats(0, 0, 0, 1));
        n.put("transformation", t);
        n.putFloat("view_range", 2.0f);
        Entity e = EntityType.loadEntityWithPassengers(n, w, ent -> {
            ent.refreshPositionAndAngles(from.x, from.y, from.z, 0, 0);
            return ent;
        });
        if (e != null) w.spawnEntity(e);
        return e;
    }

    private static NbtList floats(float... v) {
        NbtList l = new NbtList();
        for (float f : v) l.add(NbtFloat.of(f));
        return l;
    }
}
