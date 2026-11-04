package com.sumutiu.goingnuclear.fluid;

import com.sumutiu.goingnuclear.config.NuclearConfig;
import com.sumutiu.goingnuclear.machine.ExpansionTankBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Per-level cache of expansion tank groups (see {@link TankGroup}). Groups are built when first needed and
 * thrown away whenever a tank is placed or broken.
 */
public final class TankGroups {
    private static final int MAX_TANKS = 4096;
    private static final Map<ServerLevel, TankGroups> BY_LEVEL = new WeakHashMap<>();

    private final ServerLevel level;
    private final Map<BlockPos, TankGroup> byPos = new HashMap<>();
    private final Set<TankGroup> groups = new HashSet<>();
    private int ticks;

    private TankGroups(ServerLevel level) {
        this.level = level;
    }

    public static TankGroups of(ServerLevel level) {
        return BY_LEVEL.computeIfAbsent(level, TankGroups::new);
    }

    public static void invalidate(ServerLevel level) {
        TankGroups groups = BY_LEVEL.get(level);
        if (groups != null) groups.invalidate();
    }

    public static void tickAll(ServerLevel level) {
        TankGroups groups = BY_LEVEL.get(level);
        if (groups != null) groups.tick();
    }

    public static void clearAll() {
        BY_LEVEL.clear();
    }

    private void invalidate() {
        for (TankGroup group : this.groups) group.writeBack(this.level);
        this.groups.clear();
        this.byPos.clear();
    }

    private void tick() {
        if (++this.ticks % 20 == 0) {
            for (TankGroup group : this.groups) group.writeBack(this.level);
        }
    }

    public @Nullable TankGroup groupAt(BlockPos pos) {
        TankGroup group = this.byPos.get(pos);
        if (group != null) return group;
        if (!this.level.isLoaded(pos) || !(this.level.getBlockState(pos).getBlock() instanceof ExpansionTankBlock)) return null;
        return this.build(pos.immutable());
    }

    /**
     * Collects every tank joined to {@code start} through any face.
     */
    private TankGroup build(BlockPos start) {
        List<BlockPos> members = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty() && members.size() < MAX_TANKS) {
            BlockPos pos = queue.poll();
            members.add(pos);
            for (Direction dir : Direction.values()) {
                BlockPos next = pos.relative(dir);
                if (seen.contains(next) || !this.level.isLoaded(next)) continue;
                if (this.level.getBlockState(next).getBlock() instanceof ExpansionTankBlock) {
                    seen.add(next);
                    queue.add(next);
                }
            }
        }
        TankGroup group = new TankGroup(members, NuclearConfig.get().expansionTankCapacity);
        group.load(this.level);
        this.groups.add(group);
        for (BlockPos pos : group.members()) this.byPos.put(pos, group);
        return group;
    }
}
