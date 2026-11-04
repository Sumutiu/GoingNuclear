package com.sumutiu.goingnuclear.model;

import com.sumutiu.goingnuclear.power.PowerPoleBlock;
import com.sumutiu.goingnuclear.power.WireAnchor;
import com.sumutiu.goingnuclear.power.WireShape;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Box model that also draws the overhead wires of a {@link WireAnchor} block entity, hanging between
 * their two ends like a lead. Each wire is drawn by the end with the "smaller" block position, so it is
 * only rendered once. Machines with wires get a small knot where the wires are tied on.
 */
public class AnchorModel extends BoxModel {
    public static final float WIRE_THICKNESS = 0.05f;
    private int seenVersion = -1;
    private int ticks;

    public AnchorModel() {
    }

    @Override
    protected void onTick() {
        super.onTick();
        if (this.ticks++ % 10 != 0) return;
        ServerLevel level = this.level();
        if (level == null) return;
        BlockPos pos = this.blockPos();
        if (!(level.getBlockEntity(pos) instanceof WireAnchor anchor)) return;
        int version = anchor.wires().version();
        if (version == this.seenVersion) return;
        this.seenVersion = version;
        this.rebuildWires(level, pos, anchor);
    }

    private void rebuildWires(ServerLevel level, BlockPos pos, WireAnchor anchor) {
        this.clearExtraParts();
        BlockState state = this.blockState();
        if (anchor.wires().size() > 0 && !(state.getBlock() instanceof PowerPoleBlock)) {
            // Knot on top of the machine (poles have their own insulator).
            BlockState knot = Blocks.CONCRETE.black().defaultBlockState();
            this.extraBox(knot, 0.42, 1.0, 0.42, 0.58, 1.08, 0.58);
        }

        BlockState wireLook = Blocks.CONCRETE.black().defaultBlockState();
        Vec3 center = Vec3.atCenterOf(pos);
        Vec3 start = WireAnchor.attachPoint(state, pos);
        for (BlockPos target : anchor.wires().targets()) {
            if (pos.compareTo(target) > 0) continue;
            Vec3 end = level.isLoaded(target)
                    ? WireAnchor.attachPoint(level.getBlockState(target), target)
                    : new Vec3(target.getX() + 0.5, target.getY() + 0.93, target.getZ() + 0.5);
            double sag = WireShape.sag(start, end);
            int segments = WireShape.segments(start.distanceTo(end) + sag);
            Vector3f previous = null;
            for (int i = 0; i <= segments; i++) {
                Vec3 point = WireShape.point(start, end, sag, (double) i / segments);
                Vector3f relative = new Vector3f((float) (point.x - center.x), (float) (point.y - center.y), (float) (point.z - center.z));
                if (previous != null) this.beam(wireLook, previous, relative, WIRE_THICKNESS, 1.0f);
                previous = relative;
            }
        }
    }
}
