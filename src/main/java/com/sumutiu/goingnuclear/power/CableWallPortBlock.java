package com.sumutiu.goingnuclear.power;

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
 * A ground cable cased in a full block, to take a cable line through a wall without leaving a hole.
 * It conducts like any ground cable: cables, poles, machines and furnaces on either side connect to it.
 */
public class CableWallPortBlock extends PowerCableBlock implements PolymerTexturedBlock {
    private final @Nullable BlockState textured;

    public CableWallPortBlock(Properties properties) {
        super(properties);
        this.textured = Visuals.request(Visuals.model("cable_wall_port"));
    }

    @Override
    public boolean propagatesSkylightDown(BlockState state) {
        return false;
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, @Nullable PacketContext context) {
        if (this.textured != null && Visuals.hasPack(context)) return this.textured;
        return Blocks.CONCRETE.gray().defaultBlockState();
    }

    @Override
    public BlockState getPolymerBreakEventBlockState(BlockState state, PacketContext context) {
        return Blocks.CONCRETE.gray().defaultBlockState();
    }

    @Override
    public @Nullable ElementHolder createElementHolder(ServerLevel level, BlockPos pos, BlockState initialBlockState) {
        // A full block: neighbouring cables draw their arms right up to its faces.
        return null;
    }
}
