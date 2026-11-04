package com.sumutiu.goingnuclear.model;

import com.sumutiu.goingnuclear.util.Visuals;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.attachment.BlockAwareAttachment;
import eu.pb4.polymer.virtualentity.api.attachment.HolderAttachment;
import eu.pb4.polymer.virtualentity.api.elements.BlockDisplayElement;
import eu.pb4.polymer.virtualentity.api.elements.DisplayElement;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import eu.pb4.polymer.virtualentity.api.elements.VirtualElement;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Brightness;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Element holder that draws a block out of block-display "boxes" made of vanilla block textures.
 * Works for every player, with or without the resource pack.
 * Coordinates passed to {@link #box} are in block space (0..1), relative to the block's corner.
 * <p>
 * Models can also add a single resource pack model ({@link #packPart}) that only players with the pack
 * receive, while the box version is limited to players without it ({@link #setPartVisibility}).
 */
public class BoxModel extends ElementHolder {
    private final List<VirtualElement> parts = new ArrayList<>();
    private final List<VirtualElement> extraParts = new ArrayList<>();
    private float viewRange = 0.6f;
    private @Nullable Predicate<ServerPlayer> partVisibility;

    public BoxModel() {
    }

    public void setPartViewRange(float viewRange) {
        this.viewRange = viewRange;
    }

    /**
     * Who receives the parts added from now on (null = everyone). Must be set before adding them,
     * because an element that was already sent is not taken back.
     */
    protected void setPartVisibility(@Nullable Predicate<ServerPlayer> visibility) {
        this.partVisibility = visibility;
    }

    /**
     * Adds a resource pack model (see {@link Visuals#modelStack}) that fills
     * the block, only for players with the pack. {@code transform} is applied around the block centre.
     */
    public ItemDisplayElement packPart(ItemStack model, Matrix4f transform) {
        ItemDisplayElement element = new ItemDisplayElement(model);
        element.setItemDisplayContext(ItemDisplayContext.NONE);
        element.setTransformation(transform);
        element.setViewRange(this.viewRange);
        element.setInterpolationDuration(0);
        element.setTeleportDuration(0);
        element.setVisibilityPredicate(Visuals.WITH_PACK);
        this.parts.add(this.addElement(element));
        return element;
    }

    protected @Nullable BlockAwareAttachment blockAware() {
        return this.getAttachment() instanceof BlockAwareAttachment aware ? aware : null;
    }

    protected BlockState blockState() {
        BlockAwareAttachment aware = this.blockAware();
        return aware != null ? aware.getBlockState() : Blocks.AIR.defaultBlockState();
    }

    protected BlockPos blockPos() {
        BlockAwareAttachment aware = this.blockAware();
        return aware != null ? aware.getBlockPos() : BlockPos.ZERO;
    }

    protected @Nullable ServerLevel level() {
        HolderAttachment attachment = this.getAttachment();
        return attachment != null ? attachment.getWorld() : null;
    }

    @Override
    public void notifyUpdate(HolderAttachment.UpdateType updateType) {
        super.notifyUpdate(updateType);
        if (updateType == BlockAwareAttachment.BLOCK_STATE_UPDATE) {
            this.rebuild(this.blockState());
        }
    }

    /**
     * Rebuild the static part of the model for the given state.
     */
    protected void rebuild(BlockState state) {
    }

    public void clearParts() {
        for (VirtualElement element : this.parts) this.removeElement(element);
        this.parts.clear();
    }

    public void clearExtraParts() {
        for (VirtualElement element : this.extraParts) this.removeElement(element);
        this.extraParts.clear();
    }

    /**
     * Adds an axis-aligned box from (x1, y1, z1) to (x2, y2, z2) in block space.
     */
    public BlockDisplayElement box(BlockState look, double x1, double y1, double z1, double x2, double y2, double z2) {
        BlockDisplayElement element = this.addElement(this.display(look, boxMatrix(x1, y1, z1, x2, y2, z2), this.viewRange));
        this.parts.add(element);
        return element;
    }

    /**
     * The light at this block, but never darker than {@code minimumBlockLight}. Keeps models visible inside
     * closed structures (like a reactor core seen through a window) without making them glow in daylight.
     */
    protected Brightness lightAtLeast(int minimumBlockLight) {
        // The level is the world, not a resource to close (IntelliJ takes every AutoCloseable for one).
        @SuppressWarnings("resource")
        ServerLevel level = this.level();
        if (level == null) return new Brightness(minimumBlockLight, 0);
        BlockPos pos = this.blockPos();
        int block = Math.max(minimumBlockLight, level.getBrightness(LightLayer.BLOCK, pos));
        return new Brightness(block, level.getBrightness(LightLayer.SKY, pos));
    }

    /**
     * Sets the brightness of every part added with {@link #box}, except the given ones.
     */
    protected void setPartsBrightness(@Nullable Brightness brightness, List<? extends VirtualElement> except) {
        for (VirtualElement element : this.parts) {
            if (element instanceof DisplayElement display && !except.contains(element)) display.setBrightness(brightness);
        }
    }

    /**
     * Removes a single element that was added with {@link #box}.
     */
    public void removePart(VirtualElement element) {
        if (this.parts.remove(element)) this.removeElement(element);
    }

    /**
     * Transformation that stretches a unit block display over the box (x1, y1, z1)-(x2, y2, z2) in block space.
     */
    public static Matrix4f boxMatrix(double x1, double y1, double z1, double x2, double y2, double z2) {
        return new Matrix4f()
                .translate((float) (x1 - 0.5), (float) (y1 - 0.5), (float) (z1 - 0.5))
                .scale((float) (x2 - x1), (float) (y2 - y1), (float) (z2 - z1));
    }

    /**
     * Adds a thin square beam between two points given relative to the block centre (world units).
     * Used for overhead wires. These go into the "extra" list so they can be rebuilt separately.
     * Each beam reaches a little past its end points so the joints of a curved wire have no gaps.
     */
    public void beam(BlockState look, Vector3f from, Vector3f to, float thickness, float viewRange) {
        BlockDisplayElement element = this.display(look, new Matrix4f(), viewRange);
        if (!placeBeam(element, from, to, thickness, thickness * 0.5f)) return;
        this.extraParts.add(this.addElement(element));
    }

    /**
     * Adds an axis-aligned box in block space (like {@link #box}) to the "extra" list.
     */
    public void extraBox(BlockState look, double x1, double y1, double z1, double x2, double y2, double z2) {
        this.extraParts.add(this.addElement(this.display(look, boxMatrix(x1, y1, z1, x2, y2, z2), this.viewRange)));
    }

    /**
     * Turns a display element into a square beam of the given thickness from {@code from} to {@code to}
     * (relative to the holder), extended by {@code overlap} at both ends. Uses separate translation,
     * rotation and scale, so interpolating between two placements moves the beam smoothly.
     *
     * @return false when the two points are the same (nothing to draw)
     */
    public static boolean placeBeam(DisplayElement element, Vector3f from, Vector3f to, float thickness, float overlap) {
        Vector3f dir = new Vector3f(to).sub(from);
        float length = dir.length();
        if (length < 1.0e-4f) return false;
        dir.div(length);
        Quaternionf rotation = new Quaternionf().rotationTo(0.0f, 0.0f, 1.0f, dir.x, dir.y, dir.z);
        // The block model spans 0..1; centre it on the beam axis and start it a little before "from".
        Vector3f offset = new Vector3f(-thickness / 2.0f, -thickness / 2.0f, -overlap).rotate(rotation);
        element.setTranslation(new Vector3f(from).add(offset));
        element.setLeftRotation(rotation);
        element.setScale(new Vector3f(thickness, thickness, length + 2.0f * overlap));
        element.setRightRotation(new Quaternionf());
        return true;
    }

    private BlockDisplayElement display(BlockState look, Matrix4f matrix, float viewRange) {
        BlockDisplayElement element = new BlockDisplayElement(look);
        if (this.partVisibility != null) element.setVisibilityPredicate(this.partVisibility);
        element.setTransformation(matrix);
        element.setViewRange(viewRange);
        element.setInterpolationDuration(0);
        element.setTeleportDuration(0);
        // No culling box: long beams extend far outside the entity position.
        element.setDisplaySize(0.0f, 0.0f);
        return element;
    }
}
