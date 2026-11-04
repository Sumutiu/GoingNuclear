package com.sumutiu.goingnuclear.power;

import com.sumutiu.goingnuclear.model.AnchorModel;
import com.sumutiu.goingnuclear.model.BoxModel;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.attachment.ChunkAttachment;
import eu.pb4.polymer.virtualentity.api.elements.BlockDisplayElement;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * The wire a player is carrying, like a lead: it hangs from the pole or machine where it was started
 * to the player's hand and follows them around. Everyone nearby can see it. It turns red when it is
 * longer than a wire may be.
 */
public final class WirePreview extends ElementHolder {
    private static final int SEGMENTS = 16;
    private static final int MOVE_TICKS = 2;
    private static final BlockState NORMAL = Blocks.CONCRETE.black().defaultBlockState();
    private static final BlockState TOO_LONG = Blocks.CONCRETE.red().defaultBlockState();

    private final Vec3 origin;
    private final List<BlockDisplayElement> segments = new ArrayList<>();
    private boolean red;
    private boolean placed;

    private WirePreview(Vec3 origin) {
        this.origin = origin;
        for (int i = 0; i < SEGMENTS; i++) {
            BlockDisplayElement element = new BlockDisplayElement(NORMAL);
            element.setViewRange(1.0f);
            element.setTeleportDuration(0);
            element.setInterpolationDuration(MOVE_TICKS);
            // No culling box: the wire reaches far from the entity position.
            element.setDisplaySize(0.0f, 0.0f);
            this.segments.add(this.addElement(element));
        }
    }

    /**
     * Creates the carried wire, hanging from {@code origin} (the attach point it was started at) to
     * {@code hand}. The shape is set before the wire is shown, so nobody sees it unshaped.
     */
    public static WirePreview create(ServerLevel level, Vec3 origin, Vec3 hand, boolean tooLong) {
        WirePreview preview = new WirePreview(origin);
        preview.update(hand, tooLong);
        ChunkAttachment.ofTicking(preview, level, origin);
        return preview;
    }

    /**
     * Moves the free end of the wire to {@code hand} (world coordinates).
     */
    public void update(Vec3 hand, boolean tooLong) {
        if (tooLong != this.red) {
            this.red = tooLong;
            for (BlockDisplayElement element : this.segments) element.setBlockState(tooLong ? TOO_LONG : NORMAL);
        }
        double sag = WireShape.sag(this.origin, hand);
        Vector3f previous = new Vector3f();
        for (int i = 0; i < SEGMENTS; i++) {
            Vec3 point = WireShape.point(this.origin, hand, sag, (double) (i + 1) / SEGMENTS);
            Vector3f next = new Vector3f((float) (point.x - this.origin.x), (float) (point.y - this.origin.y), (float) (point.z - this.origin.z));
            BlockDisplayElement element = this.segments.get(i);
            if (BoxModel.placeBeam(element, previous, next, AnchorModel.WIRE_THICKNESS, AnchorModel.WIRE_THICKNESS * 0.5f) && this.placed) {
                // Slide smoothly to the new shape (the first placement appears directly).
                element.startInterpolation();
            }
            previous = next;
        }
        this.placed = true;
    }

    public void remove() {
        this.destroy();
    }
}
