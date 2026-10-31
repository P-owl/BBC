package bbc.job.alchemy;

import bbc.core.Tasks;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtFloat;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** 연성 기술 연출 도우미 (무현버스 연금술사 기술에서 옮김): 땅 높이, 솟는 돌가시(블록 디스플레이) */
public final class AlchemyFx {
    private AlchemyFx() {}

    /** 발밑에서 위아래 몇 칸 안의 땅 높이 (없으면 null) */
    public static Double ground(ServerWorld w, Vec3d at) {
        BlockPos bp = BlockPos.ofFloored(at.x, at.y + 1, at.z);
        for (int i = 0; i < 6; i++) {
            BlockPos below = bp.down();
            if (!w.getBlockState(bp).isSolidBlock(w, bp) && w.getBlockState(below).isSolidBlock(w, below)) return (double) bp.getY();
            bp = below;
        }
        return null;
    }

    /** 뾰족한 돌기둥 (실제 블록 아님). 솟았다가 life 틱 뒤 가라앉음 */
    public static void spike(ServerWorld w, double x, double y, double z, float width, float height, int life) {
        display(w, Blocks.POINTED_DRIPSTONE.getDefaultState(), x, y, z, width, height, width, 0, life, 3);
        w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.STONE.getDefaultState()), x, y + 0.1, z, 8, 0.3, 0.1, 0.3, 0.1);
        w.playSound(null, BlockPos.ofFloored(x, y, z), SoundEvents.BLOCK_POINTED_DRIPSTONE_LAND, SoundCategory.PLAYERS, 0.8f, 0.7f);
    }

    /** 네모난 바위 기둥 (기둥 감옥 등) */
    public static void pillar(ServerWorld w, double x, double y, double z, float width, float height, int life) {
        display(w, Blocks.TUFF_BRICKS.getDefaultState(), x, y, z, width, height, width, 0, life, 3);
        w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.TUFF.getDefaultState()), x, y + 0.1, z, 10, 0.3, 0.1, 0.3, 0.1);
    }

    /** 땅속(y - 높이)에서 delay 틱 뒤 솟아올라 life 틱 동안 있다가 가라앉는 블록 디스플레이 */
    public static Entity display(ServerWorld w, BlockState state, double x, double y, double z, float sx, float sy, float sz, int delay, int life, int glide) {
        NbtCompound n = new NbtCompound();
        n.putString("id", "minecraft:block_display");
        n.put("block_state", NbtHelper.fromBlockState(state));
        NbtCompound tr = new NbtCompound();
        tr.put("left_rotation", floats(0, 0, 0, 1));
        tr.put("right_rotation", floats(0, 0, 0, 1));
        tr.put("translation", floats(-sx / 2, 0, -sz / 2));
        tr.put("scale", floats(sx, sy, sz));
        n.put("transformation", tr);
        n.putInt("teleport_duration", glide);
        float yaw = (float) (Math.random() * 360);
        Entity e = EntityType.loadEntityWithPassengers(n, w, ent -> {
            ent.refreshPositionAndAngles(x, y - sy, z, yaw, 0);
            return ent;
        });
        if (e == null) return null;
        w.spawnEntity(e);
        Tasks.later(delay + 1, () -> e.requestTeleport(x, y, z));
        Tasks.later(delay + life, () -> e.requestTeleport(x, y - sy, z));
        Tasks.later(delay + life + glide + 1, e::discard);
        return e;
    }

    private static NbtList floats(float... v) {
        NbtList l = new NbtList();
        for (float f : v) l.add(NbtFloat.of(f));
        return l;
    }
}
