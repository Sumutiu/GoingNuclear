package com.sumutiu.goingnuclear.machine;

import com.sumutiu.goingnuclear.util.Visuals;
import eu.pb4.polymer.blocks.api.PolymerTexturedBlock;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * A fluid pipe cased in a full block, to take a pipe line through a wall without leaving a hole.
 * It is part of the pipe network like any pipe (same capacity, Pipe Wrench toggles its sides), and
 * pipes or machines on either side of the wall connect to it.
 */
public class PipeWallPortBlock extends FluidPipeBlock implements PolymerTexturedBlock {
    private final @Nullable BlockState textured;

    public PipeWallPortBlock(Properties properties) {
        super(properties);
        this.textured = Visuals.request(Visuals.model("pipe_wall_port"));
    }

    @Override
    public boolean propagatesSkylightDown(BlockState state) {
        return false;
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, @Nullable PacketContext context) {
        if (this.textured != null && Visuals.hasPack(context)) return this.textured;
        return Blocks.COPPER_BLOCK.weathering().unaffected().defaultBlockState();
    }

    @Override
    public @Nullable ElementHolder createElementHolder(ServerLevel level, BlockPos pos, BlockState initialBlockState) {
        // A full block: neighbouring pipes draw their arms right up to its faces.
        return null;
    }
}
