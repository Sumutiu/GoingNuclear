package com.sumutiu.goingnuclear.item;

import com.sumutiu.goingnuclear.config.NuclearConfig;
import com.sumutiu.goingnuclear.power.WireStringing;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Copper wire works like a lead: right-click a power pole or electric machine to tie it on, walk to the next
 * one with the wire hanging from your hand, and right-click it to tie the other end (see {@link WireStringing}).
 */
public class CopperWireItem extends NuclearItem {
    public CopperWireItem(Properties properties, Item fallback) {
        super(properties, fallback,
                "Use it like a lead: right-click a pole or machine,",
                "walk to the next one and right-click it",
                "Sneak + right-click the air: let go",
                "One wire covers " + NuclearConfig.get().wireBlocksPerItem + " blocks of span");
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
        if (player.isShiftKeyDown()) {
            // Sneaking lets go, like clicking the air.
            return WireStringing.cancel(player) ? InteractionResult.SUCCESS_SERVER : InteractionResult.PASS;
        }
        return WireStringing.clickBlock(player, level, context.getClickedPos(), level.getBlockState(context.getClickedPos()));
    }

    @Override
    public boolean isPolymerBlockInteraction(BlockState state, ServerPlayer player, InteractionHand hand, ItemStack stack, ServerLevel world, BlockHitResult blockHitResult, InteractionResult actionResult) {
        return true;
    }
}
