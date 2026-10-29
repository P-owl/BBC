package bus.muhyeon.duel;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
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

import java.util.concurrent.ThreadLocalRandom;

/**
 * 바위 기둥 연성: 대상을 추적하며 대상 주변 땅에서 네모난 바위 기둥이 하나씩 비스듬히 솟아
 * 대상의 몸통을 찌른다. 기둥은 솟는 순간의 대상 위치를 겨누므로 도망쳐도 따라감.
 */
public final class PillarFx {
    private PillarFx() {}

    private static final BlockState[] STONES = {
            Blocks.POLISHED_ANDESITE.getDefaultState(), Blocks.STONE.getDefaultState(), Blocks.SMOOTH_STONE.getDefaultState()};

    public static final int COUNT = 4, GAP = 5;   // 기둥 4개, 0.25초 간격
    public static final float DAMAGE = 5f;         // 기둥 하나당

    /** groundOf: 땅 높이를 찾는 함수 (AlchemySkills.ground) */
    public static void launch(ServerPlayerEntity p, LivingEntity t, java.util.function.Function<Vec3d, Double> groundOf) {
        ServerWorld w = p.getServerWorld();
        double start = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        for (int i = 0; i < COUNT; i++) {
            final int k = i;
            Tasks.later(i * GAP, () -> {
                if (!t.isAlive() || t.getWorld() != w) return;
                // 대상 둘레 1.8~2.4칸, 매번 다른 방향에서
                double a = start + k * (Math.PI * 2 / COUNT) + ThreadLocalRandom.current().nextDouble(-0.4, 0.4);
                double r = 1.8 + ThreadLocalRandom.current().nextDouble(0.6);
                Vec3d foot = t.getPos();
                Vec3d base = new Vec3d(foot.x + Math.cos(a) * r, foot.y, foot.z + Math.sin(a) * r);
                Double gy = groundOf.apply(base);
                if (gy != null) base = new Vec3d(base.x, gy - 0.2, base.z);
                Vec3d aim = t.getPos().add(0, t.getHeight() * 0.55, 0);
                pillar(w, base, aim, k == COUNT - 1);
                // 기둥이 다 솟는 순간 판정 (그 사이 피했으면 빗나감)
                final Vec3d b0 = base;
                Tasks.later(2, () -> {
                    if (!t.isAlive()) return;
                    Vec3d dir = aim.subtract(b0).normalize();
                    Vec3d tip = b0.add(dir.multiply(aim.distanceTo(b0) + 0.3));
                    if (t.getBoundingBox().expand(0.45).raycast(b0, tip).isPresent()) {
                        Targets.damage(p, t, DAMAGE);
                        t.addVelocity(dir.x * 0.35, 0.25, dir.z * 0.35);
                        t.velocityModified = true;
                    }
                });
            });
        }
    }

    /** base(땅) → aim(대상 몸통) 방향으로 솟는 네모 기둥 */
    private static void pillar(ServerWorld w, Vec3d base, Vec3d aim, boolean last) {
        Vec3d d = aim.subtract(base);
        float len = (float) d.length() + 0.4f;
        float thick = last ? 0.95f : 0.75f;
        Vector3f dir = new Vector3f((float) d.x, (float) d.y, (float) d.z).normalize();
        Quaternionf rot = new Quaternionf().rotationTo(new Vector3f(0, 1, 0), dir)
                .rotateY((float) ThreadLocalRandom.current().nextDouble(Math.PI)); // 모서리 방향을 조금씩 다르게
        Vector3f center = new Vector3f(thick / 2, 0, thick / 2).rotate(new Quaternionf(rot));
        Vector3f t0 = new Vector3f(center).negate();

        Entity e = GodHandFx.display(w, STONES[ThreadLocalRandom.current().nextInt(STONES.length)], base.x, base.y, base.z);
        if (e == null) return;
        // 땅속에서 길이 0 → 2틱 만에 최대 길이로 솟음
        GodHandFx.transform(e, rot, t0, new Vector3f(thick, 0.05f, thick), 0);
        Tasks.later(1, () -> GodHandFx.transform(e, rot, t0, new Vector3f(thick, len, thick), 2));
        Tasks.later(30, () -> GodHandFx.transform(e, rot, t0, new Vector3f(thick * 0.8f, 0.05f, thick * 0.8f), 6));
        Tasks.later(37, e::discard);

        BlockState s = Blocks.STONE.getDefaultState();
        w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, s), base.x, base.y + 0.2, base.z, 14, 0.3, 0.1, 0.3, 0.15);
        w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, base.x, base.y + 0.3, base.z, 6, 0.3, 0.2, 0.3, 0.1);
        w.playSound(null, BlockPos.ofFloored(base), SoundEvents.BLOCK_STONE_PLACE, SoundCategory.PLAYERS, 1.4f, 0.6f);
        w.playSound(null, BlockPos.ofFloored(base), SoundEvents.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, SoundCategory.PLAYERS, 0.5f, 1.4f);
    }
}
