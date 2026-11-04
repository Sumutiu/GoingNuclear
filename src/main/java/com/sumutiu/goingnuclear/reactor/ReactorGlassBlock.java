package com.sumutiu.goingnuclear.reactor;

import eu.pb4.polymer.core.api.block.PolymerBlock;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Reinforced reactor glass: lets you look inside the core and see the fuel, the control rods and the glow
 * of a running reactor. Shown as clear glass to every client, so light (and your view) gets through untinted.
 */
public class ReactorGlassBlock extends Block implements PolymerBlock, ReactorPart {
    public ReactorGlassBlock(Properties properties) {
        super(properties);
    }

    @Override
    public boolean isStructural() {
        return true;
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, @Nullable PacketContext context) {
        return Blocks.GLASS.defaultBlockState();
    }

    @Override
    public boolean propagatesSkylightDown(BlockState state) {
        return true;
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean notify) {
        super.onPlace(state, level, pos, oldState, notify);
        ReactorRegistry.notifyChange(level, pos);
    }

    @Override
    public void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean moved) {
        super.affectNeighborsAfterRemoval(state, level, pos, moved);
        ReactorRegistry.notifyChange(level, pos);
    }
}
