package com.sumutiu.goingnuclear.power;

import com.sumutiu.goingnuclear.GoingNuclear;
import com.sumutiu.goingnuclear.config.NuclearConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.SmokerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Lets vanilla furnaces, blast furnaces and smokers run on electricity instead of fuel.
 * <p>
 * The furnace's synced data (the same values the furnace screen uses: 0 = lit time, 1 = lit duration,
 * 2 = cooking progress, 3 = cooking total) is located by type, so no mixins or field names are needed.
 * While powered, the lit time is kept topped up, so the furnace smelts without burning its fuel slot.
 */
public final class FurnacePower {
    private static final int SLOT_INPUT = 0;
    private static final int SLOT_RESULT = 2;
    private static final int DATA_LIT_TIME = 0;
    private static final int DATA_LIT_DURATION = 1;

    private static @Nullable Field dataField;
    private static boolean searched;

    private FurnacePower() {
    }

    private static @Nullable ContainerData data(AbstractFurnaceBlockEntity furnace) {
        if (!searched) {
            searched = true;
            for (Field field : AbstractFurnaceBlockEntity.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && ContainerData.class.isAssignableFrom(field.getType())) {
                    try {
                        field.setAccessible(true);
                        dataField = field;
                    } catch (Exception e) {
                        GoingNuclear.LOGGER.warn("Electric furnaces disabled: cannot access furnace data", e);
                    }
                    break;
                }
            }
            if (dataField == null) GoingNuclear.LOGGER.warn("Electric furnaces disabled: furnace data field not found");
        }
        if (dataField == null) return null;
        try {
            return (ContainerData) dataField.get(furnace);
        } catch (Exception _) {
            return null;
        }
    }

    /**
     * Energy (J/t) this furnace wants this tick, or 0 when idle.
     */
    public static double demand(AbstractFurnaceBlockEntity furnace) {
        if (!NuclearConfig.get().electricFurnaces) return 0.0;
        ItemStack input = furnace.getItem(SLOT_INPUT);
        if (input.isEmpty()) return 0.0;
        ItemStack result = furnace.getItem(SLOT_RESULT);
        if (!result.isEmpty() && result.getCount() >= result.getMaxStackSize()) return 0.0;
        if (furnace instanceof BlastFurnaceBlockEntity) return NuclearConfig.get().blastFurnacePower;
        if (furnace instanceof SmokerBlockEntity) return NuclearConfig.get().smokerPower;
        return NuclearConfig.get().furnacePower;
    }

    /**
     * Keeps the furnace burning for the next tick.
     */
    public static void power(ServerLevel level, BlockPos pos, AbstractFurnaceBlockEntity furnace) {
        ContainerData data = data(furnace);
        if (data == null) return;
        if (data.get(DATA_LIT_TIME) < 2) data.set(DATA_LIT_TIME, 2);
        if (data.get(DATA_LIT_DURATION) < 2) data.set(DATA_LIT_DURATION, 2);
        BlockState state = level.getBlockState(pos);
        if (state.hasProperty(BlockStateProperties.LIT) && !state.getValue(BlockStateProperties.LIT)) {
            level.setBlock(pos, state.setValue(BlockStateProperties.LIT, true), Block.UPDATE_ALL);
        }
        furnace.setChanged();
    }

    public static boolean isElectricallyLit(AbstractFurnaceBlockEntity furnace) {
        ContainerData data = data(furnace);
        return data != null && data.get(DATA_LIT_TIME) > 0;
    }
}
