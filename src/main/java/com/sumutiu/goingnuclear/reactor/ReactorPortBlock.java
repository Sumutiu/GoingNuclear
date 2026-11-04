package com.sumutiu.goingnuclear.reactor;

import com.sumutiu.goingnuclear.block.TexturedBlock;
import com.sumutiu.goingnuclear.fluid.FluidConnectable;
import com.sumutiu.goingnuclear.registry.ModItems;
import com.sumutiu.goingnuclear.util.Visuals;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Fluid port in the reactor wall. INPUT ports draw water in; OUTPUT ports push steam (boiling mode)
 * or coolant (pressurized mode) out. Toggle with the pipe wrench.
 */
public class ReactorPortBlock extends TexturedBlock implements ReactorPart, FluidConnectable {
    public static final EnumProperty<Mode> MODE = EnumProperty.create("mode", Mode.class);

    public ReactorPortBlock(Properties properties) {
        super(properties,
                state -> Visuals.model(state.getValue(MODE) == Mode.INPUT ? "reactor_port_input" : "reactor_port_output"),
                state -> state.getValue(MODE) == Mode.INPUT ? Blocks.CONCRETE.cyan().defaultBlockState() : Blocks.CONCRETE.orange().defaultBlockState());
        this.registerDefaultState(this.stateDefinition.any().setValue(MODE, Mode.INPUT));
    }

    @Override
    public boolean isStructural() {
        return true;
    }

    @Override
    public void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(MODE);
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean notify) {
        super.onPlace(state, level, pos, oldState, notify);
        ReactorRegistry.notifyChange(level, pos);
    }

    @Override
    public void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean moved) {
        super.affectNeighborsAfterRemoval(state, level, pos, moved);
        ReactorRegistry.notifyChange(level, pos);
    }

    @Override
    public InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (ModItems.isTool(stack)) return InteractionResult.PASS;
        return super.useItemOn(stack, state, level, pos, player, hand, hit);
    }

    public enum Mode implements StringRepresentable {
        INPUT("input"),
        OUTPUT("output");

        private final String name;

        Mode(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return this.name;
        }
    }
}
