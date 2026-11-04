package com.sumutiu.goingnuclear.power;

import com.sumutiu.goingnuclear.config.NuclearConfig;
import com.sumutiu.goingnuclear.registry.ModItems;
import com.sumutiu.goingnuclear.util.Sounds;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Wires being carried by players, like a lead: the start point per player and the wire hanging from it
 * to the player's hand. Walking much further than a wire may be long makes it slip out of your hand.
 */
public final class PendingWires {
    private static final long TIMEOUT_TICKS = 20L * 60L * 5L;
    /**
     * Extra distance past the maximum wire length before the carried wire slips out of your hand.
     */
    private static final double SNAP_MARGIN = 8.0;
    private static final Map<UUID, Pending> PENDING = new HashMap<>();

    private PendingWires() {
    }

    public static final class Pending {
        private final ResourceKey<Level> dimension;
        private final BlockPos pos;
        private final long startedAt;
        private @Nullable WirePreview preview;

        private Pending(ResourceKey<Level> dimension, BlockPos pos, long startedAt) {
            this.dimension = dimension;
            this.pos = pos;
            this.startedAt = startedAt;
        }

        public ResourceKey<Level> dimension() {
            return this.dimension;
        }

        public BlockPos pos() {
            return this.pos;
        }

        private void hide() {
            if (this.preview != null) {
                this.preview.remove();
                this.preview = null;
            }
        }
    }

    public static @Nullable Pending get(ServerPlayer player) {
        return PENDING.get(player.getUUID());
    }

    /**
     * Starts carrying a wire from the anchor at {@code pos} (replacing any wire the player was carrying).
     */
    public static void start(ServerPlayer player, ServerLevel level, BlockPos pos) {
        clear(player);
        Pending pending = new Pending(level.dimension(), pos.immutable(), level.getGameTime());
        PENDING.put(player.getUUID(), pending);
        if (holdsWireTool(player)) {
            Vec3 start = WireAnchor.attachPoint(level.getBlockState(pos), pos);
            Vec3 hand = handPosition(player);
            pending.preview = WirePreview.create(level, start, hand, start.distanceTo(hand) > NuclearConfig.get().wireMaxLength);
        }
    }

    public static void clear(ServerPlayer player) {
        Pending pending = PENDING.remove(player.getUUID());
        if (pending != null) pending.hide();
    }

    public static void clearAll() {
        for (Pending pending : PENDING.values()) pending.hide();
        PENDING.clear();
    }

    public static boolean holdsWireTool(ServerPlayer player) {
        return player.getMainHandItem().is(ModItems.COPPER_WIRE) || player.getOffhandItem().is(ModItems.COPPER_WIRE)
                || player.getMainHandItem().is(ModItems.PLIERS) || player.getOffhandItem().is(ModItems.PLIERS);
    }

    /**
     * Roughly where a held item is: in front of the player, at the side of the hand holding the wire.
     */
    public static Vec3 handPosition(ServerPlayer player) {
        boolean mainHand = player.getMainHandItem().is(ModItems.COPPER_WIRE) || player.getMainHandItem().is(ModItems.PLIERS);
        double yaw = Math.toRadians(player.getYRot());
        double side = mainHand ? 1.0 : -1.0;
        // Facing direction (x = -sin(yaw), z = cos(yaw)); the right hand is 90 degrees clockwise of it: (-z, x).
        double forwardX = -Math.sin(yaw);
        double forwardZ = Math.cos(yaw);
        double height = player.isShiftKeyDown() ? 0.75 : 1.0;
        return player.position().add(forwardX * 0.3 - forwardZ * 0.35 * side, height, forwardZ * 0.3 + forwardX * 0.35 * side);
    }

    public static void tick(MinecraftServer server) {
        if (PENDING.isEmpty() || server.getTickCount() % 2 != 0) return;
        boolean message = server.getTickCount() % 10 == 0;
        int maxLength = NuclearConfig.get().wireMaxLength;
        Iterator<Map.Entry<UUID, Pending>> iterator = PENDING.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Pending> entry = iterator.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            Pending pending = entry.getValue();
            if (player == null) {
                pending.hide();
                iterator.remove();
                continue;
            }
            ServerLevel level = player.level();
            if (!level.dimension().equals(pending.dimension()) || !level.isLoaded(pending.pos())) {
                pending.hide();
                iterator.remove();
                continue;
            }
            if (level.getGameTime() - pending.startedAt > TIMEOUT_TICKS) {
                pending.hide();
                iterator.remove();
                player.sendOverlayMessage(Component.literal("Wire dropped (you carried it too long).").withStyle(ChatFormatting.GRAY));
                continue;
            }
            if (!(level.getBlockEntity(pending.pos()) instanceof WireAnchor)) {
                pending.hide();
                iterator.remove();
                player.sendOverlayMessage(Component.literal("The pole or machine your wire hung from is gone.").withStyle(ChatFormatting.GRAY));
                continue;
            }

            Vec3 start = WireAnchor.attachPoint(level.getBlockState(pending.pos()), pending.pos());
            Vec3 hand = handPosition(player);
            double length = start.distanceTo(hand);
            if (length > maxLength + SNAP_MARGIN) {
                pending.hide();
                iterator.remove();
                Sounds.at(level, pending.pos(), Sounds.leadBreak(), 1.0f, 1.0f);
                player.sendOverlayMessage(Component.literal("The wire slipped out of your hand: you walked too far.").withStyle(ChatFormatting.RED));
                continue;
            }

            // Only show the wire while the player holds copper wire or pliers (like a lead in hand).
            boolean tooLong = length > maxLength;
            if (!holdsWireTool(player)) {
                pending.hide();
                continue;
            }
            if (pending.preview == null) {
                pending.preview = WirePreview.create(level, start, hand, tooLong);
            } else {
                pending.preview.update(hand, tooLong);
            }
            if (message) {
                int cost = ElectricBlockEntity.wireCost(length);
                player.sendOverlayMessage(Component.literal(String.format(Locale.ROOT, "Wire: %.1f / %d blocks (%d copper wire)%s", length, maxLength, cost,
                                tooLong ? " - too long, put a pole in between" : ""))
                        .withStyle(tooLong ? ChatFormatting.RED : ChatFormatting.YELLOW));
            }
        }
    }
}
