package com.sumutiu.goingnuclear;

import com.sumutiu.goingnuclear.command.NuclearCommands;
import com.sumutiu.goingnuclear.config.NuclearConfig;
import com.sumutiu.goingnuclear.fluid.PipeNetworks;
import com.sumutiu.goingnuclear.fluid.TankGroups;
import com.sumutiu.goingnuclear.power.PendingWires;
import com.sumutiu.goingnuclear.power.PowerGrid;
import com.sumutiu.goingnuclear.reactor.Meltdown;
import com.sumutiu.goingnuclear.reactor.ReactorRegistry;
import com.sumutiu.goingnuclear.registry.ModBlockEntities;
import com.sumutiu.goingnuclear.registry.ModBlocks;
import com.sumutiu.goingnuclear.registry.ModItems;
import com.sumutiu.goingnuclear.util.RadiationHandler;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Going Nuclear: server-side nuclear power for Fabric (Polymer based, vanilla clients need nothing).
 */
public class GoingNuclear implements ModInitializer {
    public static final String MOD_ID = "goingnuclear";
    public static final Logger LOGGER = LoggerFactory.getLogger("GoingNuclear");

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        ModBlocks.init();
        ModBlockEntities.init();
        ModItems.init();

        // Textures and models for players who accept the server resource pack.
        PolymerResourcePackUtils.addModAssets(MOD_ID);

        // Every world (seed) has its own config, read once the world exists and before the server first ticks.
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            NuclearConfig.load(server.getWorldGenSettings().options().seed());
            if (NuclearConfig.get().requireResourcePack) {
                // Servers using Polymer's auto-host then ask every player to accept the pack (declining disconnects).
                PolymerResourcePackUtils.markAsRequired();
            }
        });

        ServerTickEvents.END_LEVEL_TICK.register(level -> {
            PipeNetworks.tickAll(level);
            TankGroups.tickAll(level);
            PowerGrid.tick(level);
            Meltdown.processQueue(level);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            PendingWires.tick(server);
            RadiationHandler.tick(server);
        });

        // Vanilla furnaces join or leave the grid when they are placed, broken, loaded or unloaded.
        ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register((blockEntity, level) -> {
            if (blockEntity instanceof AbstractFurnaceBlockEntity) PowerGrid.of(level).markDirty();
        });
        ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((blockEntity, level) -> {
            if (blockEntity instanceof AbstractFurnaceBlockEntity) PowerGrid.of(level).markDirty();
        });

        ServerLifecycleEvents.SERVER_STOPPED.register(_ -> {
            NuclearConfig.unload();
            PipeNetworks.clearAll();
            TankGroups.clearAll();
            PowerGrid.clearAll();
            ReactorRegistry.clearAll();
            Meltdown.clearAll();
            PendingWires.clearAll();
            RadiationHandler.clearAll();
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, _, _) -> NuclearCommands.register(dispatcher));

        LOGGER.info("Going Nuclear loaded. Stay below 1200 °C.");
    }
}
