package com.sumutiu.goingnuclear.power;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Per-level electric grid. Tracks every loaded electric block entity, rebuilds networks when the
 * topology changes (cables, poles, wires, machines, furnaces) and solves every network each tick.
 * <p>
 * Networks are traced through terminals (see {@link ElectricNode#terminals}): a face of a node only links to
 * its neighbour as part of the terminal that face belongs to, so a battery's back (input) and its other faces
 * (output) can end up on different networks.
 */
public final class PowerGrid {
    private static final int MAX_NETWORK_BLOCKS = 16384;
    private static final Map<ServerLevel, PowerGrid> BY_LEVEL = new WeakHashMap<>();

    private final ServerLevel level;
    private final Set<BlockPos> registered = new LinkedHashSet<>();
    private final Set<BlockPos> nodes = new LinkedHashSet<>();
    private final List<PowerNetwork> networks = new ArrayList<>();
    private final Map<BlockPos, PowerNetwork> byPos = new HashMap<>();
    private final Map<Terminal, PowerNetwork> byTerminal = new HashMap<>();
    private boolean dirty = true;

    private PowerGrid(ServerLevel level) {
        this.level = level;
    }

    public static PowerGrid of(ServerLevel level) {
        return BY_LEVEL.computeIfAbsent(level, PowerGrid::new);
    }

    public static void clearAll() {
        BY_LEVEL.clear();
    }

    public void markDirty() {
        this.dirty = true;
    }

    public void register(BlockPos pos) {
        this.registered.add(pos.immutable());
        this.dirty = true;
    }

    public void unregister(BlockPos pos) {
        this.registered.remove(pos);
        this.dirty = true;
    }

    /**
     * Network at a cable, furnace or node (for a node with several terminals, the one of terminal 0).
     */
    public @Nullable PowerNetwork networkAt(BlockPos pos) {
        if (this.dirty) this.rebuild();
        return this.byPos.get(pos);
    }

    /**
     * Network that one terminal of a node is on.
     */
    public @Nullable PowerNetwork networkAt(BlockPos pos, int terminal) {
        if (this.dirty) this.rebuild();
        return this.byTerminal.get(new Terminal(pos, terminal));
    }

    public static void tick(ServerLevel level) {
        PowerGrid grid = BY_LEVEL.get(level);
        if (grid == null) return;
        if (grid.dirty) grid.rebuild();
        for (PowerNetwork network : grid.networks) network.solve(level);
    }

    public static boolean isConductor(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) return false;
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof PowerCableBlock) return true;
        return state.getBlock() instanceof ElectricConnectable && level.getBlockEntity(pos) instanceof ElectricNode;
    }

    private void rebuild() {
        this.dirty = false;
        this.networks.clear();
        this.byPos.clear();
        this.byTerminal.clear();
        this.nodes.clear();

        List<BlockPos> stale = new ArrayList<>();
        for (BlockPos pos : this.registered) {
            if (this.level.isLoaded(pos) && this.level.getBlockEntity(pos) instanceof ElectricNode) {
                this.nodes.add(pos);
            } else {
                stale.add(pos);
            }
        }
        stale.forEach(this.registered::remove);

        Set<Terminal> visited = new HashSet<>();
        for (BlockPos start : this.nodes) {
            int terminals = this.level.getBlockEntity(start) instanceof ElectricNode node ? node.terminals() : 1;
            for (int index = 0; index < terminals; index++) {
                Terminal first = new Terminal(start, index);
                if (visited.add(first)) this.networks.add(this.trace(first, visited));
            }
        }
    }

    /**
     * Collects everything connected to {@code start} into a new network.
     */
    private PowerNetwork trace(Terminal start, Set<Terminal> visited) {
        PowerNetwork network = new PowerNetwork();
        Set<BlockPos> furnaceSet = new HashSet<>();
        ArrayDeque<Terminal> queue = new ArrayDeque<>();
        queue.add(start);
        int count = 0;
        while (!queue.isEmpty() && count++ < MAX_NETWORK_BLOCKS) {
            Terminal at = queue.poll();
            BlockPos pos = at.pos();
            BlockEntity be = this.level.getBlockEntity(pos);
            ElectricNode node = be instanceof ElectricNode electric ? electric : null;
            if (node != null) {
                network.members.add(new PowerNetwork.Member(pos, at.index()));
            } else {
                network.cables.add(pos);
            }
            this.byTerminal.put(at, network);
            if (at.index() == 0) this.byPos.put(pos, network);

            for (Direction dir : Direction.values()) {
                // A face that belongs to another terminal of this node leads to another network.
                if (node != null && node.terminalAt(dir) != at.index()) continue;
                BlockPos next = pos.relative(dir);
                if (isConductor(this.level, next)) {
                    int index = this.terminalAt(next, dir.getOpposite());
                    if (index < 0) continue;
                    Terminal neighbour = new Terminal(next, index);
                    if (visited.add(neighbour)) queue.add(neighbour);
                } else if (this.level.isLoaded(next) && this.level.getBlockEntity(next) instanceof AbstractFurnaceBlockEntity) {
                    if (furnaceSet.add(next)) network.furnaces.add(next);
                }
            }

            if (be instanceof WireAnchor anchor && (node == null || node.wireTerminal() == at.index())) {
                List<BlockPos> broken = new ArrayList<>();
                for (BlockPos target : anchor.wires().targets()) {
                    if (!this.level.isLoaded(target)) continue;
                    BlockEntity targetBe = this.level.getBlockEntity(target);
                    if (!(targetBe instanceof WireAnchor)) {
                        broken.add(target);
                        continue;
                    }
                    int index = targetBe instanceof ElectricNode targetNode ? targetNode.wireTerminal() : 0;
                    Terminal other = new Terminal(target, index);
                    if (visited.add(other)) queue.add(other);
                }
                for (BlockPos target : broken) anchor.wires().remove(target);
                if (!broken.isEmpty()) be.setChanged();
            }
        }
        for (BlockPos furnace : network.furnaces) this.byPos.put(furnace, network);
        return network;
    }

    private int terminalAt(BlockPos pos, Direction face) {
        return this.level.getBlockEntity(pos) instanceof ElectricNode node ? node.terminalAt(face) : 0;
    }

    private record Terminal(BlockPos pos, int index) {
    }
}
