package com.sumutiu.goingnuclear.block;

import com.sumutiu.goingnuclear.util.Visuals;
import eu.pb4.polymer.blocks.api.PolymerBlockModel;
import eu.pb4.polymer.blocks.api.PolymerTexturedBlock;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * A full-cube block that shows a custom textured model (through Polymer's note-block states) to players
 * with the server resource pack, and a vanilla look-alike block to everyone else.
 */
public class TexturedBlock extends Block implements PolymerTexturedBlock {
    private final Map<BlockState, BlockState> textured = new IdentityHashMap<>();
    private final Function<BlockState, BlockState> fallback;

    public TexturedBlock(Properties properties, Function<BlockState, PolymerBlockModel> models, Function<BlockState, BlockState> fallback) {
        super(properties);
        this.fallback = fallback;
        for (BlockState state : this.getStateDefinition().getPossibleStates()) {
            BlockState requested = Visuals.request(models.apply(state));
            if (requested != null) this.textured.put(state, requested);
        }
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, @Nullable PacketContext context) {
        BlockState custom = this.textured.get(state);
        if (custom != null && Visuals.hasPack(context)) return custom;
        return this.fallback.apply(state);
    }
}
