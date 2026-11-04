package com.sumutiu.goingnuclear.power;

import com.sumutiu.goingnuclear.config.NuclearConfig;
import com.sumutiu.goingnuclear.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Base block entity for everything on the electric grid. Handles grid registration and overhead wires.
 */
public abstract class ElectricBlockEntity extends BlockEntity implements ElectricNode, WireAnchor {
    private final WireSet wires = new WireSet();
    protected double powerFraction;

    protected ElectricBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public WireSet wires() {
        return this.wires;
    }

    @Override
    public void receivePower(double fraction) {
        this.powerFraction = fraction;
    }

    /**
     * One terminal on every face the block connects power on (see {@link ElectricConnectable#connectsPower}).
     */
    @Override
    public int terminalAt(Direction face) {
        BlockState state = this.getBlockState();
        return state.getBlock() instanceof ElectricConnectable connectable && !connectable.connectsPower(state, face) ? -1 : 0;
    }

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        if (level instanceof ServerLevel serverLevel) PowerGrid.of(serverLevel).register(this.getBlockPos());
    }

    @Override
    public void setRemoved() {
        if (this.level instanceof ServerLevel serverLevel) PowerGrid.of(serverLevel).unregister(this.getBlockPos());
        super.setRemoved();
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState oldState) {
        super.preRemoveSideEffects(pos, oldState);
        if (this.level instanceof ServerLevel serverLevel) cutAllWires(serverLevel, pos, oldState, this.wires, true);
    }

    /**
     * Removes every wire attached to an anchor, unlinking the other ends and dropping the copper wire.
     *
     * @return number of wire items returned
     */
    public static int cutAllWires(ServerLevel level, BlockPos pos, BlockState state, WireSet wires, boolean drop) {
        int items = 0;
        List<BlockPos> targets = new ArrayList<>(wires.targets());
        Vec3 from = WireAnchor.attachPoint(state, pos);
        for (BlockPos target : targets) {
            Vec3 to = level.isLoaded(target) ? WireAnchor.attachPoint(level.getBlockState(target), target) : Vec3.atCenterOf(target);
            items += wireCost(from.distanceTo(to));
            if (level.isLoaded(target) && level.getBlockEntity(target) instanceof WireAnchor other) {
                other.wires().remove(pos);
                BlockEntity be = (BlockEntity) other;
                be.setChanged();
            }
        }
        wires.clear();
        if (drop && items > 0) {
            Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, new ItemStack(ModItems.COPPER_WIRE, items));
        }
        PowerGrid.of(level).markDirty();
        return items;
    }

    public static int wireCost(double length) {
        return Math.max(1, (int) Math.ceil(length / NuclearConfig.get().wireBlocksPerItem));
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        this.wires.save(output);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.wires.load(input);
    }
}
