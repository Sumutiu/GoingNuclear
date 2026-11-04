package com.sumutiu.goingnuclear.reactor;

import com.sumutiu.goingnuclear.machine.MachineBlock;
import com.sumutiu.goingnuclear.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

public class FuelChannelBlockEntity extends BlockEntity implements MachineBlock.TickingMachine {
    private ItemStack rod = ItemStack.EMPTY;

    public FuelChannelBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.FUEL_CHANNEL, pos, state);
    }

    public ItemStack getRod() {
        return this.rod;
    }

    public void setRod(ItemStack stack) {
        this.rod = stack.isEmpty() ? ItemStack.EMPTY : stack;
        this.setChanged();
        this.syncState();
    }

    public ItemStack takeRod() {
        ItemStack out = this.rod;
        this.rod = ItemStack.EMPTY;
        this.setChanged();
        this.syncState();
        return out;
    }

    /**
     * Used by the controller to write burn-up back without touching the block state.
     */
    public void updateRodSilently(ItemStack stack) {
        this.rod = stack;
        this.setChanged();
    }

    /**
     * Meltdown: the fuel is gone.
     */
    public void destroyRod() {
        this.rod = ItemStack.EMPTY;
        this.setChanged();
    }

    private void syncState() {
        if (this.level == null || this.level.isClientSide()) return;
        BlockState state = this.getBlockState();
        if (!state.hasProperty(FuelChannelBlock.ROD)) return;
        FuelChannelBlock.Rod wanted = FuelChannelBlock.Rod.of(FuelRods.tierOf(this.rod));
        if (state.getValue(FuelChannelBlock.ROD) != wanted) {
            this.level.setBlock(this.getBlockPos(), state.setValue(FuelChannelBlock.ROD, wanted), Block.UPDATE_ALL);
        }
    }

    @Override
    public void serverTick(ServerLevel level, BlockPos pos, BlockState state) {
        long time = level.getGameTime();
        if (time % 40L == 0L) this.syncState();
        if (time % 20L == 10L) this.updateGlow(level, pos, state);
    }

    /**
     * Makes the channel give off light while its rod is fissioning (brighter at higher power).
     */
    private void updateGlow(ServerLevel level, BlockPos pos, BlockState state) {
        if (!state.hasProperty(FuelChannelBlock.GLOW)) return;
        int glow = 0;
        if (FuelRods.isActive(FuelRods.tierOf(this.rod))) {
            ReactorControllerBlockEntity controller = ReactorRegistry.controllerAt(level, pos);
            if (controller != null) glow = FuelChannelBlock.glowFor(controller.powerLevel());
        }
        if (state.getValue(FuelChannelBlock.GLOW) != glow) {
            level.setBlock(pos, state.setValue(FuelChannelBlock.GLOW, glow), Block.UPDATE_CLIENTS);
        }
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState oldState) {
        super.preRemoveSideEffects(pos, oldState);
        if (this.level != null && !this.rod.isEmpty()) {
            Containers.dropItemStack(this.level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, this.rod);
            this.rod = ItemStack.EMPTY;
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.store("rod", ItemStack.OPTIONAL_CODEC, this.rod);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.rod = input.read("rod", ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY);
    }
}
