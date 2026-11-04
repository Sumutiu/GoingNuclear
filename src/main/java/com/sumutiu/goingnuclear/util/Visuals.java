package com.sumutiu.goingnuclear.util;

import com.sumutiu.goingnuclear.GoingNuclear;
import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.PolymerBlockModel;
import eu.pb4.polymer.blocks.api.PolymerBlockResourceUtils;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Helpers for choosing between the resource-pack look (custom textures) and the vanilla look-alike
 * that players without the server resource pack get.
 */
public final class Visuals {
    private static final Map<PolymerBlockModel, @Nullable BlockState> REQUESTED = new HashMap<>();
    /**
     * Display elements only sent to players with the resource pack.
     */
    public static final Predicate<ServerPlayer> WITH_PACK = Visuals::hasPack;
    /**
     * Display elements only sent to players without the resource pack.
     */
    public static final Predicate<ServerPlayer> WITHOUT_PACK = player -> !hasPack(player);

    private Visuals() {
    }

    /**
     * True when this player accepted the server resource pack. When unsure, answers false so the player
     * gets the vanilla look, which always works.
     */
    public static boolean hasPack(ServerPlayer player) {
        try {
            return PolymerResourcePackUtils.hasMainPack(player.connection);
        } catch (Throwable _) {
            return false;
        }
    }

    /**
     * An item stack that renders one of our resource pack models ({@code assets/goingnuclear/items/<path>.json}),
     * for item display entities shown to players with the pack.
     */
    public static ItemStack modelStack(String path) {
        ItemStack stack = new ItemStack(Items.TRIAL_KEY);
        stack.set(DataComponents.ITEM_MODEL, GoingNuclear.id(path));
        return stack;
    }

    /**
     * True when the player behind this packet context accepted the server resource pack.
     * A missing context (used by Polymer for light calculations) counts as "has pack".
     */
    public static boolean hasPack(@Nullable PacketContext context) {
        if (context == null) return true;
        try {
            return PolymerResourcePackUtils.hasMainPack(context);
        } catch (Throwable _) {
            return true;
        }
    }

    /**
     * Client block state that shows {@code model}. Several states of a block that look the same (a pump's
     * power-port side only matters while it stands upright) share one, so they do not use up extra states.
     */
    public static @Nullable BlockState request(PolymerBlockModel model) {
        if (REQUESTED.containsKey(model)) return REQUESTED.get(model);
        BlockState state;
        try {
            state = PolymerBlockResourceUtils.requestBlock(BlockModelType.FULL_BLOCK, model);
        } catch (Throwable e) {
            GoingNuclear.LOGGER.warn("Could not allocate a textured block state for {}", model.model(), e);
            state = null;
        }
        REQUESTED.put(model, state);
        return state;
    }

    public static PolymerBlockModel model(String path) {
        return PolymerBlockModel.of(GoingNuclear.id("block/" + path));
    }

    /**
     * Model of a pump with a six-way facing: the base model turned around for the horizontal facings, and
     * separate {@code _up} / {@code _down} models for the vertical ones (power-in port on their north side),
     * turned so that the port faces {@code port}. Only quarter turns around the vertical axis are used, so every
     * face sits exactly where the code expects it.
     */
    public static PolymerBlockModel sixWay(String path, Direction facing, Direction port) {
        return switch (facing) {
            case UP -> facing(path + "_up", port);
            case DOWN -> facing(path + "_down", port);
            default -> facing(path, facing);
        };
    }

    /**
     * Model rotated so that its north face (the "front" in our models) points to {@code facing}.
     */
    public static PolymerBlockModel facing(String path, Direction facing) {
        int x = 0;
        int y = 0;
        switch (facing) {
            case EAST -> y = 90;
            case SOUTH -> y = 180;
            case WEST -> y = 270;
            case UP -> x = 270;
            case DOWN -> x = 90;
        }
        return PolymerBlockModel.of(GoingNuclear.id("block/" + path), x, y);
    }
}
