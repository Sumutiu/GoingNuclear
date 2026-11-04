package com.sumutiu.goingnuclear.reactor;

import com.sumutiu.goingnuclear.GoingNuclear;
import com.sumutiu.goingnuclear.config.NuclearConfig;
import com.sumutiu.goingnuclear.registry.ModBlocks;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Meltdowns are queued by the controller and executed at the end of the level tick
 * (explosions are not safe to run while block entities are being ticked).
 */
public final class Meltdown {
    private static final Map<ServerLevel, List<Event>> QUEUE = new WeakHashMap<>();

    private Meltdown() {
    }

    public record Event(BlockPos controller, BlockPos min, BlockPos max, List<BlockPos> fuel, int activeRods, double power, String cause) {
    }

    public static void queue(ServerLevel level, Event event) {
        QUEUE.computeIfAbsent(level, _ -> new ArrayList<>()).add(event);
    }

    public static void clearAll() {
        QUEUE.clear();
    }

    public static void processQueue(ServerLevel level) {
        List<Event> events = QUEUE.remove(level);
        if (events == null) return;
        for (Event event : events) {
            try {
                execute(level, event);
            } catch (Exception e) {
                GoingNuclear.LOGGER.error("Meltdown processing failed", e);
            }
        }
    }

    private static void execute(ServerLevel level, Event event) {
        NuclearConfig cfg = NuclearConfig.get();
        NuclearConfig.MeltdownMode mode = cfg.meltdown();
        RandomSource random = level.getRandom();
        double cx = (event.min().getX() + event.max().getX()) / 2.0 + 0.5;
        double cy = (event.min().getY() + event.max().getY()) / 2.0 + 0.5;
        double cz = (event.min().getZ() + event.max().getZ()) / 2.0 + 0.5;
        BlockPos center = BlockPos.containing(cx, cy, cz);

        // The fuel is destroyed in every mode.
        for (BlockPos pos : event.fuel()) {
            if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof FuelChannelBlockEntity channel) channel.destroyRod();
        }

        Component message = Component.literal("☢ REACTOR MELTDOWN at " + center.toShortString() + " - " + event.cause())
                .withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD);
        for (ServerPlayer player : level.players()) {
            if (player.blockPosition().closerThan(center, 160.0)) player.sendSystemMessage(message);
        }
        GoingNuclear.LOGGER.warn("Reactor meltdown at {} in {} ({}), mode {}", center.toShortString(), level.dimension(), event.cause(), mode);

        float power = (float) Math.min(cfg.maxExplosionPower,
                4.0 + 1.5 * Math.sqrt(Math.max(1, event.activeRods())) + Math.min(8.0, event.power() / 25000.0));

        switch (mode) {
            case CONTAINED -> {
                level.sendParticles(ParticleTypes.LARGE_SMOKE, cx, cy, cz, 80, 2.0, 2.0, 2.0, 0.05);
                level.sendParticles(ParticleTypes.FLAME, cx, cy, cz, 40, 1.5, 1.5, 1.5, 0.02);
                irradiate(level, cx, cy, cz, 12.0, 200);
            }
            case EXPLOSION -> explode(level, event, cx, cy, cz, power, random);
            case FULL -> {
                explode(level, event, cx, cy, cz, power, random);
                spillCorium(level, event, random, cfg);
                irradiate(level, cx, cy, cz, Math.max(8.0, cfg.radiationRadius), 600);
            }
        }

        if (level.isLoaded(event.controller()) && level.getBlockEntity(event.controller()) instanceof ReactorControllerBlockEntity controller) {
            controller.meltdownProcessed();
        }
    }

    private static void explode(ServerLevel level, Event event, double cx, double cy, double cz, float power, RandomSource random) {
        level.explode(null, cx, cy, cz, power, Level.ExplosionInteraction.TNT);
        List<BlockPos> fuel = event.fuel();
        int extra = Math.min(3, fuel.size() / 4);
        for (int i = 0; i < extra; i++) {
            BlockPos pos = fuel.get(random.nextInt(fuel.size()));
            level.explode(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, power * 0.5f, Level.ExplosionInteraction.TNT);
        }
    }

    private static void spillCorium(ServerLevel level, Event event, RandomSource random, NuclearConfig cfg) {
        BlockPos min = event.min();
        BlockPos max = event.max();
        for (int x = min.getX() + 1; x < max.getX(); x++) {
            for (int z = min.getZ() + 1; z < max.getZ(); z++) {
                for (int y = min.getY(); y < max.getY(); y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level.isLoaded(pos) || !level.getBlockState(pos).isAir()) continue;
                    if (random.nextDouble() < cfg.coriumFraction) {
                        level.setBlock(pos, Blocks.LAVA.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
            }
        }
        BlockPos core = new BlockPos((min.getX() + max.getX()) / 2, min.getY(), (min.getZ() + max.getZ()) / 2);
        if (level.isLoaded(core)) {
            level.setBlock(core, ModBlocks.CORIUM.defaultBlockState(), Block.UPDATE_ALL);
            if (level.getBlockEntity(core) instanceof CoriumBlockEntity corium) {
                corium.configure(cfg.radiationRadius, cfg.radiationDurationTicks);
            }
        }
    }

    private static void irradiate(ServerLevel level, double cx, double cy, double cz, double radius, int duration) {
        AABB box = new AABB(cx - radius, cy - radius, cz - radius, cx + radius, cy + radius, cz + radius);
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, box)) {
            double distance = Math.sqrt(entity.distanceToSqr(cx, cy, cz));
            if (distance > radius) continue;
            if (entity instanceof ServerPlayer player && (player.isCreative() || player.isSpectator())) continue;
            entity.addEffect(new MobEffectInstance(MobEffects.WITHER, duration / 2, distance < radius / 2.0 ? 1 : 0));
            entity.addEffect(new MobEffectInstance(MobEffects.POISON, duration, 1));
            entity.addEffect(new MobEffectInstance(MobEffects.HUNGER, duration, 1));
            entity.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, duration, 1));
        }
    }
}
