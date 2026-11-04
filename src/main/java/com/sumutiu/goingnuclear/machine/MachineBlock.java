package com.sumutiu.goingnuclear.machine;

import com.sumutiu.goingnuclear.block.TexturedBlock;
import com.sumutiu.goingnuclear.model.AnchorModel;
import com.sumutiu.goingnuclear.power.ElectricConnectable;
import com.sumutiu.goingnuclear.registry.ModItems;
import eu.pb4.polymer.blocks.api.PolymerBlockModel;
import eu.pb4.polymer.virtualentity.api.BlockWithElementHolder;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

import java.util.function.Function;

/**
 * Base for full-cube machines: textured look, ticking block entity, and (for electric machines)
 * an element holder that draws overhead wires attached to the machine.
 */
public abstract class MachineBlock extends TexturedBlock implements EntityBlock, BlockWithElementHolder {
    protected MachineBlock(Properties properties, Function<BlockState, PolymerBlockModel> models, Function<BlockState, BlockState> fallback) {
        super(properties, models, fallback);
    }

    @Override
    public abstract @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state);

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) return null;
        return (tickLevel, pos, tickState, be) -> {
            if (be instanceof TickingMachine machine && tickLevel instanceof ServerLevel serverLevel) {
                machine.serverTick(serverLevel, pos, tickState);
            }
        };
    }

    /**
     * Our tools (wrench, pliers, probe, copper wire) skip the block's own interaction so the tool logic runs.
     */
    @Override
    public InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (ModItems.isTool(stack)) return InteractionResult.PASS;
        return super.useItemOn(stack, state, level, pos, player, hand, hit);
    }

    @Override
    public @Nullable ElementHolder createElementHolder(ServerLevel level, BlockPos pos, BlockState initialBlockState) {
        return this instanceof ElectricConnectable ? new AnchorModel() : null;
    }

    @Override
    public boolean tickElementHolder(ServerLevel level, BlockPos pos, BlockState initialBlockState) {
        return this instanceof ElectricConnectable;
    }

    public interface TickingMachine {
        void serverTick(ServerLevel level, BlockPos pos, BlockState state);
    }
}
