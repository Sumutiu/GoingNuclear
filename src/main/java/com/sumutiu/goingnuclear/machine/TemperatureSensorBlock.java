package com.sumutiu.goingnuclear.machine;

import com.sumutiu.goingnuclear.gui.TemperatureSensorGui;
import com.sumutiu.goingnuclear.util.Visuals;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * Reads the temperature of an adjacent reactor (any casing, port, glass or controller block),
 * heat exchanger or turbine and outputs a redstone signal. Right-click to configure.
 */
public class TemperatureSensorBlock extends MachineBlock {
    public static final IntegerProperty POWER = BlockStateProperties.POWER;

    public TemperatureSensorBlock(Properties properties) {
        super(properties,
                state -> Visuals.model(state.getValue(POWER) > 0 ? "temperature_sensor_on" : "temperature_sensor"),
                _ -> Blocks.TARGET.defaultBlockState());
        this.registerDefaultState(this.stateDefinition.any().setValue(POWER, 0));
    }

    @Override
    public void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(POWER);
    }

    @Override
    public boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    public int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return state.getValue(POWER);
    }

    @Override
    public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof TemperatureSensorBlockEntity sensor) {
            new TemperatureSensorGui(serverPlayer, sensor).open();
        }
        return InteractionResult.SUCCESS_SERVER;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TemperatureSensorBlockEntity(pos, state);
    }
}
