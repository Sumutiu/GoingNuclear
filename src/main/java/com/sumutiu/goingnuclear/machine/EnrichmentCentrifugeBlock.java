package com.sumutiu.goingnuclear.machine;

import com.sumutiu.goingnuclear.gui.EnrichmentCentrifugeGui;
import com.sumutiu.goingnuclear.power.ElectricConnectable;
import com.sumutiu.goingnuclear.registry.ModItems;
import com.sumutiu.goingnuclear.util.PlayerItems;
import com.sumutiu.goingnuclear.util.Visuals;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * Enriches depleted redstone and glowstone rods (see {@link EnrichmentCentrifugeBlockEntity}). Right-click with
 * depleted rods or dust to load them, right-click with an empty hand to open its screen, sneak-right-click with
 * an empty hand to take the enriched rods out. Power in: the yellow port on the back.
 */
public class EnrichmentCentrifugeBlock extends MachineBlock implements ElectricConnectable {
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final BooleanProperty SPINNING = BooleanProperty.create("spinning");

    public EnrichmentCentrifugeBlock(Properties properties) {
        super(properties,
                state -> Visuals.facing(state.getValue(SPINNING) ? "enrichment_centrifuge_on" : "enrichment_centrifuge", state.getValue(FACING)),
                state -> Blocks.BLAST_FURNACE.defaultBlockState()
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, state.getValue(FACING))
                        .setValue(BlockStateProperties.LIT, state.getValue(SPINNING)));
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(SPINNING, false));
    }

    @Override
    public void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, SPINNING);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext ctx) {
        // Window towards you, power port at the back.
        return this.defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    /**
     * Power only through the back.
     */
    @Override
    public boolean connectsPower(BlockState state, Direction side) {
        return side == state.getValue(FACING).getOpposite();
    }

    @Override
    public InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (ModItems.isTool(stack)) return InteractionResult.PASS;
        boolean spent = stack.is(ModItems.DEPLETED_FUEL_ROD);
        if (!spent && EnrichmentCentrifugeBlockEntity.slotFor(stack) == null) return InteractionResult.TRY_WITH_EMPTY_HAND;
        if (!(player instanceof ServerPlayer serverPlayer) || !(level.getBlockEntity(pos) instanceof EnrichmentCentrifugeBlockEntity centrifuge)) {
            return InteractionResult.SUCCESS;
        }
        if (spent) {
            serverPlayer.sendOverlayMessage(Component.literal("Depleted Fuel Rods were enriched once already. Smelt them for iron.")
                    .withStyle(ChatFormatting.RED));
            return InteractionResult.FAIL;
        }
        int moved = centrifuge.insert(stack, !player.hasInfiniteMaterials());
        if (moved <= 0) {
            serverPlayer.sendOverlayMessage(Component.literal("The centrifuge has no room for that.").withStyle(ChatFormatting.RED));
            return InteractionResult.FAIL;
        }
        return InteractionResult.SUCCESS_SERVER;
    }

    @Override
    public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(player instanceof ServerPlayer serverPlayer) || !(level.getBlockEntity(pos) instanceof EnrichmentCentrifugeBlockEntity centrifuge)) {
            return InteractionResult.SUCCESS;
        }
        if (player.isShiftKeyDown()) {
            for (EnrichmentCentrifugeBlockEntity.Kind kind : EnrichmentCentrifugeBlockEntity.Kind.values()) {
                PlayerItems.give(player, centrifuge.takeDone(kind));
            }
            return InteractionResult.SUCCESS_SERVER;
        }
        new EnrichmentCentrifugeGui(serverPlayer, centrifuge).open();
        return InteractionResult.SUCCESS_SERVER;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new EnrichmentCentrifugeBlockEntity(pos, state);
    }
}
