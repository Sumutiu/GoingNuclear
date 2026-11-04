package com.sumutiu.goingnuclear.util;

import com.sumutiu.goingnuclear.config.NuclearConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

public final class Sounds {
    private Sounds() {
    }

    public static void toPlayer(Player player, SoundEvent sound, float volume, float pitch) {
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.connection.send(new ClientboundSoundEntityPacket(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound),
                    SoundSource.MASTER, player, volume, pitch, player.getRandom().nextLong()));
        }
    }

    /**
     * Lead sounds, looked up by id because their names in {@link SoundEvents} changed between versions.
     */
    public static SoundEvent leadTied() {
        return vanilla(SoundEvents.STONE_HIT, "item.lead.tied", "entity.leash_knot.place");
    }

    public static SoundEvent leadUntied() {
        return vanilla(SoundEvents.STONE_HIT, "item.lead.untied", "entity.leash_knot.break");
    }

    public static SoundEvent leadBreak() {
        return vanilla(SoundEvents.STONE_HIT, "item.lead.break", "entity.leash_knot.break");
    }

    /**
     * The first vanilla sound that exists among {@code ids} (e.g. "entity.leash_knot.place"), or the fallback.
     */
    public static SoundEvent vanilla(SoundEvent fallback, String... ids) {
        for (String id : ids) {
            SoundEvent sound = lookup(id);
            if (sound != null) return sound;
        }
        return fallback;
    }

    public static void at(ServerLevel level, BlockPos pos, SoundEvent sound, float volume, float pitch) {
        level.playSound(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, sound, SoundSource.BLOCKS, volume, pitch);
    }

    // ---------- Running machines ----------

    /**
     * Sound of a running machine, scaled by {@code machineSoundVolume} in the config (0 = machines are silent).
     * Does nothing when the sound does not exist in this game version.
     */
    public static void machine(ServerLevel level, BlockPos pos, @Nullable SoundEvent sound, float volume, float pitch) {
        double scale = NuclearConfig.get().machineSoundVolume;
        if (sound == null || scale <= 0.0) return;
        at(level, pos, sound, (float) (volume * scale), pitch);
    }

    /**
     * True on one tick out of every {@code period}. The tick differs from block to block, so a row of machines
     * does not play the same sound all at once.
     */
    public static boolean due(ServerLevel level, BlockPos pos, int period, int offset) {
        return Math.floorMod(level.getGameTime() + pos.hashCode() + offset, (long) period) == 0L;
    }

    public static boolean due(ServerLevel level, BlockPos pos, int period) {
        return due(level, pos, period, 0);
    }

    /**
     * Water pump piston: out on one stroke, back in on the next.
     */
    public static @Nullable SoundEvent pumpStroke(boolean out) {
        return out ? lookup("block.piston.extend") : SoundEvents.PISTON_CONTRACT;
    }

    /**
     * Water being drawn in by the water pump.
     */
    public static @Nullable SoundEvent waterGurgle() {
        return lookup("block.water.ambient");
    }

    /**
     * Deep electric motor hum of the circulation pump.
     */
    public static @Nullable SoundEvent motorHum() {
        return lookup("block.conduit.ambient");
    }

    /**
     * Water swirling through the circulation pump.
     */
    public static @Nullable SoundEvent waterSwirl() {
        return lookup("block.bubble_column.whirlpool_ambient");
    }

    /**
     * Turbine rotor and generator whine (played higher than the reactor's hum of the same sound).
     */
    public static SoundEvent rotorWhine() {
        return SoundEvents.BEACON_AMBIENT;
    }

    /**
     * Steam rushing through the turbine.
     */
    public static @Nullable SoundEvent steamHiss() {
        return lookup("block.fire.extinguish");
    }

    /**
     * The Enrichment Centrifuge finishing a rod.
     */
    public static @Nullable SoundEvent enrichmentDone() {
        return lookup("block.respawn_anchor.charge");
    }

    private static @Nullable SoundEvent lookup(String id) {
        return BuiltInRegistries.SOUND_EVENT.getValue(Identifier.fromNamespaceAndPath("minecraft", id));
    }
}
