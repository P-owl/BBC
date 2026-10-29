package bus.muhyeon.duel;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

/** 바닥에 까는 연성진. 우클릭하면 등가교환 화면 */
public class AlchemyCircleBlock extends Block {
    public static final MapCodec<AlchemyCircleBlock> CODEC = createCodec(AlchemyCircleBlock::new);
    private static final VoxelShape SHAPE = Block.createCuboidShape(0, 0, 0, 16, 1, 16);

    public AlchemyCircleBlock(Settings settings) { super(settings); }

    @Override
    protected MapCodec<? extends Block> getCodec() { return CODEC; }

    @Override
    protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext ctx) { return SHAPE; }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        if (!world.isClient && player instanceof ServerPlayerEntity sp) {
            AlchemyMenu.open(sp, pos, null);
        }
        return ActionResult.SUCCESS;
    }
}
