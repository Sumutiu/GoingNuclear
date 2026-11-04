package com.sumutiu.goingnuclear.item;

import com.sumutiu.goingnuclear.power.ElectricBlockEntity;
import com.sumutiu.goingnuclear.power.PowerCableBlock;
import com.sumutiu.goingnuclear.power.WireAnchor;
import com.sumutiu.goingnuclear.power.WireStringing;
import com.sumutiu.goingnuclear.registry.ModItems;
import com.sumutiu.goingnuclear.util.PlayerItems;
import com.sumutiu.goingnuclear.util.Sounds;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Pliers cut wires: sneak-right-click a pole or machine to cut every wire tied to it (the copper wire comes
 * back), or a ground cable to take it up. Plain right-click strings wires like holding Copper Wire does,
 * using wire from your inventory.
 */
public class PliersItem extends NuclearItem {
    public PliersItem(Properties properties, Item fallback) {
        super(properties, fallback,
                "Sneak + right-click pole/machine: cut its wires",
                "Sneak + right-click ground cable: take it up",
                "Right-click pole/machine: string a wire",
                "(or just use Copper Wire like a lead)");
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer serverPlayer && player.isShiftKeyDown() && WireStringing.cancel(serverPlayer)) {
            return InteractionResult.SUCCESS_SERVER;
        }
        return InteractionResult.PASS;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!(context.getPlayer() instanceof ServerPlayer player) || !(context.getLevel() instanceof ServerLevel level)) return InteractionResult.SUCCESS;
        BlockPos pos = context.getClickedPos();
        BlockState state = level.getBlockState(pos);
        if (!player.isShiftKeyDown()) return WireStringing.clickBlock(player, level, pos, state);

        if (state.getBlock() instanceof PowerCableBlock) {
            level.destroyBlock(pos, !player.isCreative(), player);
            return InteractionResult.SUCCESS_SERVER;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof WireAnchor anchor)) {
            PipeWrenchItem.tell(player, "Wires tie on to power poles and electric machines.", ChatFormatting.RED);
            return InteractionResult.FAIL;
        }
        int items = ElectricBlockEntity.cutAllWires(level, pos, state, anchor.wires(), false);
        be.setChanged();
        if (items > 0 && !player.hasInfiniteMaterials()) {
            PlayerItems.give(player, new ItemStack(ModItems.COPPER_WIRE, items));
        }
        PipeWrenchItem.tell(player, items > 0 ? "Cut all wires (" + items + " copper wire returned)." : "No wires tied on here.", ChatFormatting.GOLD);
        Sounds.at(level, pos, Sounds.leadUntied(), 0.8f, 1.2f);
        return InteractionResult.SUCCESS_SERVER;
    }

    @Override
    public boolean isPolymerBlockInteraction(BlockState state, ServerPlayer player, InteractionHand hand, ItemStack stack, ServerLevel world, BlockHitResult blockHitResult, InteractionResult actionResult) {
        return true;
    }
}
