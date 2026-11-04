package com.sumutiu.goingnuclear.registry;

import com.sumutiu.goingnuclear.GoingNuclear;
import com.sumutiu.goingnuclear.machine.BackupGeneratorBlockEntity;
import com.sumutiu.goingnuclear.machine.BatteryBankBlockEntity;
import com.sumutiu.goingnuclear.machine.CheckValveBlockEntity;
import com.sumutiu.goingnuclear.machine.CirculationPumpBlockEntity;
import com.sumutiu.goingnuclear.machine.CondenserBlockEntity;
import com.sumutiu.goingnuclear.machine.ElectricLampBlockEntity;
import com.sumutiu.goingnuclear.machine.EnrichmentCentrifugeBlockEntity;
import com.sumutiu.goingnuclear.machine.ExpansionTankBlockEntity;
import com.sumutiu.goingnuclear.machine.FluidPipeBlockEntity;
import com.sumutiu.goingnuclear.machine.HeatExchangerBlockEntity;
import com.sumutiu.goingnuclear.machine.SteamDumpValveBlockEntity;
import com.sumutiu.goingnuclear.machine.SteamTurbineBlockEntity;
import com.sumutiu.goingnuclear.machine.TemperatureSensorBlockEntity;
import com.sumutiu.goingnuclear.machine.WaterOutletBlockEntity;
import com.sumutiu.goingnuclear.machine.WaterPumpBlockEntity;
import com.sumutiu.goingnuclear.power.PowerPoleBlockEntity;
import com.sumutiu.goingnuclear.reactor.CoriumBlockEntity;
import com.sumutiu.goingnuclear.reactor.FuelChannelBlockEntity;
import com.sumutiu.goingnuclear.reactor.ReactorControllerBlockEntity;
import eu.pb4.polymer.core.api.block.PolymerBlockUtils;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

public final class ModBlockEntities {
    public static final BlockEntityType<ReactorControllerBlockEntity> REACTOR_CONTROLLER = register("reactor_controller",
            FabricBlockEntityTypeBuilder.create(ReactorControllerBlockEntity::new, ModBlocks.REACTOR_CONTROLLER));
    public static final BlockEntityType<FuelChannelBlockEntity> FUEL_CHANNEL = register("fuel_channel",
            FabricBlockEntityTypeBuilder.create(FuelChannelBlockEntity::new, ModBlocks.FUEL_CHANNEL));
    public static final BlockEntityType<CoriumBlockEntity> CORIUM = register("corium",
            FabricBlockEntityTypeBuilder.create(CoriumBlockEntity::new, ModBlocks.CORIUM));
    public static final BlockEntityType<FluidPipeBlockEntity> FLUID_PIPE = register("fluid_pipe",
            FabricBlockEntityTypeBuilder.create(FluidPipeBlockEntity::new, ModBlocks.FLUID_PIPE, ModBlocks.PIPE_WALL_PORT));
    public static final BlockEntityType<CheckValveBlockEntity> CHECK_VALVE = register("check_valve",
            FabricBlockEntityTypeBuilder.create(CheckValveBlockEntity::new, ModBlocks.CHECK_VALVE));
    public static final BlockEntityType<WaterPumpBlockEntity> WATER_PUMP = register("water_pump",
            FabricBlockEntityTypeBuilder.create(WaterPumpBlockEntity::new, ModBlocks.WATER_PUMP));
    public static final BlockEntityType<CirculationPumpBlockEntity> CIRCULATION_PUMP = register("circulation_pump",
            FabricBlockEntityTypeBuilder.create(CirculationPumpBlockEntity::new, ModBlocks.CIRCULATION_PUMP));
    public static final BlockEntityType<HeatExchangerBlockEntity> HEAT_EXCHANGER = register("heat_exchanger",
            FabricBlockEntityTypeBuilder.create(HeatExchangerBlockEntity::new, ModBlocks.HEAT_EXCHANGER));
    public static final BlockEntityType<SteamTurbineBlockEntity> STEAM_TURBINE = register("steam_turbine",
            FabricBlockEntityTypeBuilder.create(SteamTurbineBlockEntity::new, ModBlocks.STEAM_TURBINE));
    public static final BlockEntityType<CondenserBlockEntity> CONDENSER = register("condenser",
            FabricBlockEntityTypeBuilder.create(CondenserBlockEntity::new, ModBlocks.CONDENSER));
    public static final BlockEntityType<SteamDumpValveBlockEntity> STEAM_DUMP_VALVE = register("steam_dump_valve",
            FabricBlockEntityTypeBuilder.create(SteamDumpValveBlockEntity::new, ModBlocks.STEAM_DUMP_VALVE));
    public static final BlockEntityType<WaterOutletBlockEntity> WATER_OUTLET = register("water_outlet",
            FabricBlockEntityTypeBuilder.create(WaterOutletBlockEntity::new, ModBlocks.WATER_OUTLET));
    public static final BlockEntityType<EnrichmentCentrifugeBlockEntity> ENRICHMENT_CENTRIFUGE = register("enrichment_centrifuge",
            FabricBlockEntityTypeBuilder.create(EnrichmentCentrifugeBlockEntity::new, ModBlocks.ENRICHMENT_CENTRIFUGE));
    public static final BlockEntityType<ExpansionTankBlockEntity> EXPANSION_TANK = register("expansion_tank",
            FabricBlockEntityTypeBuilder.create(ExpansionTankBlockEntity::new, ModBlocks.EXPANSION_TANK));
    public static final BlockEntityType<PowerPoleBlockEntity> POWER_POLE = register("power_pole",
            FabricBlockEntityTypeBuilder.create(PowerPoleBlockEntity::new, ModBlocks.POWER_POLE));
    public static final BlockEntityType<BatteryBankBlockEntity> BATTERY_BANK = register("battery_bank",
            FabricBlockEntityTypeBuilder.create(BatteryBankBlockEntity::new, ModBlocks.BATTERY_BANK));
    public static final BlockEntityType<BackupGeneratorBlockEntity> BACKUP_GENERATOR = register("backup_generator",
            FabricBlockEntityTypeBuilder.create(BackupGeneratorBlockEntity::new, ModBlocks.BACKUP_GENERATOR));
    public static final BlockEntityType<ElectricLampBlockEntity> ELECTRIC_LAMP = register("electric_lamp",
            FabricBlockEntityTypeBuilder.create(ElectricLampBlockEntity::new, ModBlocks.ELECTRIC_LAMP));
    public static final BlockEntityType<TemperatureSensorBlockEntity> TEMPERATURE_SENSOR = register("temperature_sensor",
            FabricBlockEntityTypeBuilder.create(TemperatureSensorBlockEntity::new, ModBlocks.TEMPERATURE_SENSOR));

    private ModBlockEntities() {
    }

    private static <T extends BlockEntity> BlockEntityType<T> register(String name, FabricBlockEntityTypeBuilder<T> builder) {
        BlockEntityType<T> type = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, GoingNuclear.id(name), builder.build());
        PolymerBlockUtils.registerBlockEntity(type);
        return type;
    }

    public static void init() {
        // Loads the class, which registers everything above.
    }
}
