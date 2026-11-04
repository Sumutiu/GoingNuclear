package com.sumutiu.goingnuclear.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sumutiu.goingnuclear.GoingNuclear;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Server configuration, stored in config/goingnuclear.json.
 * Units: energy in J, power in J per tick (1 J/t = 20 W), fluids in mB, temperatures in degrees Celsius.
 */
public final class NuclearConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static NuclearConfig instance = new NuclearConfig();

    public int minReactorSize = 3;
    public int maxReactorSize = 15;

    public double redstoneRodPower = 1000.0;
    public double redstoneRodEnergy = 3.6e8;
    public double redstoneNeutronFactor = 1.0;
    public double glowstoneRodPower = 2750.0;
    public double glowstoneRodEnergy = 4.8e8;
    public double glowstoneNeutronFactor = 1.5;
    public double enrichedRedstoneRodPower = 1500.0;
    public double enrichedGlowstoneRodPower = 3750.0;
    public double enrichedEnergyFactor = 1.0;

    public double fuelCoupling = 0.25;
    public double controlRodAbsorption = 0.9;
    public double voidModerationMin = 0.4;
    public double dopplerReferenceTemp = 300.0;
    public double dopplerScale = 1500.0;
    public double decayHeatFraction = 0.06;
    public int decayHeatTimeConstantTicks = 600;
    public boolean xenonPoisoning = true;
    public double xenonWorth = 0.10;
    public double xenonTimeScale = 1.0;
    public double xenonBurnout = 3.0;

    public double interiorHeatCapacity = 4000.0;
    public double shellHeatCapacity = 1000.0;
    public double shellHeatLoss = 0.01;
    public double boilingTransfer = 30.0;
    public double coreToCoolantTransfer = 400.0;
    public double pressurizedMaxTemp = 345.0;
    public double fuelDamageTemp = 1200.0;
    public double integrityLossDivisor = 400.0;
    public double warningTemp = 800.0;
    public double defaultScramTemp = 950.0;

    public double boilHeatPerWater = 2000.0;
    public double steamPerWater = 2.0;
    public double steamEnergy = 1000.0;
    public double coolantHeatCapacity = 10.0;
    public double heatExchangerMinTemp = 150.0;
    public double coolantPerInteriorBlock = 1000.0;
    public double steamPerInteriorBlock = 2000.0;
    public double portRate = 200.0;
    public double makeUpLevel = 0.9;
    public double pipeCapacity = 250.0;
    public double waterPumpRate = 25.0;
    public double waterPerSourceBlock = 8.0;
    public double circulationPumpRate = 25.0;
    public double checkValveRate = 100.0;
    public double checkValveTopUp = 0.8;
    public double expansionTankCapacity = 8000.0;
    public double expansionTankRate = 200.0;
    public double waterOutletRate = 200.0;
    public double heatExchangerRate = 25.0;
    public double heatExchangerEfficiency = 0.95;
    public double turbineSteamRate = 20.0;
    public double turbineEfficiency = 0.35;
    public double turbineSteamLoss = 0.005;
    public double heatExchangerCoolantLoss = 0.001;
    public double steamApproachTemp = 20.0;
    public double steamDesignTemp = 300.0;
    public double steamMaxBonus = 1.2;
    public double steamDumpRate = 40.0;
    public double condenserRate = 20.0;
    public double condenserCoolingWater = 0.5;

    public double waterPumpPower = 50.0;
    public double circulationPumpPower = 75.0;
    public double lampPower = 5.0;
    public double furnacePower = 250.0;
    public double blastFurnacePower = 500.0;
    public double smokerPower = 100.0;
    public boolean electricFurnaces = true;
    public double backupGeneratorPower = 100.0;
    public double centrifugePower = 1000.0;
    public int centrifugeTicks = 1200;
    public int centrifugeDust = 8;
    public double batteryCapacity = 2.0e6;
    public double batteryRate = 1000.0;
    public int wireMaxLength = 24;
    public int wireBlocksPerItem = 3;
    public double wireSagBase = 0.25;
    public double wireSagPerBlock = 0.07;

    public boolean requireResourcePack = true;

    public double machineSoundVolume = 1.0;

    public String meltdownMode = "FULL";
    public float maxExplosionPower = 20.0f;
    public double coriumFraction = 0.35;
    public int radiationRadius = 16;
    public int radiationDurationTicks = 24000;
    public boolean depletedFuelRadiation = true;

    public static NuclearConfig get() {
        return instance;
    }

    public MeltdownMode meltdown() {
        try {
            return MeltdownMode.valueOf(this.meltdownMode.trim().toUpperCase());
        } catch (Exception _) {
            return MeltdownMode.FULL;
        }
    }

    public static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("goingnuclear.json");
    }

    public static void load() {
        Path path = path();
        NuclearConfig loaded = null;
        if (Files.exists(path)) {
            try {
                loaded = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), NuclearConfig.class);
            } catch (Exception e) {
                GoingNuclear.LOGGER.error("Could not read {}, using defaults", path, e);
            }
        }
        instance = loaded != null ? loaded : new NuclearConfig();
        instance.sanitize();
        save();
    }

    public static void save() {
        try {
            Files.createDirectories(path().getParent());
            Files.writeString(path(), GSON.toJson(instance), StandardCharsets.UTF_8);
        } catch (IOException e) {
            GoingNuclear.LOGGER.error("Could not write {}", path(), e);
        }
    }

    private void sanitize() {
        this.minReactorSize = Math.clamp(this.minReactorSize, 3, 32);
        this.maxReactorSize = Math.clamp(this.maxReactorSize, this.minReactorSize, 32);
        this.wireMaxLength = Math.clamp(this.wireMaxLength, 2, 64);
        this.wireBlocksPerItem = Math.max(1, this.wireBlocksPerItem);
        this.batteryRate = Math.max(0.0, this.batteryRate);
        this.wireSagBase = Math.clamp(this.wireSagBase, 0.0, 4.0);
        this.wireSagPerBlock = Math.clamp(this.wireSagPerBlock, 0.0, 0.5);
        this.machineSoundVolume = Math.clamp(this.machineSoundVolume, 0.0, 2.0);
        this.makeUpLevel = Math.clamp(this.makeUpLevel, 0.5, 0.99);
        this.maxExplosionPower = Math.clamp(this.maxExplosionPower, 1.0f, 60.0f);
        this.radiationRadius = Math.clamp(this.radiationRadius, 0, 64);
        this.decayHeatTimeConstantTicks = Math.max(1, this.decayHeatTimeConstantTicks);
        this.xenonWorth = Math.clamp(this.xenonWorth, 0.0, 0.5);
        this.xenonTimeScale = Math.max(0.05, this.xenonTimeScale);
        this.xenonBurnout = Math.max(0.0, this.xenonBurnout);
        this.pipeCapacity = Math.max(1.0, this.pipeCapacity);
        this.checkValveRate = Math.max(1.0, this.checkValveRate);
        this.checkValveTopUp = Math.clamp(this.checkValveTopUp, 0.05, 1.0);
        this.waterOutletRate = Math.max(1.0, this.waterOutletRate);
        this.expansionTankCapacity = Math.max(1000.0, this.expansionTankCapacity);
        this.expansionTankRate = Math.max(1.0, this.expansionTankRate);
        this.steamApproachTemp = Math.max(0.0, this.steamApproachTemp);
        this.steamDesignTemp = Math.max(60.0, this.steamDesignTemp);
        this.steamMaxBonus = Math.max(1.0, this.steamMaxBonus);
        this.condenserRate = Math.max(1.0, this.condenserRate);
        this.steamDumpRate = Math.max(1.0, this.steamDumpRate);
        this.condenserCoolingWater = Math.max(0.0, this.condenserCoolingWater);
        this.turbineSteamLoss = Math.clamp(this.turbineSteamLoss, 0.0, 0.1);
        this.heatExchangerCoolantLoss = Math.clamp(this.heatExchangerCoolantLoss, 0.0, 0.1);
        this.coolantHeatCapacity = Math.max(0.1, this.coolantHeatCapacity);
        this.coreToCoolantTransfer = Math.max(0.0, this.coreToCoolantTransfer);
        this.redstoneRodPower = Math.max(1.0, this.redstoneRodPower);
        this.glowstoneRodPower = Math.max(1.0, this.glowstoneRodPower);
        this.redstoneRodEnergy = Math.max(1.0, this.redstoneRodEnergy);
        this.glowstoneRodEnergy = Math.max(1.0, this.glowstoneRodEnergy);
        this.enrichedRedstoneRodPower = Math.max(1.0, this.enrichedRedstoneRodPower);
        this.enrichedGlowstoneRodPower = Math.max(1.0, this.enrichedGlowstoneRodPower);
        this.enrichedEnergyFactor = Math.max(0.1, this.enrichedEnergyFactor);
        this.centrifugePower = Math.max(0.0, this.centrifugePower);
        this.centrifugeTicks = Math.max(20, this.centrifugeTicks);
        this.centrifugeDust = Math.clamp(this.centrifugeDust, 0, 64);
    }

    public enum MeltdownMode {
        FULL,
        EXPLOSION,
        CONTAINED
    }
}
