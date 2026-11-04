package com.sumutiu.goingnuclear.machine;

import com.sumutiu.goingnuclear.config.NuclearConfig;
import com.sumutiu.goingnuclear.power.ElectricBlockEntity;
import com.sumutiu.goingnuclear.registry.ModBlockEntities;
import com.sumutiu.goingnuclear.util.Fmt;
import com.sumutiu.goingnuclear.util.InfoProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.List;

public class BackupGeneratorBlockEntity extends ElectricBlockEntity implements MachineBlock.TickingMachine, InfoProvider {
    private ItemStack fuel = ItemStack.EMPTY;
    private int burnTime;
    private int burnTotal;
    private double produced;
    private boolean disabled;

    public BackupGeneratorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.BACKUP_GENERATOR, pos, state);
    }

    public static int fuelTicks(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        if (stack.is(Items.COAL_BLOCK)) return 16000;
        if (stack.is(Items.DRIED_KELP_BLOCK)) return 4000;
        if (stack.is(Items.BLAZE_ROD)) return 2400;
        if (stack.is(ItemTags.COALS)) return 1600;
        if (stack.is(ItemTags.LOGS)) return 300;
        if (stack.is(ItemTags.PLANKS)) return 300;
        if (stack.is(Items.STICK)) return 100;
        return 0;
    }

    /**
     * @return number of items moved into the generator
     */
    public int insertFuel(ItemStack stack, boolean consume) {
        if (fuelTicks(stack) <= 0) return 0;
        int moved;
        if (this.fuel.isEmpty()) {
            moved = stack.getCount();
            this.fuel = stack.copy();
        } else if (ItemStack.isSameItemSameComponents(this.fuel, stack)) {
            moved = Math.min(stack.getCount(), this.fuel.getMaxStackSize() - this.fuel.getCount());
            this.fuel.grow(moved);
        } else {
            return 0;
        }
        if (consume) stack.shrink(moved);
        this.setChanged();
        return moved;
    }

    public ItemStack takeFuel() {
        ItemStack out = this.fuel;
        this.fuel = ItemStack.EMPTY;
        this.setChanged();
        return out;
    }

    @Override
    public void serverTick(ServerLevel level, BlockPos pos, BlockState state) {
        this.disabled = level.hasNeighborSignal(pos);
        if (this.burnTime > 0) {
            this.burnTime--;
            this.produced += NuclearConfig.get().backupGeneratorPower;
        }
        if (this.burnTime <= 0 && !this.disabled && !this.fuel.isEmpty()) {
            int ticks = fuelTicks(this.fuel);
            if (ticks > 0) {
                this.burnTime = ticks;
                this.burnTotal = ticks;
                this.fuel.shrink(1);
                if (this.fuel.isEmpty()) this.fuel = ItemStack.EMPTY;
                this.setChanged();
            }
        }
        boolean lit = this.burnTime > 0;
        if (state.getValue(BackupGeneratorBlock.LIT) != lit) {
            level.setBlock(pos, state.setValue(BackupGeneratorBlock.LIT, lit), Block.UPDATE_ALL);
        }
        if (lit && level.getGameTime() % 10L == 0L) {
            level.sendParticles(ParticleTypes.SMOKE, pos.getX() + 0.5, pos.getY() + 1.05, pos.getZ() + 0.5, 1, 0.1, 0.0, 0.1, 0.01);
        }
        if (level.getGameTime() % 20L == 0L) this.setChanged();
    }

    @Override
    public double powerOutput() {
        return this.produced;
    }

    @Override
    public void clearOutput() {
        this.produced = 0.0;
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState oldState) {
        super.preRemoveSideEffects(pos, oldState);
        if (this.level != null && !this.fuel.isEmpty()) {
            Containers.dropItemStack(this.level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, this.fuel);
            this.fuel = ItemStack.EMPTY;
        }
    }

    @Override
    public void appendInfo(List<Component> lines) {
        lines.add(Fmt.title("Backup Generator"));
        lines.add(Fmt.label("Status", this.disabled ? "switched off (redstone)" : this.burnTime > 0 ? "running" : "out of fuel",
                this.burnTime > 0 ? ChatFormatting.GREEN : ChatFormatting.RED));
        lines.add(Fmt.label("Output", Fmt.power(this.burnTime > 0 ? NuclearConfig.get().backupGeneratorPower : 0.0), ChatFormatting.GREEN));
        lines.add(Fmt.label("Fuel", this.fuel.isEmpty() ? Component.literal("empty").withStyle(ChatFormatting.DARK_GRAY)
                : Component.literal(this.fuel.getCount() + "x ").append(this.fuel.getHoverName())));
        if (this.burnTotal > 0) lines.add(Fmt.label("Burning", Fmt.pct((double) this.burnTime / this.burnTotal) + " left", ChatFormatting.GOLD));
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.store("fuel", ItemStack.OPTIONAL_CODEC, this.fuel);
        output.putInt("burn", this.burnTime);
        output.putInt("burn_total", this.burnTotal);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.fuel = input.read("fuel", ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY);
        this.burnTime = input.getIntOr("burn", 0);
        this.burnTotal = input.getIntOr("burn_total", 0);
    }
}
