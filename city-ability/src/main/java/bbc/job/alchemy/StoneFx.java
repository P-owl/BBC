package bbc.job.alchemy;

import bbc.core.Tasks;
import bbc.core.ButtonMenu;

import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

import java.util.concurrent.ThreadLocalRandom;

/** 현자의 돌 사용 연출: 몸 주위로 튀는 빨간 전기 스파크 */
public final class StoneFx {
    private static final DustParticleEffect RED = new DustParticleEffect(new Vector3f(1.0f, 0.08f, 0.08f), 0.7f);
    private static final DustParticleEffect CORE = new DustParticleEffect(new Vector3f(1.0f, 0.55f, 0.5f), 0.45f);
    private static final DustParticleEffect DARK = new DustParticleEffect(new Vector3f(0.55f, 0.0f, 0.05f), 1.0f);

    private StoneFx() {}

    /** 선택창을 열 때: 손 주변에서 짧게 파직 */
    public static void small(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        Vec3d hand = handPos(p);
        for (int i = 0; i < 3; i++) arc(w, hand, 0.7, 5);
        w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_RESPAWN_ANCHOR_CHARGE, SoundCategory.PLAYERS, 0.5f, 1.8f);
    }

    /** 효과 발동: 1초 동안 몸 전체에서 스파크가 튀고 마지막에 붉은 파동 */
    public static void big(ServerPlayerEntity p) {
        p.getServerWorld().playSound(null, p.getBlockPos(), SoundEvents.BLOCK_RESPAWN_ANCHOR_CHARGE, SoundCategory.PLAYERS, 1.0f, 1.4f);
        for (int t = 0; t < 20; t += 2) {
            final int tick = t;
            Tasks.later(t, () -> {
                if (p.isRemoved()) return;
                ServerWorld w = p.getServerWorld();
                Vec3d c = p.getPos().add(0, 1.0, 0);
                ThreadLocalRandom r = ThreadLocalRandom.current();
                for (int i = 0; i < 4; i++) {
                    Vec3d from = c.add((r.nextDouble() - 0.5) * 0.8, (r.nextDouble() - 0.5) * 1.6, (r.nextDouble() - 0.5) * 0.8);
                    arc(w, from, 1.4, 8);
                }
                w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y, c.z, 6, 0.5, 0.8, 0.5, 0.3);
                if (tick % 6 == 0) {
                    w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_BEE_STING, SoundCategory.PLAYERS, 0.35f, 0.5f + r.nextFloat() * 0.4f);
                }
            });
        }
        Tasks.later(20, () -> {
            if (p.isRemoved()) return;
            ServerWorld w = p.getServerWorld();
            Vec3d base = p.getPos().add(0, 0.1, 0);
            for (int i = 0; i < 48; i++) {
                double a = Math.PI * 2 * i / 48;
                w.spawnParticles(DARK, base.x + Math.cos(a) * 1.6, base.y, base.z + Math.sin(a) * 1.6, 1, 0, 0, 0, 0);
                w.spawnParticles(RED, base.x + Math.cos(a) * 1.1, base.y + 0.05, base.z + Math.sin(a) * 1.1, 1, 0, 0, 0, 0);
            }
            w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 1.0f, 0.8f);
        });
    }

    private static final DustParticleEffect BLUE = new DustParticleEffect(new Vector3f(0.35f, 0.75f, 1.0f), 0.8f);

    /** 연성진: 바닥에 푸른 원이 그려지고 점점 빨라지다가 번쩍 */
    public static void circleAt(ServerWorld w, Vec3d c0, int ticks) {
        w.playSound(null, BlockPos.ofFloored(c0), SoundEvents.BLOCK_RESPAWN_ANCHOR_CHARGE, SoundCategory.PLAYERS, 1.0f, 0.8f);
        Vec3d c = c0.add(0, 0.05, 0);
        for (int t = 0; t < ticks; t += 2) {
            final int tick = t;
            Tasks.later(t, () -> {
                double radius = 2.0;
                int n = 48;
                for (int i = 0; i < n; i++) {
                    double a = Math.PI * 2 * i / n;
                    w.spawnParticles(BLUE, c.x + Math.cos(a) * radius, c.y, c.z + Math.sin(a) * radius, 1, 0, 0, 0, 0);
                    if (i % 2 == 0) w.spawnParticles(BLUE, c.x + Math.cos(a) * 1.4, c.y, c.z + Math.sin(a) * 1.4, 1, 0, 0, 0, 0);
                }
                double prog = (double) tick / ticks;
                double a0 = prog * prog * Math.PI * 6;
                for (int k = 0; k < 3; k++) {
                    double a1 = a0 + k * Math.PI * 2 / 3, a2 = a0 + (k + 1) * Math.PI * 2 / 3;
                    Vec3d p1 = c.add(Math.cos(a1) * radius, 0, Math.sin(a1) * radius);
                    Vec3d p2 = c.add(Math.cos(a2) * radius, 0, Math.sin(a2) * radius);
                    for (int s = 0; s <= 10; s++) {
                        Vec3d q = p1.lerp(p2, s / 10.0);
                        w.spawnParticles(BLUE, q.x, q.y, q.z, 1, 0, 0, 0, 0);
                    }
                }
                w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y + 0.3, c.z, 4 + (int) (prog * 16), 1.0, 0.2, 1.0, 0.3);
                if (tick % 10 == 0) {
                    w.playSound(null, BlockPos.ofFloored(c), SoundEvents.BLOCK_BEACON_AMBIENT, SoundCategory.PLAYERS, 1.0f, 1.0f + (float) prog);
                }
            });
        }
        Tasks.later(ticks, () -> {
            w.spawnParticles(ParticleTypes.FLASH, c.x, c.y + 0.8, c.z, 1, 0, 0, 0, 0);
            w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y + 0.8, c.z, 120, 0.8, 1.0, 0.8, 0.8);
            w.playSound(null, BlockPos.ofFloored(c), SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.PLAYERS, 0.8f, 1.4f);
        });
    }

    /** 지그재그 번개 한 줄기 */
    private static void arc(ServerWorld w, Vec3d start, double length, int segments) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Vec3d dir = new Vec3d(r.nextGaussian(), r.nextGaussian() * 0.6, r.nextGaussian()).normalize();
        double step = length / segments;
        Vec3d cur = start;
        for (int s = 0; s < segments; s++) {
            Vec3d jitter = new Vec3d(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).multiply(step * 0.6);
            Vec3d next = cur.add(dir.multiply(step)).add(jitter);
            // 선분을 촘촘히 찍어서 끊김 없는 번개처럼
            for (int k = 0; k <= 3; k++) {
                Vec3d q = cur.lerp(next, k / 3.0);
                w.spawnParticles(k == 0 ? CORE : RED, q.x, q.y, q.z, 1, 0, 0, 0, 0);
            }
            cur = next;
        }
    }

    private static Vec3d handPos(ServerPlayerEntity p) {
        Vec3d look = p.getRotationVec(1.0f);
        Vec3d right = look.crossProduct(new Vec3d(0, 1, 0)).normalize();
        return p.getEyePos().add(look.multiply(0.5)).add(right.multiply(0.35)).add(0, -0.4, 0);
    }
}
