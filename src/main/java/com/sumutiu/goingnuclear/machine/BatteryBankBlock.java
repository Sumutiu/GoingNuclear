package com.sumutiu.goingnuclear.machine;

import com.sumutiu.goingnuclear.power.ElectricConnectable;
import com.sumutiu.goingnuclear.util.Visuals;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import org.jspecify.annotations.Nullable;

/**
 * Stores electricity. It takes power in only through its back (the face with the yellow power-in socket,
 * pointing away from you when you place it) and gives it out through its left, right, top and bottom (the red
 * power-out ports) and its overhead wires; when the grid at its back runs short it gives power back through
 * the back too. The front only shows the charge. Batteries touching on their red ports form one bank and
 * share their charge. Comparators read its charge.
 */
public class BatteryBankBlock extends MachineBlock implements ElectricConnectable {
    public static final IntegerProperty CHARGE = IntegerProperty.create("charge", 0, 4);
    /**
     * The front; the back (power in) is the opposite face.
     */
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

    public BatteryBankBlock(Properties properties) {
        super(properties,
                state -> Visuals.facing("battery_bank_" + state.getValue(CHARGE), state.getValue(FACING)),
                _ -> Blocks.LODESTONE.defaultBlockState());
        this.registerDefaultState(this.stateDefinition.any().setValue(CHARGE, 0).setValue(FACING, Direction.NORTH));
    }

    @Override
    public void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(CHARGE, FACING);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext ctx) {
        // Front towards you, power in at the back.
        return this.defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    /**
     * Power on every face but the front (the charge gauge).
     */
    @Override
    public boolean connectsPower(BlockState state, Direction side) {
        return side != state.getValue(FACING);
    }

    @Override
    public boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    public int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos, Direction direction) {
        if (level.getBlockEntity(pos) instanceof BatteryBankBlockEntity battery && battery.capacity() > 0.0) {
            return (int) Math.round(battery.stored() / battery.capacity() * 15.0);
        }
        return 0;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BatteryBankBlockEntity(pos, state);
    }
}
