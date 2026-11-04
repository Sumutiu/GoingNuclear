package com.sumutiu.goingnuclear.fluid;

import com.sumutiu.goingnuclear.machine.FluidPipeBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Per-level cache of pipe networks. Networks are built lazily and thrown away whenever any pipe changes.
 */
public final class PipeNetworks {
    private static final int MAX_PIPES = 8192;
    private static final Map<ServerLevel, PipeNetworks> BY_LEVEL = new WeakHashMap<>();

    private final ServerLevel level;
    private final Map<BlockPos, PipeNetwork> byPos = new HashMap<>();
    private final Set<PipeNetwork> networks = new HashSet<>();
    private int ticks;

    private PipeNetworks(ServerLevel level) {
        this.level = level;
    }

    public static PipeNetworks of(ServerLevel level) {
        return BY_LEVEL.computeIfAbsent(level, PipeNetworks::new);
    }

    public static void invalidate(ServerLevel level) {
        PipeNetworks networks = BY_LEVEL.get(level);
        if (networks != null) networks.invalidate();
    }

    public static void tickAll(ServerLevel level) {
        PipeNetworks networks = BY_LEVEL.get(level);
        if (networks != null) networks.tick();
        FluidAccess.tick(level);
    }

    public static void clearAll() {
        BY_LEVEL.clear();
        FluidAccess.clearAll();
    }

    public void invalidate() {
        for (PipeNetwork network : this.networks) network.writeBack(this.level);
        this.networks.clear();
        this.byPos.clear();
    }

    private void tick() {
        if (++this.ticks % 20 == 0) {
            for (PipeNetwork network : this.networks) network.writeBack(this.level);
        }
    }

    public @Nullable PipeNetwork networkAt(BlockPos pos) {
        PipeNetwork network = this.byPos.get(pos);
        if (network != null) return network;
        if (!this.level.isLoaded(pos)) return null;
        BlockState state = this.level.getBlockState(pos);
        if (!(state.getBlock() instanceof FluidPipeBlock)) return null;
        return this.build(pos.immutable());
    }

    private PipeNetwork build(BlockPos start) {
        Set<BlockPos> pipes = new HashSet<>();
        List<PipeNetwork.Boundary> boundary = PipeNetwork.newBoundaryList();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        pipes.add(start);
        while (!queue.isEmpty() && pipes.size() < MAX_PIPES) {
            BlockPos pos = queue.poll();
            BlockState state = this.level.getBlockState(pos);
            if (!(state.getBlock() instanceof FluidPipeBlock)) continue;
            for (Direction dir : Direction.values()) {
                if (!state.getValue(FluidPipeBlock.connection(dir))) continue;
                BlockPos next = pos.relative(dir);
                if (!this.level.isLoaded(next)) continue;
                BlockState nextState = this.level.getBlockState(next);
                if (nextState.getBlock() instanceof FluidPipeBlock) {
                    if (nextState.getValue(FluidPipeBlock.connection(dir.getOpposite())) && pipes.add(next)) {
                        queue.add(next);
                    }
                } else {
                    boundary.add(new PipeNetwork.Boundary(pos, dir));
                }
            }
        }
        PipeNetwork network = new PipeNetwork(this.level, pipes, boundary);
        network.load(this.level);
        this.networks.add(network);
        for (BlockPos pos : pipes) this.byPos.put(pos, network);
        return network;
    }
}
