package com.sumutiu.goingnuclear.reactor;

import com.sumutiu.goingnuclear.config.NuclearConfig;
import com.sumutiu.goingnuclear.machine.MachineBlock;
import com.sumutiu.goingnuclear.registry.ModBlockEntities;
import com.sumutiu.goingnuclear.util.Fmt;
import com.sumutiu.goingnuclear.util.InfoProvider;
import com.sumutiu.goingnuclear.util.RadiationHandler;
import com.sumutiu.goingnuclear.util.TemperatureSource;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public class CoriumBlockEntity extends BlockEntity implements MachineBlock.TickingMachine, InfoProvider, TemperatureSource {
    private int radius = NuclearConfig.get().radiationRadius;
    private int remaining = NuclearConfig.get().radiationDurationTicks;
    private int total = NuclearConfig.get().radiationDurationTicks;

    public CoriumBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CORIUM, pos, state);
    }

    public void configure(int radius, int duration) {
        this.radius = radius;
        this.remaining = duration;
        this.total = Math.max(1, duration);
        this.setChanged();
    }

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        if (level instanceof ServerLevel serverLevel) RadiationHandler.trackCorium(serverLevel, this.getBlockPos());
    }

    @Override
    public void setRemoved() {
        if (this.level instanceof ServerLevel serverLevel) RadiationHandler.untrackCorium(serverLevel, this.getBlockPos());
        super.setRemoved();
    }

    private double strength() {
        return this.remaining / (double) Math.max(1, this.total);
    }

    /**
     * Unprotected dose rate (0..1) at a point: strongest on top of the corium, fading out at the radius.
     */
    public double doseAt(Vec3 point) {
        if (this.radius <= 0) return 0.0;
        double distance = Math.sqrt(point.distanceToSqr(Vec3.atCenterOf(this.getBlockPos())));
        if (distance > this.radius) return 0.0;
        return (1.0 - distance / this.radius) * this.strength();
    }

    @Override
    public double temperature() {
        return 300.0 + 2200.0 * this.remaining / (double) Math.max(1, this.total);
    }

    @Override
    public void serverTick(ServerLevel level, BlockPos pos, BlockState state) {
        long time = level.getGameTime();
        if (time % 10L == 0L) {
            level.sendParticles(ParticleTypes.LARGE_SMOKE, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 2, 0.3, 0.2, 0.3, 0.02);
        }
        if (time % 20L != 0L) return;
        this.remaining -= 20;
        if (this.remaining <= 0) {
            level.setBlock(pos, Blocks.OBSIDIAN.defaultBlockState(), Block.UPDATE_ALL);
            return;
        }
        this.setChanged();
        if (this.radius <= 0) return;

        double strength = this.strength();
        double cx = pos.getX() + 0.5;
        double cy = pos.getY() + 0.5;
        double cz = pos.getZ() + 0.5;
        double r = this.radius;
        AABB box = new AABB(cx - r, cy - r, cz - r, cx + r, cy + r, cz + r);
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, box)) {
            double distance = Math.sqrt(entity.distanceToSqr(cx, cy, cz));
            if (distance > r) continue;
            double protection = RadiationHandler.protection(entity);
            if (entity instanceof ServerPlayer player) {
                if (player.isCreative() || player.isSpectator()) continue;
                player.sendOverlayMessage(protection >= 1.0
                        ? Component.literal("☢ Radiation: your hazmat suit is protecting you").withStyle(ChatFormatting.YELLOW)
                        : Component.literal("☢ RADIATION - get away from the corium!").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
            }
            double dose = (1.0 - distance / r) * strength * (1.0 - protection);
            if (dose > 0.01) {
                entity.addEffect(new MobEffectInstance(MobEffects.POISON, 100, dose > 0.5 ? 1 : 0));
                entity.addEffect(new MobEffectInstance(MobEffects.HUNGER, 100, 0));
                if (dose > 0.6) entity.addEffect(new MobEffectInstance(MobEffects.WITHER, 60, 0));
            }
            // The heat burns whatever you wear.
            if (distance < 3.0) entity.hurtServer(level, level.damageSources().magic(), (float) (2.0 + 4.0 * strength));
        }
    }

    @Override
    public void appendInfo(List<Component> lines) {
        lines.add(Fmt.title("Corium"));
        lines.add(Fmt.label("Radiation radius", this.radius + " blocks", ChatFormatting.RED));
        lines.add(Fmt.label("Cools down in", Fmt.num(this.remaining / 20.0 / 60.0, 1) + " min", ChatFormatting.GOLD));
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putInt("radius", this.radius);
        output.putInt("remaining", this.remaining);
        output.putInt("total", this.total);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.radius = input.getIntOr("radius", NuclearConfig.get().radiationRadius);
        this.remaining = input.getIntOr("remaining", NuclearConfig.get().radiationDurationTicks);
        this.total = input.getIntOr("total", Math.max(1, NuclearConfig.get().radiationDurationTicks));
    }
}
