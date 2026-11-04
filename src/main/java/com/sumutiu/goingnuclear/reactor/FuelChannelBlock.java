package com.sumutiu.goingnuclear.reactor;

import com.sumutiu.goingnuclear.config.NuclearConfig;
import com.sumutiu.goingnuclear.machine.MachineBlock;
import com.sumutiu.goingnuclear.model.BoxModel;
import com.sumutiu.goingnuclear.registry.ModItems;
import com.sumutiu.goingnuclear.util.PlayerItems;
import com.sumutiu.goingnuclear.util.Visuals;
import eu.pb4.polymer.core.api.block.PolymerBlock;
import eu.pb4.polymer.virtualentity.api.BlockWithElementHolder;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.elements.BlockDisplayElement;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import eu.pb4.polymer.virtualentity.api.elements.VirtualElement;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Brightness;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds one fuel rod inside the reactor. Fuel can be loaded here directly (right-click with a rod,
 * sneak-right-click to take it out) or through the controller's refuelling buttons.
 * <p>
 * Drawn with block displays as an open fuel assembly (tie plates and a 3x3 bundle of pins), so you can
 * see the fuel through reactor glass. While the reactor runs the pins glow inside a blue (Cherenkov) haze
 * that turns orange and then red as the core overheats, and the channel gives off real light ({@link #GLOW}).
 * Channels stacked on top of each other join into one tall assembly: the tie plates only sit at the very
 * top and bottom of the stack and the pins run through.
 */
public class FuelChannelBlock extends Block implements PolymerBlock, BlockWithElementHolder, EntityBlock, ReactorPart {
    public static final EnumProperty<Rod> ROD = EnumProperty.create("rod", Rod.class);
    /**
     * 0 = dark, 1..3 = increasingly bright, following the reactor's power. Set by the block entity.
     */
    public static final IntegerProperty GLOW = IntegerProperty.create("glow", 0, 3);

    public FuelChannelBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(ROD, Rod.EMPTY).setValue(GLOW, 0));
    }

    /**
     * Light level for the block properties.
     */
    public static int lightLevel(BlockState state) {
        return switch (state.getValue(GLOW)) {
            case 1 -> 6;
            case 2 -> 10;
            case 3 -> 13;
            default -> 0;
        };
    }

    /**
     * Glow step for a reactor running at {@code powerLevel} (fission power relative to nominal).
     */
    public static int glowFor(double powerLevel) {
        if (powerLevel < 0.02) return 0;
        if (powerLevel < 0.3) return 1;
        if (powerLevel < 0.8) return 2;
        return 3;
    }

    @Override
    public boolean isStructural() {
        return false;
    }

    @Override
    public void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ROD, GLOW);
    }

    // Only placing or breaking the channel changes the reactor structure. Rod and glow changes are state
    // updates of the same block; the code that changes the rod tells the controller itself.

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean notify) {
        super.onPlace(state, level, pos, oldState, notify);
        if (!oldState.is(this)) ReactorRegistry.notifyChange(level, pos);
    }

    @Override
    public void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean moved) {
        super.affectNeighborsAfterRemoval(state, level, pos, moved);
        if (!level.getBlockState(pos).is(this)) ReactorRegistry.notifyChange(level, pos);
    }

    @Override
    public InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (ModItems.isTool(stack)) return InteractionResult.PASS;
        int tier = FuelRods.tierOf(stack);
        if (tier == FuelRods.EMPTY) return InteractionResult.TRY_WITH_EMPTY_HAND;
        if (!(level instanceof ServerLevel serverLevel) || !(level.getBlockEntity(pos) instanceof FuelChannelBlockEntity channel)) {
            return InteractionResult.SUCCESS;
        }
        if (!channel.getRod().isEmpty()) {
            message(player, "This channel already holds a rod.", ChatFormatting.RED);
            return InteractionResult.FAIL;
        }
        channel.setRod(stack.copyWithCount(1));
        if (!player.hasInfiniteMaterials()) stack.shrink(1);
        ReactorRegistry.notifyChange(serverLevel, pos);
        return InteractionResult.SUCCESS_SERVER;
    }

    @Override
    public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(level instanceof ServerLevel serverLevel) || !(level.getBlockEntity(pos) instanceof FuelChannelBlockEntity channel)) {
            return InteractionResult.SUCCESS;
        }
        if (!player.isShiftKeyDown()) {
            ItemStack rod = channel.getRod();
            message(player, rod.isEmpty() ? "Empty fuel channel." : "Holds: " + rod.getHoverName().getString()
                    + (FuelRods.isActive(FuelRods.tierOf(rod)) ? " (" + Math.round(FuelRods.remainingFraction(rod) * 100) + "% fuel left)" : ""), ChatFormatting.GRAY);
            return InteractionResult.SUCCESS_SERVER;
        }
        ReactorControllerBlockEntity controller = ReactorRegistry.controllerAt(serverLevel, pos);
        if (controller != null && controller.temperature() > 200.0) {
            message(player, "Too hot to handle fuel: the core is above 200 °C.", ChatFormatting.RED);
            return InteractionResult.FAIL;
        }
        ItemStack rod = channel.takeRod();
        if (!rod.isEmpty()) {
            PlayerItems.give(player, rod);
            ReactorRegistry.notifyChange(serverLevel, pos);
        }
        return InteractionResult.SUCCESS_SERVER;
    }

    private static void message(Player player, String text, ChatFormatting color) {
        if (player instanceof ServerPlayer serverPlayer) serverPlayer.sendOverlayMessage(Component.literal(text).withStyle(color));
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FuelChannelBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) return null;
        return (tickLevel, pos, tickState, be) -> {
            if (be instanceof MachineBlock.TickingMachine machine && tickLevel instanceof ServerLevel serverLevel) {
                machine.serverTick(serverLevel, pos, tickState);
            }
        };
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
        return new FuelModel(initialBlockState);
    }

    @Override
    public boolean tickElementHolder(ServerLevel level, BlockPos pos, BlockState initialBlockState) {
        return true;
    }

    public static final class FuelModel extends BoxModel {
        private static final double PIN_HALF = 0.06;
        private static final double[] PIN_CENTERS = {0.25, 0.5, 0.75};
        private static final int UPDATE_TICKS = 10;
        private static final int STACK_TICKS = 4;
        private static final int MIN_LIGHT = 6;
        private static final int JOINED_UP = 1;
        private static final int JOINED_DOWN = 2;
        private static final Brightness FULL_BRIGHT = new Brightness(15, 15);

        private final List<BlockDisplayElement> pins = new ArrayList<>();
        private final List<VirtualElement> glowing = new ArrayList<>();
        private @Nullable ItemDisplayElement packModel;
        private @Nullable BlockDisplayElement glow;
        private @Nullable BlockState shownGlowLook;
        private @Nullable Rod builtRod;
        private int builtJoins;
        private boolean active;
        private int ticks;

        FuelModel(BlockState state) {
            this.setPartViewRange(0.5f);
            this.rebuild(state);
        }

        @Override
        protected void rebuild(BlockState state) {
            if (!(state.getBlock() instanceof FuelChannelBlock)) {
                this.clearAll();
                return;
            }
            Rod rod = state.getValue(ROD);
            int joins = this.joins();
            if (rod == this.builtRod && joins == this.builtJoins) {
                // Only the glow level changed: no need to rebuild the parts.
                this.updateLook();
                return;
            }
            this.build(rod, joins);
        }

        /**
         * Builds the assembly. Where another fuel channel sits above or below, the tie plate on that side is
         * left out and the pins reach the edge of the block, so stacked channels look like one long assembly.
         */
        private void build(Rod rod, int joins) {
            this.clearAll();
            this.builtRod = rod;
            this.builtJoins = joins;
            this.active = rod.active();
            boolean up = (joins & JOINED_UP) != 0;
            boolean down = (joins & JOINED_DOWN) != 0;
            double low = down ? 0.0 : 0.06;
            double high = up ? 1.0 : 0.94;

            // Players with the resource pack: one 3D model of the whole assembly.
            String part = up && down ? "_middle" : up ? "_bottom" : down ? "_top" : "";
            this.packModel = this.packPart(Visuals.modelStack("core/fuel_" + rod.getSerializedName() + part), new Matrix4f());

            // Everyone else: the same assembly built from vanilla blocks.
            this.setPartVisibility(Visuals.WITHOUT_PACK);
            // Enriched fuel is told apart by its gold tie plates.
            BlockState plate = rod.enriched() ? Blocks.GOLD_BLOCK.defaultBlockState() : Blocks.IRON_BLOCK.defaultBlockState();
            if (!down) this.box(plate, 0.1, 0.0, 0.1, 0.9, 0.06, 0.9);
            if (!up) this.box(plate, 0.1, 0.94, 0.1, 0.9, 1.0, 0.9);
            if (rod == Rod.EMPTY) {
                // Empty assembly: just the four guide posts holding the plates.
                BlockState post = Blocks.CONCRETE.lightGray().defaultBlockState();
                for (double x : new double[]{0.12, 0.8}) {
                    for (double z : new double[]{0.12, 0.8}) {
                        this.box(post, x, low, z, x + 0.08, high, z + 0.08);
                    }
                }
            } else {
                BlockState pinLook = switch (rod) {
                    case REDSTONE, ENRICHED_REDSTONE -> Blocks.REDSTONE_BLOCK.defaultBlockState();
                    case GLOWSTONE, ENRICHED_GLOWSTONE -> Blocks.GLOWSTONE.defaultBlockState();
                    case DEPLETED_REDSTONE -> Blocks.NETHER_WART_BLOCK.defaultBlockState();
                    case DEPLETED_GLOWSTONE -> Blocks.END_STONE.defaultBlockState();
                    default -> Blocks.CONCRETE.gray().defaultBlockState();
                };
                for (double x : PIN_CENTERS) {
                    for (double z : PIN_CENTERS) {
                        this.pins.add(this.box(pinLook, x - PIN_HALF, low, z - PIN_HALF, x + PIN_HALF, high, z + PIN_HALF));
                    }
                }
            }
            this.setPartVisibility(null);
            this.updateLook();
        }

        private void clearAll() {
            this.clearParts();
            this.pins.clear();
            this.glowing.clear();
            this.packModel = null;
            this.glow = null;
            this.shownGlowLook = null;
            this.builtRod = null;
        }

        /**
         * Which neighbours above and below are fuel channels too. Before the model is attached to the world
         * (chunk load) it cannot look, so it keeps what it has; the next check puts the stack together.
         */
        private int joins() {
            // The level is the world, not a resource to close (IntelliJ takes every AutoCloseable for one).
            @SuppressWarnings("resource")
            ServerLevel level = this.level();
            if (level == null) return this.builtJoins;
            BlockPos pos = this.blockPos();
            int joins = 0;
            if (level.getBlockState(pos.above()).getBlock() instanceof FuelChannelBlock) joins |= JOINED_UP;
            if (level.getBlockState(pos.below()).getBlock() instanceof FuelChannelBlock) joins |= JOINED_DOWN;
            return joins;
        }

        @Override
        protected void onTick() {
            // Placing or breaking a neighbour does not update this block, so look for stack changes now and then
            // (the first tick included, so a stack loaded with the chunk is put together right away).
            if (++this.ticks % STACK_TICKS == 1 && this.builtRod != null) {
                int joins = this.joins();
                if (joins != this.builtJoins) {
                    this.build(this.builtRod, joins);
                    return;
                }
            }
            if (this.ticks % UPDATE_TICKS != 0) return;
            this.updateLook();
        }

        /**
         * Lights the assembly (never fully dark, so it shows through reactor glass) and, while the reactor
         * runs, makes the pins glow inside a haze: blue normally, orange above the warning temperature and
         * red once the fuel is being damaged.
         */
        private void updateLook() {
            if (this.builtRod == null) return;
            ServerLevel level = this.level();
            ReactorControllerBlockEntity controller = level != null && this.active ? ReactorRegistry.controllerAt(level, this.blockPos()) : null;
            double power = controller != null ? controller.powerLevel() : 0.0;
            Brightness base = this.lightAtLeast(MIN_LIGHT);
            this.glowing.clear();

            if (controller == null || power < 0.02) {
                if (this.glow != null) {
                    this.removePart(this.glow);
                    this.glow = null;
                    this.shownGlowLook = null;
                }
                this.setPartsBrightness(base, this.glowing);
                return;
            }

            NuclearConfig cfg = NuclearConfig.get();
            double temperature = controller.temperature();
            BlockState look;
            if (temperature >= cfg.fuelDamageTemp) {
                look = Blocks.STAINED_GLASS.red().defaultBlockState();
            } else if (temperature >= cfg.warningTemp) {
                look = Blocks.STAINED_GLASS.orange().defaultBlockState();
            } else {
                look = Blocks.STAINED_GLASS.lightBlue().defaultBlockState();
            }
            if (this.glow == null) {
                // The haze runs on into stacked channels too.
                double low = (this.builtJoins & JOINED_DOWN) != 0 ? 0.0 : 0.07;
                double high = (this.builtJoins & JOINED_UP) != 0 ? 1.0 : 0.93;
                this.glow = this.box(look, 0.15, low, 0.15, 0.85, high, 0.85);
                this.glow.setBrightness(FULL_BRIGHT);
                this.shownGlowLook = look;
            } else if (look != this.shownGlowLook) {
                this.glow.setBlockState(look);
                this.shownGlowLook = look;
            }

            // Brighter with more power.
            int light = (int) Math.round(Math.min(15.0, 8.0 + 7.0 * Math.min(1.0, power)));
            Brightness pinLight = new Brightness(Math.max(light, base.block()), Math.max(light, base.sky()));
            for (BlockDisplayElement pin : this.pins) pin.setBrightness(pinLight);
            if (this.packModel != null) {
                this.packModel.setBrightness(pinLight);
                this.glowing.add(this.packModel);
            }
            this.glowing.addAll(this.pins);
            this.glowing.add(this.glow);
            this.setPartsBrightness(base, this.glowing);
        }
    }

    /**
     * What the channel holds (the resource pack models are named core/fuel_ plus the name).
     */
    public enum Rod implements StringRepresentable {
        EMPTY("empty"),
        REDSTONE("redstone"),
        GLOWSTONE("glowstone"),
        DEPLETED("depleted"),
        DEPLETED_REDSTONE("depleted_redstone"),
        DEPLETED_GLOWSTONE("depleted_glowstone"),
        ENRICHED_REDSTONE("enriched_redstone"),
        ENRICHED_GLOWSTONE("enriched_glowstone");

        private final String name;

        Rod(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return this.name;
        }

        /**
         * Whether the rod still has fuel in it (it glows while the reactor runs).
         */
        public boolean active() {
            return this == REDSTONE || this == GLOWSTONE || this == ENRICHED_REDSTONE || this == ENRICHED_GLOWSTONE;
        }

        public boolean enriched() {
            return this == ENRICHED_REDSTONE || this == ENRICHED_GLOWSTONE;
        }

        public static Rod of(int tier) {
            return switch (tier) {
                case FuelRods.REDSTONE -> REDSTONE;
                case FuelRods.GLOWSTONE -> GLOWSTONE;
                case FuelRods.DEPLETED -> DEPLETED;
                case FuelRods.DEPLETED_REDSTONE -> DEPLETED_REDSTONE;
                case FuelRods.DEPLETED_GLOWSTONE -> DEPLETED_GLOWSTONE;
                case FuelRods.ENRICHED_REDSTONE -> ENRICHED_REDSTONE;
                case FuelRods.ENRICHED_GLOWSTONE -> ENRICHED_GLOWSTONE;
                default -> EMPTY;
            };
        }
    }
}
