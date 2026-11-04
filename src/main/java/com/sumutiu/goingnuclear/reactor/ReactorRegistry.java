package com.sumutiu.goingnuclear.reactor;

import com.sumutiu.goingnuclear.config.NuclearConfig;
import com.sumutiu.goingnuclear.fluid.FluidHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Per-level index of reactor controllers and the boxes they occupy. Used to find the reactor that a
 * casing/port block belongs to, and to tell controllers when their structure changed.
 */
public final class ReactorRegistry {
    private static final Map<ServerLevel, Map<BlockPos, Bounds>> BY_LEVEL = new WeakHashMap<>();
    private static final Bounds UNFORMED = new Bounds(BlockPos.ZERO, BlockPos.ZERO, false);

    private ReactorRegistry() {
    }

    public record Bounds(BlockPos min, BlockPos max, boolean formed) {
        public boolean contains(BlockPos pos, int margin) {
            return pos.getX() >= this.min.getX() - margin && pos.getX() <= this.max.getX() + margin
                    && pos.getY() >= this.min.getY() - margin && pos.getY() <= this.max.getY() + margin
                    && pos.getZ() >= this.min.getZ() - margin && pos.getZ() <= this.max.getZ() + margin;
        }
    }

    private static Map<BlockPos, Bounds> map(ServerLevel level) {
        return BY_LEVEL.computeIfAbsent(level, _ -> new HashMap<>());
    }

    public static void track(ServerLevel level, BlockPos controller) {
        map(level).putIfAbsent(controller.immutable(), UNFORMED);
    }

    public static void untrack(ServerLevel level, BlockPos controller) {
        map(level).remove(controller);
    }

    public static void setFormed(ServerLevel level, BlockPos controller, @Nullable BlockPos min, @Nullable BlockPos max) {
        if (min == null || max == null) {
            map(level).put(controller.immutable(), UNFORMED);
        } else {
            map(level).put(controller.immutable(), new Bounds(min.immutable(), max.immutable(), true));
        }
    }

    public static void clearAll() {
        BY_LEVEL.clear();
    }

    /**
     * All formed reactors in loaded chunks of this level.
     */
    public static List<ReactorControllerBlockEntity> formedControllers(ServerLevel level) {
        Map<BlockPos, Bounds> map = BY_LEVEL.get(level);
        List<ReactorControllerBlockEntity> out = new ArrayList<>();
        if (map == null) return out;
        for (Map.Entry<BlockPos, Bounds> entry : map.entrySet()) {
            if (!entry.getValue().formed() || !level.isLoaded(entry.getKey())) continue;
            if (level.getBlockEntity(entry.getKey()) instanceof ReactorControllerBlockEntity controller && controller.isFormed()) out.add(controller);
        }
        return out;
    }

    public static @Nullable Bounds bounds(ServerLevel level, BlockPos controller) {
        Map<BlockPos, Bounds> map = BY_LEVEL.get(level);
        return map != null ? map.get(controller) : null;
    }

    public static @Nullable ReactorControllerBlockEntity controllerAt(ServerLevel level, BlockPos pos) {
        Map<BlockPos, Bounds> map = BY_LEVEL.get(level);
        if (map == null) return null;
        for (Map.Entry<BlockPos, Bounds> entry : map.entrySet()) {
            Bounds bounds = entry.getValue();
            if (!bounds.formed() || !bounds.contains(pos, 0)) continue;
            if (level.isLoaded(entry.getKey()) && level.getBlockEntity(entry.getKey()) instanceof ReactorControllerBlockEntity controller && controller.isFormed()) {
                return controller;
            }
        }
        return null;
    }

    public static @Nullable FluidHandler portHandler(ServerLevel level, BlockPos port) {
        ReactorControllerBlockEntity controller = controllerAt(level, port);
        return controller != null ? controller.portHandler(port) : null;
    }

    /**
     * A reactor block was placed, removed or changed at {@code pos}: wake up nearby controllers.
     */
    public static void notifyChange(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel)) return;
        Map<BlockPos, Bounds> map = BY_LEVEL.get(serverLevel);
        if (map == null) return;
        int reach = NuclearConfig.get().maxReactorSize;
        List<BlockPos> wake = new ArrayList<>();
        for (Map.Entry<BlockPos, Bounds> entry : map.entrySet()) {
            Bounds bounds = entry.getValue();
            boolean near = bounds.formed() ? bounds.contains(pos, 1) : entry.getKey().closerThan(pos, reach);
            if (near) wake.add(entry.getKey());
        }
        for (BlockPos controller : wake) {
            if (serverLevel.isLoaded(controller) && serverLevel.getBlockEntity(controller) instanceof ReactorControllerBlockEntity be) {
                be.markStructureDirty();
            }
        }
    }
}
