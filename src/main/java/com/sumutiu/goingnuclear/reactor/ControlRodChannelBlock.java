package com.sumutiu.goingnuclear.reactor;

import com.sumutiu.goingnuclear.model.BoxModel;
import com.sumutiu.goingnuclear.registry.ModItems;
import com.sumutiu.goingnuclear.util.PlayerItems;
import com.sumutiu.goingnuclear.util.Visuals;
import eu.pb4.polymer.core.api.block.PolymerBlock;
import eu.pb4.polymer.virtualentity.api.BlockWithElementHolder;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.elements.BlockDisplayElement;
import eu.pb4.polymer.virtualentity.api.elements.DisplayElement;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Holds one control rod. Control rods absorb neutrons from fuel channels up to two blocks away;
 * the controller sets how far they are inserted.
 * <p>
 * Drawn with block displays as an open guide frame with a cross-shaped absorber blade that slides down
 * from the top as the rods are inserted (and drops in quickly on a SCRAM).
 * <p>
 * Channels stacked on top of each other join into one tall channel with a single drive housing on top.
 * The rods go in from the top and fill the stack from top to bottom ({@link #insertionInStack}): half
 * inserted, the upper half of the stack is fully in and the lower half is out.
 */
public class ControlRodChannelBlock extends Block implements PolymerBlock, BlockWithElementHolder, ReactorPart {
    public static final BooleanProperty HAS_ROD = BooleanProperty.create("has_rod");

    public ControlRodChannelBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(HAS_ROD, false));
    }

    @Override
    public boolean isStructural() {
        return false;
    }

    /**
     * How far the rods are into one channel of a stack {@code length} channels tall, {@code fromTop} channels
     * below the top one (0 = the top one), when the stack as a whole is {@code insertion} (0..1) inserted.
     * The rods fill the stack from the top, so the top channel fills first and the bottom one last.
     */
    public static double insertionInStack(double insertion, int fromTop, int length) {
        return Math.clamp(insertion * length - fromTop, 0.0, 1.0);
    }

    @Override
    public void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(HAS_ROD);
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean notify) {
        super.onPlace(state, level, pos, oldState, notify);
        ReactorRegistry.notifyChange(level, pos);
    }

    @Override
    public void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean moved) {
        super.affectNeighborsAfterRemoval(state, level, pos, moved);
        // Only drop the rod when the block is really gone (not when just its state changed).
        if (state.getValue(HAS_ROD) && !moved && !level.getBlockState(pos).is(this)) {
            Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, new ItemStack(ModItems.CONTROL_ROD));
        }
        ReactorRegistry.notifyChange(level, pos);
    }

    @Override
    public InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (ModItems.isTool(stack)) return InteractionResult.PASS;
        if (!stack.is(ModItems.CONTROL_ROD)) return InteractionResult.TRY_WITH_EMPTY_HAND;
        if (state.getValue(HAS_ROD)) return InteractionResult.FAIL;
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        level.setBlock(pos, state.setValue(HAS_ROD, true), Block.UPDATE_ALL);
        if (!player.hasInfiniteMaterials()) stack.shrink(1);
        ReactorRegistry.notifyChange(level, pos);
        return InteractionResult.SUCCESS_SERVER;
    }

    @Override
    public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!player.isShiftKeyDown()) {
            if (player instanceof ServerPlayer serverPlayer) {
                String text = "Empty control rod channel.";
                if (state.getValue(HAS_ROD)) {
                    ReactorControllerBlockEntity controller = level instanceof ServerLevel serverLevel ? ReactorRegistry.controllerAt(serverLevel, pos) : null;
                    text = controller != null
                            ? "Control rod installed, " + Math.round(controller.insertionAt(pos) * 100.0) + "% into this channel."
                            : "Control rod installed.";
                }
                serverPlayer.sendOverlayMessage(Component.literal(text).withStyle(ChatFormatting.GRAY));
            }
            return InteractionResult.SUCCESS_SERVER;
        }
        if (!state.getValue(HAS_ROD)) return InteractionResult.PASS;
        level.setBlock(pos, state.setValue(HAS_ROD, false), Block.UPDATE_ALL);
        PlayerItems.give(player, new ItemStack(ModItems.CONTROL_ROD));
        ReactorRegistry.notifyChange(level, pos);
        return InteractionResult.SUCCESS_SERVER;
    }

    // ---------- Looks ----------

    @Override
    public boolean propagatesSkylightDown(BlockState state) {
        return true;
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, @Nullable PacketContext context) {
        return Blocks.BARRIER.defaultBlockState();
    }

    @Override
    public BlockState getPolymerBreakEventBlockState(BlockState state, PacketContext context) {
        return Blocks.IRON_BLOCK.defaultBlockState();
    }

    @Override
    public @Nullable ElementHolder createElementHolder(ServerLevel level, BlockPos pos, BlockState initialBlockState) {
        return new ControlRodModel(initialBlockState);
    }

    @Override
    public boolean tickElementHolder(ServerLevel level, BlockPos pos, BlockState initialBlockState) {
        return true;
    }

    public static final class ControlRodModel extends BoxModel {
        private static final double BLADE_MIN = 0.2;
        private static final double BLADE_MAX = 0.8;
        private static final double BLADE_HALF = 0.035;
        private static final double TOP = 0.94;
        private static final double BOTTOM_GAP = 0.02;
        private static final double MIN_BLADE = 0.02;
        private static final double HIDDEN_BLADE = 0.001;
        private static final int MAX_STACK = 64;
        private static final int UPDATE_TICKS = 5;
        private static final int MIN_LIGHT = 6;

        private final List<Segment> segments = new ArrayList<>();
        private Stack built = Stack.lone(false);
        private double shown = -1.0;
        private boolean settled;
        private int ticks;

        ControlRodModel(BlockState state) {
            this.setPartViewRange(0.5f);
            this.rebuild(state);
        }

        @Override
        protected void rebuild(BlockState state) {
            if (!(state.getBlock() instanceof ControlRodChannelBlock)) {
                this.clearParts();
                this.segments.clear();
                return;
            }
            Stack stack = this.scanStack(state);
            // Built before the holder is attached (chunk load): guess a lone channel, the first tick corrects it.
            this.build(stack != null ? stack : Stack.lone(state.getValue(HAS_ROD)));
        }

        /**
         * Frame for every channel (the drive housing only on top of a stack). The topmost channel of a stack
         * also draws the blades for the whole stack, so they slide down through all of it as one rod.
         */
        private void build(Stack stack) {
            this.clearParts();
            this.segments.clear();
            this.built = stack;
            this.settled = this.getAttachment() != null;
            double inserted = this.targetInsertion();
            double bottom = bladeBottom(inserted, stack.length());
            if (stack.top()) this.segments.addAll(Segment.runs(stack.rods()));

            // Players with the resource pack: 3D models of the frame and of each blade.
            this.packPart(Visuals.modelStack(stack.top() ? "core/control_frame" : "core/control_frame_lower"), new Matrix4f());
            for (Segment segment : this.segments) {
                segment.pack = this.packPart(Visuals.modelStack("core/control_blade"), packBladeMatrix(segment.bottom(bottom), segment.top));
            }

            // Everyone else: guide frame (four corner posts, drive housing on top) and blades from vanilla blocks.
            this.setPartVisibility(Visuals.WITHOUT_PACK);
            BlockState frame = Blocks.CONCRETE.gray().defaultBlockState();
            for (double x : new double[]{0.1, 0.82}) {
                for (double z : new double[]{0.1, 0.82}) {
                    this.box(frame, x, 0.0, z, x + 0.08, 1.0, z + 0.08);
                }
            }
            if (stack.top()) this.box(Blocks.IRON_BLOCK.defaultBlockState(), 0.18, TOP, 0.18, 0.82, 1.0, 0.82);
            BlockState absorber = Blocks.COAL_BLOCK.defaultBlockState();
            for (Segment segment : this.segments) {
                double from = segment.bottom(bottom);
                segment.bladeX = this.box(absorber, BLADE_MIN, from, 0.5 - BLADE_HALF, BLADE_MAX, segment.top, 0.5 + BLADE_HALF);
                segment.bladeZ = this.box(absorber, 0.5 - BLADE_HALF, from, BLADE_MIN, 0.5 + BLADE_HALF, segment.top, BLADE_MAX);
            }
            this.setPartVisibility(null);
            this.shown = inserted;
            this.setPartsBrightness(this.lightAtLeast(MIN_LIGHT), List.of());
        }

        @Override
        protected void onTick() {
            // Runs on the first tick too, so a stack loaded with the chunk is put together right away.
            if (++this.ticks % UPDATE_TICKS != 1) return;
            ServerLevel level = this.level();
            if (level == null) return;
            Stack stack = this.scanStack(this.blockState());
            if (stack != null && !stack.sameAs(this.built)) {
                this.build(stack);
                return;
            }
            // Never fully dark, so the rods show through reactor glass.
            if (this.ticks % (UPDATE_TICKS * 4) == 1) this.setPartsBrightness(this.lightAtLeast(MIN_LIGHT), List.of());
            if (this.segments.isEmpty()) return;
            ReactorControllerBlockEntity controller = ReactorRegistry.controllerAt(level, this.blockPos());
            double inserted = this.targetInsertion(controller);
            if (Math.abs(inserted - this.shown) * this.built.length() < 0.01) {
                this.settled = true;
                return;
            }

            // A SCRAM drops the rods in fast; normal moves follow the controller smoothly.
            int duration = !this.settled ? 0 : controller != null && controller.isScrammed() ? 3 : UPDATE_TICKS * 2;
            this.settled = true;
            double bottom = bladeBottom(inserted, this.built.length());
            for (Segment segment : this.segments) {
                double from = segment.bottom(bottom);
                if (segment.bladeX != null) {
                    moveBlade(segment.bladeX, BoxModel.boxMatrix(BLADE_MIN, from, 0.5 - BLADE_HALF, BLADE_MAX, segment.top, 0.5 + BLADE_HALF), duration);
                }
                if (segment.bladeZ != null) {
                    moveBlade(segment.bladeZ, BoxModel.boxMatrix(0.5 - BLADE_HALF, from, BLADE_MIN, 0.5 + BLADE_HALF, segment.top, BLADE_MAX), duration);
                }
                if (segment.pack != null) moveBlade(segment.pack, packBladeMatrix(from, segment.top), duration);
            }
            this.shown = inserted;
        }

        /**
         * The channels stacked with this one. Null while the model is not attached to the world yet.
         */
        private @Nullable Stack scanStack(BlockState state) {
            // The level is the world, not a resource to close (IntelliJ takes every AutoCloseable for one).
            @SuppressWarnings("resource")
            ServerLevel level = this.level();
            if (level == null) return null;
            boolean hasRod = state.getBlock() instanceof ControlRodChannelBlock && state.getValue(HAS_ROD);
            BlockPos pos = this.blockPos();
            if (level.getBlockState(pos.above()).getBlock() instanceof ControlRodChannelBlock) return new Stack(false, new boolean[]{hasRod});
            boolean[] rods = new boolean[MAX_STACK];
            int length = 0;
            rods[length++] = hasRod;
            BlockPos cursor = pos;
            while (length < MAX_STACK) {
                cursor = cursor.below();
                BlockState below = level.getBlockState(cursor);
                if (!(below.getBlock() instanceof ControlRodChannelBlock)) break;
                rods[length++] = below.getValue(HAS_ROD);
            }
            return new Stack(true, Arrays.copyOf(rods, length));
        }

        /**
         * The pack blade model fills the whole block height; squash it to the visible part (bottom..top).
         * Item models are drawn centred on the block, so the offsets are relative to the centre.
         */
        private static Matrix4f packBladeMatrix(double bottom, double top) {
            return new Matrix4f()
                    .translate(0.0f, (float) ((bottom + top) / 2.0 - 0.5), 0.0f)
                    .scale(1.0f, (float) (top - bottom), 1.0f);
        }

        private static void moveBlade(DisplayElement blade, Matrix4f matrix, int duration) {
            blade.setTransformation(matrix);
            blade.setInterpolationDuration(duration);
            blade.startInterpolation();
        }

        private double targetInsertion() {
            ServerLevel level = this.level();
            return this.targetInsertion(level != null ? ReactorRegistry.controllerAt(level, this.blockPos()) : null);
        }

        /**
         * Without a working controller the rods rest fully inserted (that is also how the simulation treats them).
         */
        private double targetInsertion(@Nullable ReactorControllerBlockEntity controller) {
            return controller != null ? controller.effectiveInsertion() : 1.0;
        }

        /**
         * Where the tip of the blades is, measured from the bottom of the top channel (lower channels of the
         * stack are below zero). Fully withdrawn the blades hide inside the drive housing; fully inserted they
         * reach the bottom of the lowest channel.
         */
        private static double bladeBottom(double inserted, int length) {
            double travel = TOP - BOTTOM_GAP + (length - 1);
            return TOP - Math.max(MIN_BLADE, inserted * travel);
        }

        /**
         * A stack of control rod channels seen from its top: whether this channel is the top one, and which
         * channels (this one first, then downwards) hold a rod. Channels lower in a stack only keep their own rod.
         */
        private record Stack(boolean top, boolean[] rods) {
            static Stack lone(boolean hasRod) {
                return new Stack(true, new boolean[]{hasRod});
            }

            int length() {
                return this.rods.length;
            }

            boolean sameAs(Stack other) {
                return this.top == other.top && Arrays.equals(this.rods, other.rods);
            }
        }

        /**
         * The blades running through one unbroken run of channels that hold rods. Heights are measured from
         * the bottom of the top channel; channel {@code i} below it spans {@code -i .. 1 - i}.
         */
        private static final class Segment {
            final double top;
            final double floor;
            @Nullable BlockDisplayElement bladeX;
            @Nullable BlockDisplayElement bladeZ;
            @Nullable ItemDisplayElement pack;

            Segment(double top, double floor) {
                this.top = top;
                this.floor = floor;
            }

            /**
             * Bottom of the blades in this run for the given blade tip. A run the blades have not reached yet
             * keeps a hidden sliver at its top, so the blades grow down out of it when they arrive.
             */
            double bottom(double tip) {
                return Math.clamp(tip, this.floor, this.top - HIDDEN_BLADE);
            }

            static List<Segment> runs(boolean[] rods) {
                List<Segment> runs = new ArrayList<>();
                int i = 0;
                while (i < rods.length) {
                    if (!rods[i]) {
                        i++;
                        continue;
                    }
                    int first = i;
                    while (i + 1 < rods.length && rods[i + 1]) i++;
                    runs.add(new Segment(first == 0 ? TOP : 1.0 - first, -i));
                    i++;
                }
                return runs;
            }
        }
    }
}
