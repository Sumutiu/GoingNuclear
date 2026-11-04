package com.sumutiu.goingnuclear.machine;

import com.sumutiu.goingnuclear.config.NuclearConfig;
import com.sumutiu.goingnuclear.fluid.FluidAccess;
import com.sumutiu.goingnuclear.fluid.FluidHandler;
import com.sumutiu.goingnuclear.fluid.FluidType;
import com.sumutiu.goingnuclear.fluid.PipeNetwork;
import com.sumutiu.goingnuclear.registry.ModBlockEntities;
import com.sumutiu.goingnuclear.util.Fmt;
import com.sumutiu.goingnuclear.util.InfoProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Moves fluid from the back of the valve to its front each tick: only that way, and only fluid that is offered
 * freely (it does not suck on pump-only outlets). Each valve has its own level (set on its screen, starting at
 * {@code checkValveTopUp}) and one of two modes:
 * <ul>
 *     <li>{@link Mode#TOP_UP}: it passes while the side it feeds (the front) is less full than the level, to top
 *     a loop up from a supply;</li>
 *     <li>{@link Mode#OVERFLOW}: it passes while the side it takes from (the back) is fuller than the level, to
 *     spill what a loop has too much of (into an expansion tank, say).</li>
 * </ul>
 * Next to a pipe line it moves no more than brings that line to the level, so it does not overshoot.
 */
public class CheckValveBlockEntity extends BlockEntity implements MachineBlock.TickingMachine, InfoProvider {
    public static final int MIN_SETTING = 5;
    public static final int MAX_SETTING = 100;

    public enum Mode {
        TOP_UP("Top up", "passes while the side it feeds is below the level"),
        OVERFLOW("Overflow", "passes while the side it takes from is above the level");

        private final String title;
        private final String description;

        Mode(String title, String description) {
            this.title = title;
            this.description = description;
        }

        public String title() {
            return this.title;
        }

        public String description() {
            return this.description;
        }

        public Mode next() {
            return values()[(this.ordinal() + 1) % values().length];
        }
    }

    private Mode mode = Mode.TOP_UP;
    /**
     * The level it works to, in percent.
     */
    private int setting = defaultSetting();
    private double lastFlow;
    private @Nullable FluidType lastType;
    private double lastFill = -1.0;
    private double lastSourceFill = -1.0;

    public CheckValveBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CHECK_VALVE, pos, state);
    }

    private static int defaultSetting() {
        return clampSetting((int) Math.round(NuclearConfig.get().checkValveTopUp * 100.0));
    }

    private static int clampSetting(int percent) {
        return Math.clamp(percent, MIN_SETTING, MAX_SETTING);
    }

    public Mode mode() {
        return this.mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
        this.setChanged();
    }

    public int setting() {
        return this.setting;
    }

    public void setSetting(int percent) {
        this.setting = clampSetting(percent);
        this.setChanged();
    }

    public double lastFlow() {
        return this.lastFlow;
    }

    public @Nullable FluidType lastType() {
        return this.lastType;
    }

    /**
     * How full the front side (the one it feeds) was last tick (0..1), or -1 when it cannot tell.
     */
    public double lastFill() {
        return this.lastFill;
    }

    /**
     * How full the back side (the one it takes from) was last tick (0..1), or -1 when it cannot tell.
     */
    public double lastSourceFill() {
        return this.lastSourceFill;
    }

    public Direction outlet() {
        return this.getBlockState().getValue(CheckValveBlock.FACING);
    }

    @Override
    public void serverTick(ServerLevel level, BlockPos pos, BlockState state) {
        NuclearConfig config = NuclearConfig.get();
        Direction out = state.getValue(CheckValveBlock.FACING);
        Direction in = out.getOpposite();
        this.lastFlow = 0.0;

        FluidHandler source = FluidAccess.get(level, pos.relative(in), out);
        FluidHandler target = FluidAccess.get(level, pos.relative(out), in);
        this.lastFill = target != null ? target.fillLevel(in) : -1.0;
        this.lastSourceFill = source != null ? source.fillLevel(out) : -1.0;
        if (source == null || target == null || source == target) return;
        FluidType type = source.available(out, false);
        if (type == null) return;
        this.lastType = type;
        double wanted = this.setting / 100.0;
        double limit = config.checkValveRate;
        if (this.mode == Mode.TOP_UP) {
            // Stop once the side it feeds is full enough (when it can tell how full it is).
            if (this.lastFill >= wanted) return;
            if (target instanceof PipeNetwork network) limit = Math.min(limit, wanted * network.capacity() - network.amount());
        } else {
            // Only what the side it takes from has above the level (a side that cannot tell gives nothing).
            if (this.lastSourceFill < 0.0 || this.lastSourceFill <= wanted) return;
            if (source instanceof PipeNetwork network) limit = Math.min(limit, network.amount() - wanted * network.capacity());
        }
        if (limit <= 0.0) return;

        // Like any other machine, the valve shares the pipe or machine on each side evenly with the rest.
        BlockPos sourcePos = pos.relative(in);
        BlockPos targetPos = pos.relative(out);
        double room = target.insert(in, type, limit, true);
        room = Math.min(room, FluidAccess.pushAllowance(level, pos, target, targetPos, in, type));
        room = Math.min(room, FluidAccess.pullAllowance(level, pos, source, sourcePos, out, type, false));
        if (room <= 0.0) return;
        double temperature = source.temperature(out, type);
        double moved = source.extract(out, type, room, false, false);
        if (moved <= 0.0) return;
        target.insert(in, type, moved, temperature, false);
        FluidAccess.pulled(level, pos, source, sourcePos, type, false, moved);
        FluidAccess.pushed(level, pos, target, targetPos, type, moved);
        this.lastFlow = moved;
    }

    @Override
    public void appendInfo(List<Component> lines) {
        Direction out = this.outlet();
        lines.add(Fmt.title("Check Valve"));
        lines.add(Fmt.label("Flow", "from " + out.getOpposite().getName() + " to " + out.getName() + " only", ChatFormatting.AQUA));
        lines.add(Fmt.label("Mode", this.mode.title() + " at " + this.setting + "% (" + this.mode.description()
                + "; right-click the valve with an empty hand to set it)", ChatFormatting.WHITE));
        lines.add(Fmt.label("Passing", this.lastFlow > 0.0 && this.lastType != null
                ? Fmt.rate(this.lastFlow) + " of " + this.lastType.displayName().getString() : "nothing", ChatFormatting.WHITE));
        lines.add(Fmt.label("Inlet side (" + out.getOpposite().getName() + ")", fill(this.lastSourceFill) + " full",
                this.mode == Mode.OVERFLOW ? ChatFormatting.GOLD : ChatFormatting.GRAY));
        lines.add(Fmt.label("Outlet side (" + out.getName() + ")", fill(this.lastFill) + " full",
                this.mode == Mode.TOP_UP ? ChatFormatting.GOLD : ChatFormatting.GRAY));
    }

    private static String fill(double fill) {
        return fill < 0.0 ? "unknown" : Fmt.pct(fill);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putInt("mode", this.mode.ordinal());
        output.putInt("setting", this.setting);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        int mode = input.getIntOr("mode", 0);
        this.mode = mode >= 0 && mode < Mode.values().length ? Mode.values()[mode] : Mode.TOP_UP;
        this.setting = clampSetting(input.getIntOr("setting", defaultSetting()));
    }
}
