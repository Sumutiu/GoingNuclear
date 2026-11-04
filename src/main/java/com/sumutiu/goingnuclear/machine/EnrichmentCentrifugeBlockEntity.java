package com.sumutiu.goingnuclear.machine;

import com.sumutiu.goingnuclear.config.NuclearConfig;
import com.sumutiu.goingnuclear.power.ElectricBlockEntity;
import com.sumutiu.goingnuclear.registry.ModBlockEntities;
import com.sumutiu.goingnuclear.registry.ModItems;
import com.sumutiu.goingnuclear.util.Fmt;
import com.sumutiu.goingnuclear.util.InfoProvider;
import com.sumutiu.goingnuclear.util.Sounds;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Enriches spent fuel: one depleted redstone rod and some redstone dust spin into an enriched redstone rod, and
 * the same for glowstone. Plain Depleted Fuel Rods (spent enriched fuel) are not taken: fuel is enriched once.
 * <p>
 * It holds up to {@link #ROD_ROOM} depleted rods and {@link #DUST_ROOM} dust of each kind, and up to
 * {@link #ROD_ROOM} finished rods of each kind; it stops while the finished rods are not collected. Each rod
 * takes {@code centrifugeTicks} ticks at {@code centrifugePower} J/t (it slows down on too little power), and
 * the rod and the dust are used up when it is done. Redstone rods go first when both kinds are waiting. A
 * redstone signal stops it. Power in: the back.
 */
public class EnrichmentCentrifugeBlockEntity extends ElectricBlockEntity implements MachineBlock.TickingMachine, InfoProvider {
    public static final int ROD_ROOM = 16;
    public static final int DUST_ROOM = 64;

    /**
     * One recipe: the depleted rod, the dust it takes, and the enriched rod it gives.
     */
    public enum Kind {
        REDSTONE("Redstone", "redstone", ChatFormatting.RED),
        GLOWSTONE("Glowstone", "glowstone", ChatFormatting.GOLD);

        private final String title;
        private final String word;
        private final ChatFormatting color;

        Kind(String title, String word, ChatFormatting color) {
            this.title = title;
            this.word = word;
            this.color = color;
        }

        public String title() {
            return this.title;
        }

        /**
         * The kind in lower case, for sentences and save keys.
         */
        public String word() {
            return this.word;
        }

        public ChatFormatting color() {
            return this.color;
        }

        public Item depleted() {
            return this == REDSTONE ? ModItems.DEPLETED_REDSTONE_FUEL_ROD : ModItems.DEPLETED_GLOWSTONE_FUEL_ROD;
        }

        public Item dust() {
            return this == REDSTONE ? Items.REDSTONE : Items.GLOWSTONE_DUST;
        }

        public Item enriched() {
            return this == REDSTONE ? ModItems.ENRICHED_REDSTONE_FUEL_ROD : ModItems.ENRICHED_GLOWSTONE_FUEL_ROD;
        }
    }

    /**
     * What a stack goes into: the depleted rods or the dust of a kind.
     */
    public enum Input {
        RODS, DUST
    }

    private final int[] rods = new int[Kind.values().length];
    private final int[] dust = new int[Kind.values().length];
    private final int[] done = new int[Kind.values().length];
    private @Nullable Kind job;
    private double progress;
    private boolean switchedOff;
    private boolean spinning;

    public EnrichmentCentrifugeBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ENRICHMENT_CENTRIFUGE, pos, state);
    }

    // ---------- Contents ----------

    /**
     * Which kind and input a stack belongs in, or null when the centrifuge does not take it.
     */
    public static @Nullable Slot slotFor(ItemStack stack) {
        for (Kind kind : Kind.values()) {
            if (stack.is(kind.depleted())) return new Slot(kind, Input.RODS);
            if (stack.is(kind.dust())) return new Slot(kind, Input.DUST);
        }
        return null;
    }

    public record Slot(Kind kind, Input input) {
    }

    public int rods(Kind kind) {
        return this.rods[kind.ordinal()];
    }

    public int dust(Kind kind) {
        return this.dust[kind.ordinal()];
    }

    public int done(Kind kind) {
        return this.done[kind.ordinal()];
    }

    public static int room(Input input) {
        return input == Input.RODS ? ROD_ROOM : DUST_ROOM;
    }

    private int[] counts(Input input) {
        return input == Input.RODS ? this.rods : this.dust;
    }

    /**
     * Moves as much of {@code stack} as fits into the centrifuge.
     *
     * @return number of items taken
     */
    public int insert(ItemStack stack, boolean consume) {
        Slot slot = slotFor(stack);
        if (slot == null) return 0;
        int[] counts = this.counts(slot.input());
        int i = slot.kind().ordinal();
        int moved = Math.min(stack.getCount(), room(slot.input()) - counts[i]);
        if (moved <= 0) return 0;
        counts[i] += moved;
        if (consume) stack.shrink(moved);
        this.setChanged();
        return moved;
    }

    /**
     * Takes every waiting rod or all the dust of a kind back out.
     */
    public ItemStack takeInput(Kind kind, Input input) {
        int[] counts = this.counts(input);
        int count = counts[kind.ordinal()];
        if (count <= 0) return ItemStack.EMPTY;
        counts[kind.ordinal()] = 0;
        this.setChanged();
        return new ItemStack(input == Input.RODS ? kind.depleted() : kind.dust(), count);
    }

    /**
     * Takes the finished rods of a kind.
     */
    public ItemStack takeDone(Kind kind) {
        int count = this.done[kind.ordinal()];
        if (count <= 0) return ItemStack.EMPTY;
        this.done[kind.ordinal()] = 0;
        this.setChanged();
        return new ItemStack(kind.enriched(), count);
    }

    // ---------- Running ----------

    /**
     * Whether a rod of this kind can be started (or carried on) now.
     */
    private boolean canRun(Kind kind) {
        int i = kind.ordinal();
        return this.rods[i] > 0 && this.dust[i] >= NuclearConfig.get().centrifugeDust && this.done[i] < ROD_ROOM;
    }

    public @Nullable Kind job() {
        return this.job;
    }

    /**
     * How far the rod being enriched is (0..1).
     */
    public double progress() {
        return Math.min(1.0, this.progress / NuclearConfig.get().centrifugeTicks);
    }

    public boolean isSpinning() {
        return this.spinning;
    }

    public boolean isSwitchedOff() {
        return this.switchedOff;
    }

    /**
     * Why the centrifuge is not running, or null while it runs.
     */
    public @Nullable String waitingFor() {
        if (this.switchedOff) return "switched off (redstone)";
        if (this.job != null) return this.spinning ? null : "no power";
        NuclearConfig config = NuclearConfig.get();
        boolean anyRods = false;
        for (Kind kind : Kind.values()) {
            int i = kind.ordinal();
            if (this.rods[i] <= 0) continue;
            anyRods = true;
            if (this.done[i] >= ROD_ROOM) return "full: take out the enriched " + kind.word() + " rods";
            if (this.dust[i] < config.centrifugeDust) return "needs " + config.centrifugeDust + " " + kind.word() + " dust per rod";
        }
        return anyRods ? "waiting" : "no depleted rods";
    }

    @Override
    public void serverTick(ServerLevel level, BlockPos pos, BlockState state) {
        this.switchedOff = level.hasNeighborSignal(pos);
        if (this.job != null && !this.canRun(this.job)) {
            // The rod or the dust was taken out, or the output filled up: start over.
            this.job = null;
            this.progress = 0.0;
            this.setChanged();
        }
        if (this.job == null && !this.switchedOff) {
            for (Kind kind : Kind.values()) {
                if (this.canRun(kind)) {
                    this.job = kind;
                    this.progress = 0.0;
                    this.setChanged();
                    break;
                }
            }
        }
        // The power the grid gave last tick drives the drum: on too little it spins slower.
        double power = this.job != null && !this.switchedOff ? this.powerFraction : 0.0;
        this.spinning = power > 0.05;
        if (this.spinning) {
            this.progress += power;
            if (this.progress >= NuclearConfig.get().centrifugeTicks) this.finish(level, pos);
            this.effects(level, pos);
        }
        if (state.getValue(EnrichmentCentrifugeBlock.SPINNING) != this.spinning) {
            level.setBlock(pos, state.setValue(EnrichmentCentrifugeBlock.SPINNING, this.spinning), Block.UPDATE_ALL);
        }
        if (level.getGameTime() % 20L == 0L) this.setChanged();
    }

    private void finish(ServerLevel level, BlockPos pos) {
        Kind kind = this.job;
        if (kind == null) return;
        int i = kind.ordinal();
        this.rods[i]--;
        this.dust[i] -= NuclearConfig.get().centrifugeDust;
        this.done[i]++;
        this.job = null;
        this.progress = 0.0;
        this.setChanged();
        Sounds.machine(level, pos, Sounds.enrichmentDone(), 0.8f, 1.2f);
        level.sendParticles(ParticleTypes.END_ROD, pos.getX() + 0.5, pos.getY() + 1.05, pos.getZ() + 0.5, 8, 0.2, 0.1, 0.2, 0.03);
    }

    private void effects(ServerLevel level, BlockPos pos) {
        if (Sounds.due(level, pos, 30)) Sounds.machine(level, pos, Sounds.rotorWhine(), 0.35f, 2.0f);
        if (Sounds.due(level, pos, 40, 20)) Sounds.machine(level, pos, Sounds.motorHum(), 0.5f, 1.6f);
        if (Sounds.due(level, pos, 10)) {
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, pos.getX() + 0.5, pos.getY() + 1.05, pos.getZ() + 0.5, 1, 0.25, 0.0, 0.25, 0.0);
        }
    }

    @Override
    public double powerDemand() {
        return this.job != null && !this.switchedOff ? NuclearConfig.get().centrifugePower : 0.0;
    }

    @Override
    public void receivePower(double fraction) {
        super.receivePower(this.job != null && !this.switchedOff ? fraction : 0.0);
    }

    // ---------- Removal, probe, saving ----------

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState oldState) {
        super.preRemoveSideEffects(pos, oldState);
        if (this.level == null) return;
        for (Kind kind : Kind.values()) {
            drop(pos, this.takeInput(kind, Input.RODS));
            drop(pos, this.takeInput(kind, Input.DUST));
            drop(pos, this.takeDone(kind));
        }
    }

    private void drop(BlockPos pos, ItemStack stack) {
        if (this.level == null) return;
        // Dust can come out as more than one stack.
        while (!stack.isEmpty()) {
            ItemStack part = stack.split(stack.getMaxStackSize());
            Containers.dropItemStack(this.level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, part);
        }
    }

    @Override
    public void appendInfo(List<Component> lines) {
        NuclearConfig config = NuclearConfig.get();
        lines.add(Fmt.title("Enrichment Centrifuge"));
        String waiting = this.waitingFor();
        Kind kind = this.job;
        if (waiting == null && kind != null) {
            lines.add(Fmt.label("Status", "enriching a " + kind.word() + " rod, " + Fmt.pct(this.progress()), ChatFormatting.GREEN));
        } else {
            lines.add(Fmt.label("Status", waiting != null ? waiting : "waiting", ChatFormatting.YELLOW));
        }
        lines.add(Fmt.label("Consumption", Fmt.power(this.spinning ? config.centrifugePower * this.powerFraction : 0.0)
                + " (needs " + Fmt.power(config.centrifugePower) + ")", ChatFormatting.WHITE));
        for (Kind k : Kind.values()) {
            lines.add(Fmt.label(k.title(), this.rods(k) + " depleted, " + this.dust(k) + " dust, " + this.done(k) + " enriched", k.color()));
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        for (Kind kind : Kind.values()) {
            String name = kind.word();
            output.putInt(name + "_rods", this.rods[kind.ordinal()]);
            output.putInt(name + "_dust", this.dust[kind.ordinal()]);
            output.putInt(name + "_done", this.done[kind.ordinal()]);
        }
        output.putInt("job", this.job != null ? this.job.ordinal() + 1 : 0);
        output.putDouble("progress", this.progress);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        for (Kind kind : Kind.values()) {
            String name = kind.word();
            this.rods[kind.ordinal()] = Math.clamp(input.getIntOr(name + "_rods", 0), 0, ROD_ROOM);
            this.dust[kind.ordinal()] = Math.clamp(input.getIntOr(name + "_dust", 0), 0, DUST_ROOM);
            this.done[kind.ordinal()] = Math.clamp(input.getIntOr(name + "_done", 0), 0, ROD_ROOM);
        }
        int job = input.getIntOr("job", 0);
        this.job = job >= 1 && job <= Kind.values().length ? Kind.values()[job - 1] : null;
        this.progress = Math.max(0.0, input.getDoubleOr("progress", 0.0));
    }
}
