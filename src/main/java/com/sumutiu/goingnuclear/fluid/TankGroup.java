package com.sumutiu.goingnuclear.fluid;

import com.sumutiu.goingnuclear.machine.ExpansionTankBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.List;

/**
 * Expansion tanks touching each other, sharing one store of water. The water is kept here while the group
 * exists and written back to the tanks (an even share each) now and then, so it is saved with them; a tank that
 * is broken takes its share with it.
 */
public final class TankGroup {
    private final List<BlockPos> members;
    private final FluidTank water;

    TankGroup(List<BlockPos> members, double perTank) {
        this.members = List.copyOf(members);
        this.water = new FluidTank(FluidType.WATER, perTank * members.size());
    }

    public FluidTank water() {
        return this.water;
    }

    public int size() {
        return this.members.size();
    }

    List<BlockPos> members() {
        return this.members;
    }

    void load(ServerLevel level) {
        double amount = 0.0;
        double heat = 0.0;
        for (BlockPos pos : this.members) {
            if (level.getBlockEntity(pos) instanceof ExpansionTankBlockEntity tank) {
                amount += tank.storedAmount();
                heat += tank.storedAmount() * tank.storedTemperature();
            }
        }
        this.water.set(Math.min(amount, this.water.capacity()));
        this.water.setTemperature(amount > 0.0 ? heat / amount : FluidType.WATER.defaultTemperature());
    }

    void writeBack(ServerLevel level) {
        double share = this.water.amount() / this.members.size();
        for (BlockPos pos : this.members) {
            if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof ExpansionTankBlockEntity tank) {
                tank.setStored(share, this.water.temperature());
            }
        }
    }
}
