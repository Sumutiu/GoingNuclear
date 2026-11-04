package com.sumutiu.goingnuclear.machine;

import com.sumutiu.goingnuclear.fluid.FluidConnectable;
import com.sumutiu.goingnuclear.power.ElectricConnectable;
import com.sumutiu.goingnuclear.util.Visuals;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import org.jspecify.annotations.Nullable;

/**
 * Steam turbine with generator, one job per face: rotor on the front, live steam in at the top, exhaust steam
 * out at the bottom (to a Condenser), and power out on the other three sides (left, right and back, the red
 * ports). Power passes through a turbine between those sides, so turbines side by side (or back to back)
 * share one cable.
 */
public class SteamTurbineBlock extends MachineBlock implements ElectricConnectable, FluidConnectable {
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

    public SteamTurbineBlock(Properties properties) {
        super(properties,
                state -> Visuals.facing("steam_turbine", state.getValue(FACING)),
                state -> Blocks.BLAST_FURNACE.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, state.getValue(FACING)));
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    public void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return this.defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    /**
     * Pipes connect to the top (live steam in) and the bottom (exhaust steam out).
     */
    @Override
    public boolean connectsFluid(BlockState state, Direction side) {
        return side.getAxis() == Direction.Axis.Y;
    }

    /**
     * Power on the left, right and back.
     */
    @Override
    public boolean connectsPower(BlockState state, Direction side) {
        return side.getAxis().isHorizontal() && side != state.getValue(FACING);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SteamTurbineBlockEntity(pos, state);
    }
}
