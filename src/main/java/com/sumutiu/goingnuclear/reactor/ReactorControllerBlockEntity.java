package com.sumutiu.goingnuclear.reactor;

import com.sumutiu.goingnuclear.config.NuclearConfig;
import com.sumutiu.goingnuclear.fluid.FluidAccess;
import com.sumutiu.goingnuclear.fluid.FluidHandler;
import com.sumutiu.goingnuclear.fluid.FluidTank;
import com.sumutiu.goingnuclear.fluid.FluidType;
import com.sumutiu.goingnuclear.fluid.Steam;
import com.sumutiu.goingnuclear.machine.MachineBlock;
import com.sumutiu.goingnuclear.registry.ModBlockEntities;
import com.sumutiu.goingnuclear.registry.ModItems;
import com.sumutiu.goingnuclear.util.Fmt;
import com.sumutiu.goingnuclear.util.InfoProvider;
import com.sumutiu.goingnuclear.util.PlayerItems;
import com.sumutiu.goingnuclear.util.Sounds;
import com.sumutiu.goingnuclear.util.TemperatureSource;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Brain of a reactor: validates the multiblock, simulates neutronics, heat, coolant and steam,
 * drives the ports, and triggers a meltdown when things go wrong.
 * <p>
 * Model summary (per tick, energy in J):
 * <ul>
 *     <li>Each active fuel rod's multiplication k = (1 + coupling from fuel within 2 blocks) x moderation (water level)
 *     x Doppler feedback (temperature) x (1 - control rod absorption). Its power = base power x k^2.</li>
 *     <li>Reactor power follows that target with a short delay; decay heat (6% of power, 30 s time constant)
 *     keeps heating the core after a shutdown.</li>
 *     <li>Boiling mode turns water into steam inside the core. Pressurized mode heats the coolant in the vessel
 *     towards the core temperature (faster with a bigger difference); the coolant flows out through the OUTPUT
 *     ports at its own temperature, through the heat exchangers, and back through the INPUT ports. Heat goes
 *     both ways: coolant coming back hotter than the core warms it.</li>
 *     <li>Above the fuel damage temperature, or with too much steam pressure, integrity drops. At 0%: meltdown.</li>
 * </ul>
 * The control rods are moved by hand, or automatically ({@link ControlMode}): holding a core temperature,
 * holding a power level, or following a redstone signal.
 */
public class ReactorControllerBlockEntity extends BlockEntity implements MachineBlock.TickingMachine, InfoProvider, TemperatureSource {
    /**
     * How fast the rod drives move, in % per tick (a full stroke takes 5 seconds).
     */
    private static final double ROD_SPEED = 1.0;
    /**
     * Automatic control: ticks over which a temperature difference is corrected, the gain (% insertion per
     * tick per unit of relative power error) and the largest correction per tick.
     */
    private static final double AUTO_RESPONSE_TICKS = 200.0;
    private static final double AUTO_GAIN = 3.0;
    private static final double AUTO_MAX_STEP = 0.5;
    /**
     * Pressurized mode: a vessel at least this full whose coolant cannot leave for {@link #SOLID_TICKS}
     * while the core is above the warning temperature means the closed loop is filled to the brim and cannot
     * circulate. The relief valve then lets coolant out until the vessel is down to {@link #VENT_DOWN_TO}, which
     * leaves the loop room to move again.
     */
    private static final double SOLID_LEVEL = 0.999;
    private static final int SOLID_TICKS = 100;
    private static final double VENT_DOWN_TO = 0.95;

    // ---------- structure ----------
    private boolean formed;
    private Component status = Component.literal("Not scanned yet").withStyle(ChatFormatting.GRAY);
    private BlockPos min = BlockPos.ZERO;
    private BlockPos max = BlockPos.ZERO;
    private int interior;
    private int shell;
    private final List<BlockPos> fuel = new ArrayList<>();
    private final List<BlockPos> control = new ArrayList<>();
    private final List<BlockPos> inputs = new ArrayList<>();
    private final List<BlockPos> outputs = new ArrayList<>();
    private final Map<BlockPos, Direction> portOutside = new HashMap<>();
    private int[] tier = new int[0];
    private double[] used = new double[0];
    private double[] rodPower = new double[0];
    private int[][] fuelNeighbors = new int[0][];
    private double[][] fuelWeights = new double[0][];
    private int[][] controlNeighbors = new int[0][];
    private double[][] controlWeights = new double[0][];
    private boolean[] controlHasRod = new boolean[0];
    /**
     * Per control channel: how many channels of its stack are above it, and how tall the stack is.
     * The rods fill a stack from the top (see {@link ControlRodChannelBlock#insertionInStack}).
     */
    private int[] controlFromTop = new int[0];
    private int[] controlStack = new int[0];
    private double[] controlIn = new double[0];
    private final Map<BlockPos, Integer> controlSlots = new HashMap<>();
    private boolean structureDirty = true;
    private int dirtyDelay;
    private int validateTimer;
    private boolean paused;

    // ---------- simulation state (saved) ----------
    private double temperature = 20.0;
    private double flux;
    private double decayHeat;
    private double integrity = 100.0;
    private double insertion = 100.0;
    private boolean scrammed = true;
    private boolean pressurized;
    private boolean autoScram = true;
    private double scramTemp = NuclearConfig.get().defaultScramTemp;
    private boolean reliefValve = true;
    private ControlMode controlMode = ControlMode.MANUAL;
    private double targetTemp = 400.0;
    private double targetPower = 50.0;
    private final FluidTank water = new FluidTank(FluidType.WATER, 0.0);
    private final FluidTank steam = new FluidTank(FluidType.STEAM, 0.0);
    private final Xenon xenon = new Xenon();

    // ---------- statistics (not saved) ----------
    private double lastThermal;
    private double lastRemoved;
    private double lastLoss;
    private double lastWaterIn;
    private double lastOutFlow;
    private double lastVented;
    private double lastMade;
    private int solidTicks;
    private boolean venting;
    private double lastFissionTarget;
    private double lastK;
    private int activeRods;
    private boolean lastRedstone;
    private boolean meltdownQueued;
    private String lastScramReason = "";

    public ReactorControllerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.REACTOR_CONTROLLER, pos, state);
    }

    // =====================================================================================
    // Accessors used by the GUI, probe and sensors
    // =====================================================================================

    public boolean isFormed() {
        return this.formed;
    }

    public Component status() {
        return this.status;
    }

    @Override
    public double temperature() {
        return this.temperature;
    }

    public double thermalPower() {
        return this.lastThermal;
    }

    public double decayHeat() {
        return this.decayHeat;
    }

    /**
     * Xenon poisoning of the core (see {@link Xenon}).
     */
    public Xenon xenon() {
        return this.xenon;
    }

    public double heatRemoved() {
        return this.lastRemoved;
    }

    public double heatLoss() {
        return this.lastLoss;
    }

    public double integrity() {
        return this.integrity;
    }

    public double insertion() {
        return this.insertion;
    }

    /**
     * Fission power relative to the loaded fuel's base power (0 = shut down, about 1 = nominal).
     * Only used for visuals such as the glow of the fuel rods.
     */
    public double powerLevel() {
        double base = this.nominalPower();
        return base > 0.0 ? this.flux / base : 0.0;
    }

    /**
     * How far the control rods are actually in (0..1); a SCRAM drives them in completely.
     */
    public double effectiveInsertion() {
        return this.scrammed ? 1.0 : this.insertion / 100.0;
    }

    /**
     * How far the rods are into the control rod channel at {@code pos} (0..1). Stacked channels fill from the
     * top down: half inserted, a stack of two has its upper rod fully in and its lower rod fully out.
     */
    public double insertionAt(BlockPos pos) {
        double inserted = this.effectiveInsertion();
        Integer slot = this.controlSlots.get(pos);
        if (slot == null || slot >= this.controlStack.length) return inserted;
        return ControlRodChannelBlock.insertionInStack(inserted, this.controlFromTop[slot], this.controlStack[slot]);
    }

    public boolean isScrammed() {
        return this.scrammed;
    }

    public String scramReason() {
        return this.lastScramReason;
    }

    public boolean isPressurized() {
        return this.pressurized;
    }

    public boolean autoScram() {
        return this.autoScram;
    }

    public double scramTemp() {
        return this.scramTemp;
    }

    public boolean reliefValve() {
        return this.reliefValve;
    }

    public ControlMode controlMode() {
        return this.controlMode;
    }

    public double targetTemp() {
        return this.targetTemp;
    }

    /**
     * Target fission power in % of the loaded fuel's nominal power (POWER mode).
     */
    public double targetPower() {
        return this.targetPower;
    }

    /**
     * Nominal power of the loaded fuel (J/t): what the rods give at multiplication 1.
     */
    public double nominalPower() {
        double base = 0.0;
        for (int t : this.tier) {
            if (FuelRods.isActive(t)) base += FuelRods.basePower(t);
        }
        return base;
    }

    /**
     * Steam made in the core last tick (boiling mode), in mB. Pressurized mode makes no steam in the core; see
     * {@link #heatRemoved()} for the heat the coolant took.
     */
    public double made() {
        return this.lastMade;
    }

    /**
     * How full the steam space is (boiling mode). Above 95% the relief valve vents, above 100% the vessel
     * gets damaged and above 150% it ruptures.
     */
    public double steamFill() {
        return this.steam.capacity() > 0.0 ? this.steam.amount() / this.steam.capacity() : 0.0;
    }

    /**
     * Liquid in the vessel (in pressurized mode: the coolant, whatever its temperature).
     */
    public double coolantInVessel() {
        return this.water.amount();
    }

    /**
     * Temperature of the coolant in the vessel (pressurized mode), which is what leaves through the OUTPUT ports.
     */
    public double coolantTemperature() {
        return this.water.temperature();
    }

    public double vesselCapacity() {
        return this.interior * NuclearConfig.get().coolantPerInteriorBlock;
    }

    /**
     * How full the vessel is with liquid coolant (0..1).
     */
    public double coolantLevel() {
        double vessel = this.vesselCapacity();
        return vessel > 0.0 ? Math.min(1.0, this.coolantInVessel() / vessel) : 0.0;
    }

    /**
     * How much fresh (make-up) water the reactor takes right now. Boiling mode: as much as fits, since the
     * water boils away. Pressurized mode: only enough to bring the vessel up to {@code makeUpLevel}; the rest
     * of the room is for the coolant coming back from the heat exchangers. A closed loop fed with make-up
     * water can then never be overfilled, however the water is piped in.
     */
    public double makeUpRoom() {
        if (!this.pressurized) return this.water.space();
        double wanted = this.vesselCapacity() * NuclearConfig.get().makeUpLevel - this.coolantInVessel();
        return Math.clamp(wanted, 0.0, this.water.space());
    }

    /**
     * True while the relief valve is letting coolant out of an overfilled pressurized loop.
     */
    public boolean isVentingCoolant() {
        return this.venting;
    }

    public FluidTank steam() {
        return this.steam;
    }

    public double waterIn() {
        return this.lastWaterIn;
    }

    public double outFlow() {
        return this.lastOutFlow;
    }

    public double vented() {
        return this.lastVented;
    }

    public double averageK() {
        return this.lastK;
    }

    public int activeRods() {
        return this.activeRods;
    }

    public int fuelChannels() {
        return this.fuel.size();
    }

    public int controlChannels() {
        return this.control.size();
    }

    public int controlRodsInstalled() {
        int count = 0;
        for (boolean rod : this.controlHasRod) {
            if (rod) count++;
        }
        return count;
    }

    public int inputPorts() {
        return this.inputs.size();
    }

    public int outputPorts() {
        return this.outputs.size();
    }

    public String sizeText() {
        if (!this.formed) return "-";
        return (this.max.getX() - this.min.getX() + 1) + "x" + (this.max.getY() - this.min.getY() + 1) + "x" + (this.max.getZ() - this.min.getZ() + 1);
    }

    public int interiorVolume() {
        return this.interior;
    }

    /**
     * Absolute pressure in bar (display only). In boiling mode it is the boiling pressure of water at the core
     * temperature (1 bar at 100 °C, about 74 bar at 290 °C; see {@link Steam#pressure}). In pressurized mode a
     * pressurizer holds the loop at about 155 bar once it is filled. An empty or cold vessel is at the air's
     * pressure.
     */
    public double pressureBar() {
        if (this.pressurized) return this.water.amount() > 0.0 ? 155.0 : Steam.ATMOSPHERIC;
        return this.temperature <= 100.0 ? Steam.ATMOSPHERIC : Steam.pressure(this.temperature);
    }

    public double[] rodFuelLeft() {
        double[] out = new double[this.tier.length];
        for (int i = 0; i < this.tier.length; i++) {
            out[i] = FuelRods.isActive(this.tier[i]) ? 1.0 - this.used[i] / FuelRods.energy(this.tier[i]) : 0.0;
        }
        return out;
    }

    /**
     * One channel of the core, for the core map: a fuel channel (with its rod, the fuel left in it and the heat
     * it makes) or a control rod channel (with or without a rod, and how far that is in).
     */
    public record CoreCell(BlockPos pos, boolean isFuel, int tier, double fuelLeft, double power, boolean hasRod, double inserted) {
    }

    /**
     * Every fuel and control rod channel of the core, as it is right now.
     */
    public List<CoreCell> coreCells() {
        List<CoreCell> cells = new ArrayList<>();
        for (int i = 0; i < this.fuel.size() && i < this.tier.length; i++) {
            int t = this.tier[i];
            double left = FuelRods.isActive(t) ? Math.max(0.0, 1.0 - this.used[i] / FuelRods.energy(t)) : 0.0;
            // The rod's share of the fission power (the same split the burn-up uses).
            double power = this.lastFissionTarget > 0.0 && i < this.rodPower.length ? this.flux * this.rodPower[i] / this.lastFissionTarget : 0.0;
            cells.add(new CoreCell(this.fuel.get(i), true, t, left, power, false, 0.0));
        }
        for (int i = 0; i < this.control.size() && i < this.controlHasRod.length; i++) {
            BlockPos pos = this.control.get(i);
            cells.add(new CoreCell(pos, false, FuelRods.EMPTY, 0.0, 0.0, this.controlHasRod[i], this.insertionAt(pos)));
        }
        return cells;
    }

    /**
     * Lowest corner of the inside of the reactor (inside the casing).
     */
    public BlockPos coreMin() {
        return this.min.offset(1, 1, 1);
    }

    /**
     * Highest corner of the inside of the reactor.
     */
    public BlockPos coreMax() {
        return this.max.offset(-1, -1, -1);
    }

    /**
     * The way you look into the core when you stand at the control box (north for a box in the floor or roof).
     */
    public Direction viewDirection() {
        BlockPos pos = this.getBlockPos();
        if (pos.getX() == this.min.getX()) return Direction.EAST;
        if (pos.getX() == this.max.getX()) return Direction.WEST;
        if (pos.getZ() == this.min.getZ()) return Direction.SOUTH;
        if (pos.getZ() == this.max.getZ()) return Direction.NORTH;
        return Direction.NORTH;
    }

    public int countTier(int wanted) {
        int count = 0;
        for (int t : this.tier) {
            if (t == wanted) count++;
        }
        return count;
    }

    /**
     * Spent rods of every kind in the core.
     */
    public int countSpent() {
        int count = 0;
        for (int t : this.tier) {
            if (FuelRods.isSpent(t)) count++;
        }
        return count;
    }

    // =====================================================================================
    // Controls
    // =====================================================================================

    public void setInsertion(double value) {
        this.insertion = Math.clamp(value, 0.0, 100.0);
        this.setChanged();
    }

    public void setControlMode(ControlMode mode) {
        this.controlMode = mode;
        this.setChanged();
    }

    public void setTargetTemp(double value) {
        this.targetTemp = Math.clamp(value, 50.0, 1100.0);
        this.setChanged();
    }

    public void setTargetPower(double value) {
        this.targetPower = Math.clamp(value, 0.0, 300.0);
        this.setChanged();
    }

    public void scram(ServerLevel level, String reason) {
        if (this.scrammed) return;
        this.scrammed = true;
        this.lastScramReason = reason;
        this.setChanged();
        Sounds.at(level, this.getBlockPos(), SoundEvents.BEACON_DEACTIVATE, 1.0f, 0.6f);
        if (this.formed) {
            // The rods drop into the core: a heavy clunk from inside the reactor.
            BlockPos center = this.center();
            Sounds.at(level, center, SoundEvents.PISTON_CONTRACT, 1.5f, 0.5f);
            Sounds.at(level, center, SoundEvents.ANVIL_LAND, 0.5f, 0.6f);
        }
        Component message = Component.literal("☢ Reactor SCRAM at " + this.getBlockPos().toShortString() + ": " + reason).withStyle(ChatFormatting.RED);
        for (ServerPlayer player : level.players()) {
            if (player.blockPosition().closerThan(this.getBlockPos(), 48.0)) player.sendSystemMessage(message);
        }
    }

    /**
     * Clears a SCRAM so the operator can start the reactor again.
     *
     * @return null on success, otherwise the reason it cannot restart
     */
    public @Nullable String reset(ServerLevel level) {
        if (!this.formed) return "The reactor structure is not complete.";
        if (this.integrity <= 0.0) return "The core is destroyed.";
        if (this.temperature >= this.scramTemp) return "Cool the core below " + Fmt.temp(this.scramTemp) + " first.";
        this.scrammed = false;
        this.lastScramReason = "";
        this.setChanged();
        Sounds.at(level, this.getBlockPos(), SoundEvents.BEACON_ACTIVATE, 1.0f, 1.2f);
        return null;
    }

    public @Nullable String togglePressurized() {
        if (!this.scrammed && this.insertion < 100.0) return "Shut the reactor down (SCRAM) before changing the cooling mode.";
        if (this.temperature > 100.0) return "Let the core cool below 100 °C before changing the cooling mode.";
        this.pressurized = !this.pressurized;
        this.steam.set(0.0);
        this.setChanged();
        return null;
    }

    public void setAutoScram(boolean autoScram) {
        this.autoScram = autoScram;
        this.setChanged();
    }

    public void setScramTemp(double value) {
        this.scramTemp = Math.clamp(value, 150.0, 3000.0);
        this.setChanged();
    }

    public void setReliefValve(boolean reliefValve) {
        this.reliefValve = reliefValve;
        this.setChanged();
    }

    public void markStructureDirty() {
        this.structureDirty = true;
        this.dirtyDelay = 5;
    }

    // =====================================================================================
    // Refuelling through the GUI
    // =====================================================================================

    /**
     * Moves fuel rods of one kind from the player's inventory into empty fuel channels.
     */
    public int loadFuel(ServerLevel level, ServerPlayer player, Item rodItem) {
        if (!this.formed) return 0;
        this.writeBackRods(level);
        Inventory inventory = player.getInventory();
        int loaded = 0;
        for (BlockPos pos : this.fuel) {
            if (!(level.getBlockEntity(pos) instanceof FuelChannelBlockEntity channel) || !channel.getRod().isEmpty()) continue;
            ItemStack taken = ItemStack.EMPTY;
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (stack.is(rodItem)) {
                    taken = stack.split(1);
                    break;
                }
            }
            if (taken.isEmpty() && player.hasInfiniteMaterials()) taken = new ItemStack(rodItem);
            if (taken.isEmpty()) break;
            channel.setRod(taken);
            loaded++;
        }
        if (loaded > 0) this.markStructureDirty();
        return loaded;
    }

    /**
     * @param depletedOnly true to only take out spent rods
     * @return number of rods returned, or -1 when the core is too hot
     */
    public int unloadFuel(ServerLevel level, ServerPlayer player, boolean depletedOnly) {
        if (!this.formed) return 0;
        if (this.temperature > 200.0) return -1;
        this.writeBackRods(level);
        int count = 0;
        for (BlockPos pos : this.fuel) {
            if (!(level.getBlockEntity(pos) instanceof FuelChannelBlockEntity channel) || channel.getRod().isEmpty()) continue;
            if (depletedOnly && !FuelRods.isSpent(FuelRods.tierOf(channel.getRod()))) continue;
            PlayerItems.give(player, channel.takeRod());
            count++;
        }
        if (count > 0) this.markStructureDirty();
        return count;
    }

    public int loadControlRods(ServerLevel level, ServerPlayer player) {
        if (!this.formed) return 0;
        Inventory inventory = player.getInventory();
        int loaded = 0;
        for (BlockPos pos : this.control) {
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof ControlRodChannelBlock) || state.getValue(ControlRodChannelBlock.HAS_ROD)) continue;
            boolean found = player.hasInfiniteMaterials();
            for (int slot = 0; slot < inventory.getContainerSize() && !found; slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (stack.is(ModItems.CONTROL_ROD)) {
                    stack.shrink(1);
                    found = true;
                }
            }
            if (!found) break;
            level.setBlock(pos, state.setValue(ControlRodChannelBlock.HAS_ROD, true), Block.UPDATE_ALL);
            loaded++;
        }
        if (loaded > 0) this.markStructureDirty();
        return loaded;
    }

    public int unloadControlRods(ServerLevel level, ServerPlayer player) {
        if (!this.formed) return 0;
        int count = 0;
        for (BlockPos pos : this.control) {
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof ControlRodChannelBlock) || !state.getValue(ControlRodChannelBlock.HAS_ROD)) continue;
            level.setBlock(pos, state.setValue(ControlRodChannelBlock.HAS_ROD, false), Block.UPDATE_ALL);
            PlayerItems.give(player, new ItemStack(ModItems.CONTROL_ROD));
            count++;
        }
        if (count > 0) this.markStructureDirty();
        return count;
    }

    // =====================================================================================
    // Ports
    // =====================================================================================

    /**
     * What leaves through the OUTPUT ports: the coolant in the vessel (pressurized mode) or the steam.
     */
    private FluidTank outputTank() {
        return this.pressurized ? this.water : this.steam;
    }

    private FluidType outputFluid() {
        return this.pressurized ? FluidType.COOLANT : FluidType.STEAM;
    }

    /**
     * Temperature of what leaves through the OUTPUT ports: the coolant's (pressurized mode), or the steam's,
     * which boils off the fuel at the core's temperature (boiling mode).
     */
    private double outputTemperature() {
        return this.pressurized ? this.water.temperature() : Math.max(100.0, this.temperature);
    }

    public FluidHandler portHandler(BlockPos port) {
        boolean input = this.inputs.contains(port);
        boolean output = this.outputs.contains(port);
        return new FluidHandler() {
            @Override
            public double insert(Direction side, FluidType type, double amount, boolean simulate) {
                return this.insert(side, type, amount, type.defaultTemperature(), simulate);
            }

            @Override
            public double insert(Direction side, FluidType type, double amount, double temperature, boolean simulate) {
                if (!input) return 0.0;
                ReactorControllerBlockEntity reactor = ReactorControllerBlockEntity.this;
                // Coolant coming back from a heat exchanger is always welcome (it is water, at its temperature).
                if (type == FluidType.COOLANT) return reactor.water.insert(FluidType.WATER, amount, temperature, simulate);
                // Fresh water only tops the loop up (see makeUpRoom).
                if (type == FluidType.WATER) {
                    return reactor.water.insert(FluidType.WATER, Math.min(amount, reactor.makeUpRoom()), temperature, simulate);
                }
                return 0.0;
            }

            @Override
            public double extract(Direction side, FluidType type, double max, boolean pumped, boolean simulate) {
                ReactorControllerBlockEntity reactor = ReactorControllerBlockEntity.this;
                if (!output || type != reactor.outputFluid()) return 0.0;
                FluidTank tank = reactor.outputTank();
                return tank.extract(tank.type(), max, simulate);
            }

            @Override
            public double temperature(Direction side, FluidType type) {
                return output ? ReactorControllerBlockEntity.this.outputTemperature() : type.defaultTemperature();
            }

            @Override
            public @Nullable FluidType available(Direction side, boolean pumped) {
                ReactorControllerBlockEntity reactor = ReactorControllerBlockEntity.this;
                return output && reactor.outputTank().amount() > 0.0 ? reactor.outputFluid() : null;
            }

            @Override
            public @Nullable FluidType offers(Direction side, boolean pumped) {
                return output ? ReactorControllerBlockEntity.this.outputFluid() : null;
            }

            @Override
            public double fillLevel(Direction side) {
                if (!input) return -1.0;
                ReactorControllerBlockEntity reactor = ReactorControllerBlockEntity.this;
                return reactor.pressurized ? reactor.coolantLevel() : reactor.water.fill();
            }
        };
    }

    // =====================================================================================
    // Ticking
    // =====================================================================================

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        if (level instanceof ServerLevel serverLevel) {
            ReactorRegistry.track(serverLevel, this.getBlockPos());
            this.structureDirty = true;
        }
    }

    @Override
    public void setRemoved() {
        if (this.level instanceof ServerLevel serverLevel) ReactorRegistry.untrack(serverLevel, this.getBlockPos());
        super.setRemoved();
    }

    @Override
    public void serverTick(ServerLevel level, BlockPos pos, BlockState state) {
        this.validateTimer++;
        boolean rescan = (this.structureDirty && --this.dirtyDelay <= 0) || this.validateTimer >= 100;
        if (rescan) {
            this.validateTimer = 0;
            this.structureDirty = false;
            this.scan(level, pos);
        }

        boolean redstone = level.hasNeighborSignal(pos);
        // In REDSTONE mode the signal moves the rods instead of triggering a SCRAM.
        if (redstone && !this.lastRedstone && this.formed && this.controlMode != ControlMode.REDSTONE) {
            this.scram(level, "redstone signal");
        }
        this.lastRedstone = redstone;

        if (!this.formed) {
            this.flux *= 0.9;
            this.decayHeat *= 0.995;
            this.xenon.tick(this.powerLevel());
            this.temperature += (20.0 - this.temperature) * 0.002;
            this.lastThermal = this.flux + this.decayHeat;
            return;
        }

        if (this.paused) return;
        this.simulate(level);
        this.alarms(level, pos);
        this.hum(level);

        if (level.getGameTime() % 20L == 0L) {
            this.writeBackRods(level);
            this.setChanged();
        }
    }

    private BlockPos center() {
        return new BlockPos((this.min.getX() + this.max.getX()) / 2, (this.min.getY() + this.max.getY()) / 2, (this.min.getZ() + this.max.getZ()) / 2);
    }

    /**
     * A running reactor hums; louder and higher with more power.
     */
    private void hum(ServerLevel level) {
        if (level.getGameTime() % 40L != 0L) return;
        double power = this.powerLevel();
        if (power < 0.02) return;
        double strength = Math.min(1.5, power) / 1.5;
        Sounds.at(level, this.center(), SoundEvents.BEACON_AMBIENT, (float) (0.5 + 0.7 * strength), (float) (0.6 + 0.6 * strength));
    }

    /**
     * Pressurized mode. A closed loop filled to the brim cannot circulate: the pump has nowhere to push,
     * the heat exchanger has nowhere to put its coolant, and the coolant cannot leave the core.
     * When the vessel stays completely full while no coolant can get out and the core overheats (above the
     * warning temperature), the relief valve lets coolant out as steam until the vessel is down to
     * {@link #VENT_DOWN_TO}; that room then moves around the loop and everything flows again.
     * <p>
     * A loop that only stands still because the grid needs no power (the turbines take no steam) is left alone:
     * in temperature-hold mode the reactor just turns down and holds its temperature.
     *
     * @return coolant vented this tick
     */
    private double relieveSolidLoop(ServerLevel level, double vessel, double outFlow, NuclearConfig cfg) {
        boolean solid = vessel > 0.0 && this.coolantInVessel() >= vessel * SOLID_LEVEL && outFlow <= 0.0
                && this.temperature >= cfg.warningTemp;
        this.solidTicks = solid ? this.solidTicks + 1 : 0;
        if (this.reliefValve && this.solidTicks >= SOLID_TICKS) this.venting = true;
        if (!this.venting) return 0.0;
        double excess = this.coolantInVessel() - vessel * VENT_DOWN_TO;
        if (excess <= 0.0 || !this.reliefValve) {
            this.venting = false;
            this.solidTicks = 0;
            return 0.0;
        }
        double vent = Math.min(cfg.portRate, excess);
        this.water.extract(FluidType.WATER, vent, false);
        if (level.getGameTime() % 5L == 0L) {
            double x = (this.min.getX() + this.max.getX()) / 2.0 + 0.5;
            double z = (this.min.getZ() + this.max.getZ()) / 2.0 + 0.5;
            level.sendParticles(ParticleTypes.CLOUD, x, this.max.getY() + 1.2, z, 6, 0.4, 0.2, 0.4, 0.05);
            SoundEvent hiss = Sounds.steamHiss();
            if (hiss != null) Sounds.at(level, this.center(), hiss, 0.6f, 0.7f);
        }
        return vent;
    }

    /**
     * Draws up to {@code room} of a fluid in through the INPUT ports, the same amount through each port that
     * has some (a port that runs dry hands its part to the others), at most {@code portRate} per port per tick
     * counting everything that port already took this tick ({@code portUse}).
     *
     * @return the amount taken in (stored as water in the vessel)
     */
    private double pullIn(ServerLevel level, FluidType type, Map<BlockPos, Double> portUse, double portRate, double room) {
        List<BlockPos> open = new ArrayList<>(this.inputs);
        double total = 0.0;
        for (int pass = 0; pass < 3 && total < room - 1.0e-9 && !open.isEmpty(); pass++) {
            double part = (room - total) / open.size();
            Iterator<BlockPos> iterator = open.iterator();
            while (iterator.hasNext()) {
                BlockPos port = iterator.next();
                Direction out = this.portOutside.get(port);
                double ask = Math.min(part, portRate - portUse.getOrDefault(port, 0.0));
                double temperature = out != null ? FluidAccess.temperatureAt(level, port, out, type) : type.defaultTemperature();
                double got = out != null && ask > 0.0 ? FluidAccess.pull(level, port, out, type, ask, false) : 0.0;
                got = this.water.insert(FluidType.WATER, got, temperature, false);
                portUse.merge(port, got, Double::sum);
                total += got;
                if (got < part - 1.0e-9) iterator.remove();
            }
        }
        return total;
    }

    /**
     * Pushes the steam or coolant out through the OUTPUT ports, the same amount through each port that can
     * take it (a blocked port hands its part to the others), at most {@code portRate} per port per tick.
     *
     * @return the amount that left the core
     */
    private double pushOut(ServerLevel level, FluidTank tank, FluidType fluid, double temperature, double portRate) {
        List<BlockPos> open = new ArrayList<>(this.outputs);
        Map<BlockPos, Double> sent = new HashMap<>();
        double total = 0.0;
        for (int pass = 0; pass < 3 && tank.amount() > 1.0e-9 && !open.isEmpty(); pass++) {
            double part = tank.amount() / open.size();
            Iterator<BlockPos> iterator = open.iterator();
            while (iterator.hasNext()) {
                BlockPos port = iterator.next();
                Direction out = this.portOutside.get(port);
                double ask = Math.min(part, portRate - sent.getOrDefault(port, 0.0));
                double pushed = out != null && ask > 0.0 ? FluidAccess.push(level, port, out, fluid, ask, temperature) : 0.0;
                tank.extract(tank.type(), pushed, false);
                sent.merge(port, pushed, Double::sum);
                total += pushed;
                if (pushed < part - 1.0e-9) iterator.remove();
            }
        }
        return total;
    }

    private void simulate(ServerLevel level) {
        NuclearConfig cfg = NuclearConfig.get();
        double volume = this.interior;
        double vessel = volume * cfg.coolantPerInteriorBlock;
        this.water.setCapacity(vessel);
        this.steam.setCapacity(volume * cfg.steamPerInteriorBlock);
        if (this.water.amount() > this.water.capacity()) this.water.set(this.water.capacity());

        // 1. Coolant / water in, evenly through all INPUT ports, each at its own temperature. Coolant coming back
        // from the heat exchangers first, whenever there is room; fresh water only as make-up (in pressurized
        // mode just up to makeUpLevel, see makeUpRoom).
        Map<BlockPos, Double> portUse = new HashMap<>();
        double waterIn = this.pullIn(level, FluidType.COOLANT, portUse, cfg.portRate, this.water.space());
        waterIn += this.pullIn(level, FluidType.WATER, portUse, cfg.portRate, this.makeUpRoom());
        // Share of the vessel filled with liquid (it moderates and carries the heat).
        double liquid = vessel > 0.0 ? Math.min(1.0, this.water.amount() / vessel) : 0.0;

        // 2. Neutronics.
        double ins = this.scrammed ? 1.0 : this.insertion / 100.0;
        for (int c = 0; c < this.controlIn.length; c++) {
            this.controlIn[c] = ControlRodChannelBlock.insertionInStack(ins, this.controlFromTop[c], this.controlStack[c]);
        }
        double moderation = cfg.voidModerationMin + (1.0 - cfg.voidModerationMin) * Math.min(1.0, liquid * 1.25);
        double doppler = 1.0 / (1.0 + Math.max(0.0, this.temperature - cfg.dopplerReferenceTemp) / cfg.dopplerScale);
        // Xenon soaks up neutrons everywhere in the core.
        double poison = this.xenon.reactivityFactor();
        double target = 0.0;
        double kSum = 0.0;
        int active = 0;
        for (int i = 0; i < this.tier.length; i++) {
            if (!FuelRods.isActive(this.tier[i])) {
                this.rodPower[i] = 0.0;
                continue;
            }
            double coupling = 1.0;
            for (int k = 0; k < this.fuelNeighbors[i].length; k++) {
                coupling += this.fuelWeights[i][k] * FuelRods.neutronFactor(this.tier[this.fuelNeighbors[i][k]]);
            }
            double absorb = 0.0;
            for (int k = 0; k < this.controlNeighbors[i].length; k++) {
                int c = this.controlNeighbors[i][k];
                if (this.controlHasRod[c]) absorb += this.controlWeights[i][k] * this.controlIn[c];
            }
            absorb = Math.min(0.98, absorb);
            double multiplication = coupling * moderation * doppler * (1.0 - absorb) * poison;
            double remaining = 1.0 - this.used[i] / FuelRods.energy(this.tier[i]);
            double burn = remaining > 0.1 ? 1.0 : 0.3 + 7.0 * Math.max(0.0, remaining);
            double power = FuelRods.basePower(this.tier[i]) * multiplication * multiplication * burn;
            this.rodPower[i] = power;
            target += power;
            kSum += multiplication;
            active++;
        }
        this.activeRods = active;
        this.lastK = active > 0 ? kSum / active : 0.0;
        this.lastFissionTarget = target;

        double rate = target > this.flux ? 0.05 : 0.15;
        this.flux += (target - this.flux) * rate;
        if (this.flux < 1.0e-3) this.flux = 0.0;
        this.decayHeat += (cfg.decayHeatFraction * this.flux - this.decayHeat) / cfg.decayHeatTimeConstantTicks;
        this.xenon.tick(this.powerLevel());
        double thermal = this.flux + this.decayHeat;

        // 3. Burn-up.
        if (target > 0.0 && this.flux > 0.0) {
            for (int i = 0; i < this.tier.length; i++) {
                if (this.rodPower[i] <= 0.0) continue;
                this.used[i] += this.flux * this.rodPower[i] / target;
                if (this.used[i] >= FuelRods.energy(this.tier[i])) this.depleteRod(level, i);
            }
        }

        // 4. Heat removal by the coolant.
        double removed = 0.0;
        double made = 0.0;
        double wet = Math.min(1.0, liquid * 2.0);
        if (!this.pressurized) {
            if (this.temperature > 100.0) {
                double capacity = cfg.boilingTransfer * (this.temperature - 100.0) * wet * volume;
                removed = Math.min(capacity, this.water.amount() * cfg.boilHeatPerWater);
                double boiled = removed / cfg.boilHeatPerWater;
                this.water.extract(FluidType.WATER, boiled, false);
                this.steam.set(this.steam.amount() + boiled * cfg.steamPerWater);
                made = boiled * cfg.steamPerWater;
            }
        } else if (this.water.amount() > 1.0e-6) {
            // Pressurized: the core and the coolant in it trade heat, faster with a bigger difference, both ways
            // (coolant coming back hotter than the core warms it). Above pressurizedMaxTemp the coolant starts
            // to boil on the fuel and takes much less. A core that has cooled down heats the coolant no more, so
            // the heat exchangers get coolant too cold to make steam.
            double coolantCapacity = this.water.amount() * cfg.coolantHeatCapacity;
            double coreCapacity = volume * cfg.interiorHeatCapacity + this.shell * cfg.shellHeatCapacity;
            double difference = this.temperature - this.water.temperature();
            double transfer = cfg.coreToCoolantTransfer * wet * volume;
            if (this.temperature > cfg.pressurizedMaxTemp) transfer *= 0.3;
            // Never more than what would bring both to the same temperature in one tick.
            double equalize = difference * coolantCapacity * coreCapacity / (coolantCapacity + coreCapacity);
            removed = Math.abs(transfer * difference) < Math.abs(equalize) ? transfer * difference : equalize;
            this.water.setTemperature(this.water.temperature() + removed / coolantCapacity);
        }

        // 5. Steam / coolant out, evenly through all OUTPUT ports.
        double outFlow = this.pushOut(level, this.outputTank(), this.outputFluid(), this.outputTemperature(), cfg.portRate);

        // 6. Relief valve and over-pressure.
        double vented = 0.0;
        if (this.pressurized) {
            vented = this.relieveSolidLoop(level, vessel, outFlow, cfg);
        } else {
            double limit = this.steam.capacity() * 0.95;
            if (this.reliefValve && this.steam.amount() > limit) {
                vented = this.steam.amount() - limit;
                this.steam.set(limit);
                if (level.getGameTime() % 5L == 0L) {
                    level.sendParticles(ParticleTypes.CLOUD, (this.min.getX() + this.max.getX()) / 2.0 + 0.5, this.max.getY() + 1.2,
                            (this.min.getZ() + this.max.getZ()) / 2.0 + 0.5, 6, 0.4, 0.2, 0.4, 0.05);
                }
            }
            double fill = this.steam.capacity() > 0.0 ? this.steam.amount() / this.steam.capacity() : 0.0;
            if (fill > 1.0) {
                this.integrity -= (fill - 1.0) * 0.5;
                if (fill > 1.5) {
                    this.triggerMeltdown(level, "steam explosion: the reactor vessel ruptured from over-pressure");
                    return;
                }
            }
        }

        // 7. Temperature.
        double loss = cfg.shellHeatLoss * this.shell * (this.temperature - 20.0);
        double heatCapacity = volume * cfg.interiorHeatCapacity + this.shell * cfg.shellHeatCapacity;
        this.temperature += (thermal - removed - loss) / Math.max(1.0, heatCapacity);
        if (this.temperature < 15.0) this.temperature = 15.0;

        // 8. Damage and safety systems.
        if (this.temperature > cfg.fuelDamageTemp) {
            this.integrity -= (this.temperature - cfg.fuelDamageTemp) / cfg.integrityLossDivisor / 20.0;
        } else if (this.temperature < 200.0 && this.integrity < 100.0) {
            this.integrity = Math.min(100.0, this.integrity + 0.0005);
        }
        if (this.autoScram && !this.scrammed && this.temperature >= this.scramTemp) {
            this.scram(level, "core temperature reached " + Fmt.temp(this.temperature));
        }

        this.lastThermal = thermal;
        this.lastRemoved = removed;
        this.lastLoss = loss;
        this.lastWaterIn = waterIn;
        this.lastOutFlow = outFlow;
        this.lastVented = vented;
        this.lastMade = made;

        this.automaticControl(level, removed, loss, heatCapacity);

        if (this.integrity <= 0.0) {
            this.integrity = 0.0;
            this.triggerMeltdown(level, "core meltdown: the fuel overheated");
        }
    }

    /**
     * Moves the control rods for the automatic modes (never faster than the rod drives allow):
     * <ul>
     *     <li>TEMPERATURE: aims for the fission power that brings the core to the target temperature in about
     *     10 seconds, given the heat that is leaving it right now.</li>
     *     <li>POWER: aims for a fixed share of the fuel's nominal power.</li>
     *     <li>REDSTONE: the strongest redstone signal at the control box sets the insertion; no signal means
     *     rods fully in (fail-safe), signal 15 means fully out.</li>
     * </ul>
     * The fission target reacts immediately to the rods, so this is a simple integrating controller.
     */
    private void automaticControl(ServerLevel level, double removed, double loss, double heatCapacity) {
        if (this.scrammed || this.controlMode == ControlMode.MANUAL) return;
        double wanted;
        if (this.controlMode == ControlMode.REDSTONE) {
            int signal = level.getBestNeighborSignal(this.getBlockPos());
            wanted = 100.0 - signal * 100.0 / 15.0;
        } else {
            double nominal = this.nominalPower();
            if (nominal <= 0.0) return;
            double desired;
            if (this.controlMode == ControlMode.TEMPERATURE) {
                desired = removed + loss + heatCapacity * (this.targetTemp - this.temperature) / AUTO_RESPONSE_TICKS - this.decayHeat;
            } else {
                desired = nominal * this.targetPower / 100.0;
            }
            double error = (this.lastFissionTarget - Math.max(0.0, desired)) / nominal;
            wanted = this.insertion + Math.clamp(error * AUTO_GAIN, -AUTO_MAX_STEP, AUTO_MAX_STEP);
        }
        double step = Math.clamp(wanted - this.insertion, -ROD_SPEED, ROD_SPEED);
        this.insertion = Math.clamp(this.insertion + step, 0.0, 100.0);
    }

    /**
     * A rod that has given all its energy turns into a depleted rod: one of its kind for redstone and glowstone
     * fuel (it can be enriched), the plain depleted rod for enriched fuel.
     */
    private void depleteRod(ServerLevel level, int index) {
        BlockPos pos = this.fuel.get(index);
        ItemStack spent = new ItemStack(FuelRods.depletedItem(this.tier[index]));
        this.tier[index] = FuelRods.tierOf(spent);
        this.used[index] = 0.0;
        this.rodPower[index] = 0.0;
        if (level.getBlockEntity(pos) instanceof FuelChannelBlockEntity channel) {
            channel.setRod(spent);
        }
    }

    private void writeBackRods(ServerLevel level) {
        for (int i = 0; i < this.fuel.size() && i < this.tier.length; i++) {
            if (!FuelRods.isActive(this.tier[i])) continue;
            BlockPos pos = this.fuel.get(i);
            if (!level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof FuelChannelBlockEntity channel)) continue;
            ItemStack rod = channel.getRod();
            if (FuelRods.tierOf(rod) != this.tier[i]) continue;
            ItemStack updated = rod.copy();
            FuelRods.setUsedEnergy(updated, this.used[i]);
            channel.updateRodSilently(updated);
        }
    }

    /**
     * Two-tone siren while something is wrong (faster once the core is being damaged), plus a warning
     * on the screen of everyone nearby.
     */
    private void alarms(ServerLevel level, BlockPos pos) {
        NuclearConfig cfg = NuclearConfig.get();
        boolean hot = this.temperature >= cfg.warningTemp;
        boolean pressure = !this.pressurized && this.steamFill() > 0.9;
        boolean damaged = this.integrity < 50.0;
        if (!hot && !pressure && !damaged) return;
        long time = level.getGameTime();
        boolean urgent = damaged || this.temperature >= cfg.fuelDamageTemp || this.steamFill() > 1.0;
        long period = urgent ? 5L : 10L;
        if (time % period == 0L) {
            boolean high = (time / period) % 2L == 0L;
            Sounds.at(level, pos, SoundEvents.NOTE_BLOCK_BIT.value(), 2.0f, high ? 1.0f : 0.75f);
        }
        if (time % 40L != 0L) return;
        String what = damaged ? "CORE DAMAGE " + Fmt.pct(this.integrity / 100.0) + " integrity"
                : hot ? "HIGH TEMPERATURE " + Fmt.temp(this.temperature) : "HIGH PRESSURE";
        Component message = Component.literal("☢ REACTOR ALARM: " + what).withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
        for (ServerPlayer player : level.players()) {
            if (player.blockPosition().closerThan(pos, 32.0)) player.sendOverlayMessage(message);
        }
    }

    private void triggerMeltdown(ServerLevel level, String cause) {
        if (this.meltdownQueued) return;
        this.meltdownQueued = true;
        double power = this.flux + this.decayHeat;
        Meltdown.queue(level, new Meltdown.Event(this.getBlockPos(), this.min, this.max, new ArrayList<>(this.fuel), this.activeRods, power, cause));
        // The core is gone; reset so a surviving controller does not loop.
        this.flux = 0.0;
        this.decayHeat = 0.0;
        this.xenon.reset();
        this.temperature = 100.0;
        this.integrity = 25.0;
        this.scrammed = true;
        this.lastScramReason = "meltdown";
        this.water.set(0.0);
        this.steam.set(0.0);
        for (int i = 0; i < this.tier.length; i++) {
            this.tier[i] = FuelRods.EMPTY;
            this.used[i] = 0.0;
        }
        this.setChanged();
    }

    /**
     * Called by {@link Meltdown} once the event was processed (if this controller survived).
     */
    public void meltdownProcessed() {
        this.meltdownQueued = false;
        this.markStructureDirty();
    }

    // =====================================================================================
    // Multiblock validation
    // =====================================================================================

    /**
     * Whether this block cannot be part of the casing shell (anything but casing, glass, ports and the control box).
     */
    private static boolean notCasing(BlockState state) {
        return !(state.getBlock() instanceof ReactorPart part && part.isStructural());
    }

    /**
     * Part of the reactor is in an unloaded chunk: keep the state and simply wait.
     */
    private void pause() {
        this.paused = true;
        this.structureDirty = true;
        this.dirtyDelay = 40;
        this.status = Component.literal("Paused: part of the reactor is in an unloaded chunk.").withStyle(ChatFormatting.GOLD);
    }

    private void fail(ServerLevel level, BlockPos pos, Component reason) {
        this.paused = false;
        if (this.formed && !this.meltdownQueued && this.temperature >= NuclearConfig.get().warningTemp) {
            this.triggerMeltdown(level, "containment breached while the core was at " + Fmt.temp(this.temperature));
        }
        this.formed = false;
        this.status = reason;
        this.fuel.clear();
        this.control.clear();
        this.inputs.clear();
        this.outputs.clear();
        this.portOutside.clear();
        this.tier = new int[0];
        this.used = new double[0];
        this.rodPower = new double[0];
        this.fuelNeighbors = new int[0][];
        this.fuelWeights = new double[0][];
        this.controlNeighbors = new int[0][];
        this.controlWeights = new double[0][];
        this.controlHasRod = new boolean[0];
        this.controlFromTop = new int[0];
        this.controlStack = new int[0];
        this.controlIn = new double[0];
        this.controlSlots.clear();
        ReactorRegistry.setFormed(level, pos, null, null);
    }

    private static Component error(String text) {
        return Component.literal(text).withStyle(ChatFormatting.RED);
    }

    public void scan(ServerLevel level, BlockPos pos) {
        if (this.formed) this.writeBackRods(level);
        NuclearConfig cfg = NuclearConfig.get();
        int maxSize = cfg.maxReactorSize;

        // Flood-fill the casing shell starting at the controller.
        Set<BlockPos> shellBlocks = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        shellBlocks.add(pos);
        queue.add(pos);
        int limit = maxSize * maxSize * 6 + 64;
        int minX = pos.getX();
        int minY = pos.getY();
        int minZ = pos.getZ();
        int maxX = minX;
        int maxY = minY;
        int maxZ = minZ;
        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            for (Direction dir : Direction.values()) {
                BlockPos next = current.relative(dir);
                if (shellBlocks.contains(next)) continue;
                if (Math.abs(next.getX() - pos.getX()) >= maxSize || Math.abs(next.getY() - pos.getY()) >= maxSize
                        || Math.abs(next.getZ() - pos.getZ()) >= maxSize) {
                    continue;
                }
                if (!level.isLoaded(next)) {
                    this.pause();
                    return;
                }
                if (notCasing(level.getBlockState(next))) continue;
                shellBlocks.add(next);
                queue.add(next);
                minX = Math.min(minX, next.getX());
                minY = Math.min(minY, next.getY());
                minZ = Math.min(minZ, next.getZ());
                maxX = Math.max(maxX, next.getX());
                maxY = Math.max(maxY, next.getY());
                maxZ = Math.max(maxZ, next.getZ());
                if (shellBlocks.size() > limit) {
                    this.fail(level, pos, error("Too much connected casing: the reactor is larger than " + maxSize + " blocks."));
                    return;
                }
            }
        }

        int sizeX = maxX - minX + 1;
        int sizeY = maxY - minY + 1;
        int sizeZ = maxZ - minZ + 1;
        int minSize = cfg.minReactorSize;
        if (sizeX < minSize || sizeY < minSize || sizeZ < minSize || sizeX > maxSize || sizeY > maxSize || sizeZ > maxSize) {
            this.fail(level, pos, error("The casing must form a closed box between " + minSize + " and " + maxSize
                    + " blocks per side (found " + sizeX + "x" + sizeY + "x" + sizeZ + ")."));
            return;
        }

        List<BlockPos> newFuel = new ArrayList<>();
        List<BlockPos> newControl = new ArrayList<>();
        List<BlockPos> newInputs = new ArrayList<>();
        List<BlockPos> newOutputs = new ArrayList<>();
        Map<BlockPos, Direction> newOutside = new HashMap<>();
        int controllers = 0;
        int interiorCount = 0;
        int shellCount = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (!level.isLoaded(p)) {
                        this.pause();
                        return;
                    }
                    BlockState state = level.getBlockState(p);
                    int planes = (x == minX || x == maxX ? 1 : 0) + (y == minY || y == maxY ? 1 : 0) + (z == minZ || z == maxZ ? 1 : 0);
                    if (planes > 0) {
                        shellCount++;
                        if (notCasing(state)) {
                            this.fail(level, pos, error("Missing casing at " + p.toShortString() + "."));
                            return;
                        }
                        Block block = state.getBlock();
                        if (block instanceof ReactorControllerBlock) {
                            controllers++;
                            if (planes > 1) {
                                this.fail(level, pos, error("The controller must sit in a face of the box, not on an edge or corner."));
                                return;
                            }
                        } else if (block instanceof ReactorPortBlock) {
                            if (planes > 1) {
                                this.fail(level, pos, error("Ports must sit in a face of the box, not on an edge or corner (" + p.toShortString() + ")."));
                                return;
                            }
                            Direction outside = x == minX ? Direction.WEST : x == maxX ? Direction.EAST
                                    : y == minY ? Direction.DOWN : y == maxY ? Direction.UP
                                    : z == minZ ? Direction.NORTH : Direction.SOUTH;
                            newOutside.put(p, outside);
                            if (state.getValue(ReactorPortBlock.MODE) == ReactorPortBlock.Mode.INPUT) {
                                newInputs.add(p);
                            } else {
                                newOutputs.add(p);
                            }
                        }
                    } else {
                        interiorCount++;
                        Block block = state.getBlock();
                        if (state.isAir() || state.is(Blocks.WATER)) continue;
                        if (block instanceof FuelChannelBlock) {
                            newFuel.add(p);
                        } else if (block instanceof ControlRodChannelBlock) {
                            newControl.add(p);
                        } else {
                            this.fail(level, pos, Component.literal("Only air, water, fuel channels and control rod channels may be inside the reactor. Found ")
                                    .withStyle(ChatFormatting.RED)
                                    .append(block.getName())
                                    .append(Component.literal(" at " + p.toShortString() + ".").withStyle(ChatFormatting.RED)));
                            return;
                        }
                    }
                }
            }
        }
        if (controllers != 1) {
            this.fail(level, pos, error("A reactor needs exactly one controller (found " + controllers + ")."));
            return;
        }
        if (newFuel.isEmpty()) {
            this.fail(level, pos, error("Place at least one Fuel Channel inside the reactor."));
            return;
        }

        boolean wasFormed = this.formed;
        this.paused = false;
        this.formed = true;
        this.min = new BlockPos(minX, minY, minZ);
        this.max = new BlockPos(maxX, maxY, maxZ);
        this.interior = interiorCount;
        this.shell = shellCount;
        this.fuel.clear();
        this.fuel.addAll(newFuel);
        this.control.clear();
        this.control.addAll(newControl);
        this.inputs.clear();
        this.inputs.addAll(newInputs);
        this.outputs.clear();
        this.outputs.addAll(newOutputs);
        this.portOutside.clear();
        this.portOutside.putAll(newOutside);
        this.rebuildCore(level, cfg);
        ReactorRegistry.setFormed(level, pos, this.min, this.max);

        String text = "Reactor formed: " + sizeX + "x" + sizeY + "x" + sizeZ + ", " + this.fuel.size() + " fuel / "
                + this.control.size() + " control channels, " + this.inputs.size() + " in / " + this.outputs.size() + " out ports.";
        if (this.inputs.isEmpty() || this.outputs.isEmpty()) {
            this.status = Component.literal(text + " Warning: it needs at least one INPUT and one OUTPUT port.").withStyle(ChatFormatting.GOLD);
        } else {
            this.status = Component.literal(text).withStyle(ChatFormatting.GREEN);
        }
        if (!wasFormed) Sounds.at(level, pos, SoundEvents.BEACON_POWER_SELECT, 1.0f, 1.0f);
    }

    private void rebuildCore(ServerLevel level, NuclearConfig cfg) {
        int fuelCount = this.fuel.size();
        int controlCount = this.control.size();
        this.tier = new int[fuelCount];
        this.used = new double[fuelCount];
        this.rodPower = new double[fuelCount];
        this.controlHasRod = new boolean[controlCount];

        Map<BlockPos, Integer> fuelIndex = new HashMap<>();
        Map<BlockPos, Integer> controlIndex = new HashMap<>();
        for (int i = 0; i < fuelCount; i++) {
            BlockPos pos = this.fuel.get(i);
            fuelIndex.put(pos, i);
            if (level.getBlockEntity(pos) instanceof FuelChannelBlockEntity channel) {
                ItemStack rod = channel.getRod();
                this.tier[i] = FuelRods.tierOf(rod);
                this.used[i] = FuelRods.usedEnergy(rod);
            }
        }
        for (int i = 0; i < controlCount; i++) {
            BlockPos pos = this.control.get(i);
            controlIndex.put(pos, i);
            BlockState state = level.getBlockState(pos);
            this.controlHasRod[i] = state.getBlock() instanceof ControlRodChannelBlock && state.getValue(ControlRodChannelBlock.HAS_ROD);
        }
        // Stacks of control rod channels: the rods go in from the top of each stack.
        this.controlFromTop = new int[controlCount];
        this.controlStack = new int[controlCount];
        this.controlIn = new double[controlCount];
        for (int i = 0; i < controlCount; i++) {
            BlockPos pos = this.control.get(i);
            int above = 0;
            while (controlIndex.containsKey(pos.above(above + 1))) above++;
            int below = 0;
            while (controlIndex.containsKey(pos.below(below + 1))) below++;
            this.controlFromTop[i] = above;
            this.controlStack[i] = above + 1 + below;
            this.controlIn[i] = 1.0;
        }
        this.controlSlots.clear();
        this.controlSlots.putAll(controlIndex);

        this.fuelNeighbors = new int[fuelCount][];
        this.fuelWeights = new double[fuelCount][];
        this.controlNeighbors = new int[fuelCount][];
        this.controlWeights = new double[fuelCount][];
        for (int i = 0; i < fuelCount; i++) {
            BlockPos origin = this.fuel.get(i);
            List<Integer> fuelIdx = new ArrayList<>();
            List<Double> fuelW = new ArrayList<>();
            List<Integer> ctrlIdx = new ArrayList<>();
            List<Double> ctrlW = new ArrayList<>();
            for (int dx = -2; dx <= 2; dx++) {
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos other = origin.offset(dx, dy, dz);
                        double distanceSq = dx * dx + dy * dy + dz * dz;
                        Integer f = fuelIndex.get(other);
                        if (f != null) {
                            fuelIdx.add(f);
                            fuelW.add(cfg.fuelCoupling / distanceSq);
                        }
                        Integer c = controlIndex.get(other);
                        if (c != null) {
                            ctrlIdx.add(c);
                            ctrlW.add(cfg.controlRodAbsorption / distanceSq);
                        }
                    }
                }
            }
            this.fuelNeighbors[i] = fuelIdx.stream().mapToInt(Integer::intValue).toArray();
            this.fuelWeights[i] = fuelW.stream().mapToDouble(Double::doubleValue).toArray();
            this.controlNeighbors[i] = ctrlIdx.stream().mapToInt(Integer::intValue).toArray();
            this.controlWeights[i] = ctrlW.stream().mapToDouble(Double::doubleValue).toArray();
        }
    }

    // =====================================================================================
    // Probe + persistence
    // =====================================================================================

    @Override
    public void appendInfo(List<Component> lines) {
        lines.add(Fmt.title("Reactor Controller"));
        lines.add(this.status);
        if (!this.formed) return;
        lines.add(Fmt.label("Core temperature", Fmt.temp(this.temperature), Fmt.tempColor(this.temperature)));
        lines.add(Fmt.label("Thermal power", Fmt.power(this.lastThermal) + " (decay heat " + Fmt.power(this.decayHeat) + ")", ChatFormatting.GOLD));
        if (NuclearConfig.get().xenonPoisoning) {
            lines.add(Fmt.label("Xenon", this.xenonSummary(), this.xenon.powerFactor() < 0.85 ? ChatFormatting.GOLD : ChatFormatting.LIGHT_PURPLE));
            Component warning = this.xenonWarning();
            if (warning != null) lines.add(warning);
        }
        lines.add(Fmt.label("Control rods", Fmt.num(this.scrammed ? 100.0 : this.insertion, 0) + "% inserted" + (this.scrammed ? " (SCRAM)" : ""),
                this.scrammed ? ChatFormatting.RED : ChatFormatting.GREEN));
        lines.add(Fmt.label("Rod control", this.controlModeText(), ChatFormatting.AQUA));
        lines.add(Fmt.label("Coolant", Fmt.mb(this.coolantInVessel()) + " / " + Fmt.mb(this.vesselCapacity())
                + (this.pressurized ? " at " + Fmt.temp(this.coolantTemperature()) : ""), ChatFormatting.BLUE));
        if (this.pressurized) {
            lines.add(Fmt.label("Make-up water", "taken only below " + Fmt.pct(NuclearConfig.get().makeUpLevel) + " full", ChatFormatting.GRAY));
        }
        if (this.venting) {
            lines.add(Component.literal("Relief valve venting: the coolant loop was too full to circulate.").withStyle(ChatFormatting.GOLD));
        }
        if (this.pressurized) {
            lines.add(Fmt.label("Heat to coolant", Fmt.power(this.lastRemoved) + ", " + Fmt.rate(this.lastOutFlow) + " out @ "
                    + Fmt.bar(this.pressureBar()), ChatFormatting.WHITE));
            double minimum = NuclearConfig.get().heatExchangerMinTemp;
            if (this.water.amount() > 1.0 && this.coolantTemperature() <= minimum) {
                lines.add(Component.literal("Coolant below " + Fmt.temp(minimum) + ": heat exchangers make no steam from it.").withStyle(ChatFormatting.GRAY));
            }
        } else {
            lines.add(Fmt.label("Steam made", Fmt.rate(this.lastMade) + " @ " + Fmt.bar(this.pressureBar()), ChatFormatting.WHITE));
        }
        if (!this.pressurized) {
            lines.add(Fmt.label("Steam space", Fmt.pct(this.steamFill()) + " full", this.steamFill() > 0.9 ? ChatFormatting.RED : ChatFormatting.WHITE));
        }
        lines.add(Fmt.label("Integrity", Fmt.pct(this.integrity / 100.0), this.integrity > 75.0 ? ChatFormatting.GREEN : this.integrity > 30.0 ? ChatFormatting.GOLD : ChatFormatting.RED));
    }

    /**
     * The xenon level, what it costs and where it is heading, in one line (probe and GUI).
     */
    public String xenonSummary() {
        double level = this.xenon.level();
        return Fmt.num(level, 2) + "x the full-power level, power " + Fmt.pct(this.xenon.powerFactor()) + " at the same rods, "
                + xenonTrend(this.xenon.forecast(this.powerLevel()));
    }

    /**
     * Where the xenon is heading, in words.
     */
    public static String xenonTrend(Xenon.Forecast forecast) {
        String level = Fmt.num(forecast.level(), 2) + "x";
        String minutes = forecast.minutes() < 1.0 ? "under a minute" : "about " + Fmt.num(forecast.minutes(), 0) + " min";
        return switch (forecast.trend()) {
            case STEADY -> "steady";
            case PEAKS -> "rising, peaks at " + level + " in " + minutes;
            case BOTTOMS_OUT -> "falling, down to " + level + " in " + minutes;
            case SETTLES_UP -> "rising, settles at " + level + " in " + minutes;
            case SETTLES_DOWN -> "falling, settles at " + level + " in " + minutes;
            case CLEARS -> "falling, nearly gone in " + minutes;
        };
    }

    /**
     * A warning when the xenon matters right now, or null: the rods are fully out and it still holds the power
     * down, or it is burning off and the power climbs by itself (manual rod control).
     */
    public @Nullable Component xenonWarning() {
        if (!NuclearConfig.get().xenonPoisoning || this.scrammed || this.flux <= 0.0) return null;
        double change = this.xenon.changePerMinute(this.powerLevel());
        if (this.insertion <= 0.5 && this.xenon.powerFactor() < 0.95) {
            return Component.literal("Xenon is holding the power down to " + Fmt.pct(this.xenon.powerFactor()) + " with the rods fully out:"
                    + " wait for it to fade, or it burns off as the reactor runs.").withStyle(ChatFormatting.GOLD);
        }
        if (this.controlMode == ControlMode.MANUAL && change < -0.02 && this.xenon.level() > 0.2) {
            return Component.literal("Xenon is burning off, so the power is climbing by itself: watch the temperature and push"
                    + " the rods in.").withStyle(ChatFormatting.GOLD);
        }
        return null;
    }

    /**
     * Short description of the rod control mode and its target, for the probe and the GUI.
     */
    public String controlModeText() {
        return switch (this.controlMode) {
            case MANUAL -> "manual";
            case TEMPERATURE -> "automatic, holding " + Fmt.temp(this.targetTemp);
            case POWER -> "automatic, holding " + Fmt.num(this.targetPower, 0) + "% power";
            case REDSTONE -> "redstone signal";
        };
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putDouble("temperature", this.temperature);
        output.putDouble("flux", this.flux);
        output.putDouble("decay_heat", this.decayHeat);
        output.putDouble("integrity", this.integrity);
        output.putDouble("insertion", this.insertion);
        output.putBoolean("scrammed", this.scrammed);
        output.putBoolean("pressurized", this.pressurized);
        output.putBoolean("auto_scram", this.autoScram);
        output.putDouble("scram_temp", this.scramTemp);
        output.putBoolean("relief_valve", this.reliefValve);
        output.putString("control_mode", this.controlMode.name());
        output.putDouble("target_temp", this.targetTemp);
        output.putDouble("target_power", this.targetPower);
        this.water.save(output, "water");
        this.steam.save(output, "steam");
        this.xenon.save(output);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.temperature = input.getDoubleOr("temperature", 20.0);
        this.flux = input.getDoubleOr("flux", 0.0);
        this.decayHeat = input.getDoubleOr("decay_heat", 0.0);
        this.integrity = input.getDoubleOr("integrity", 100.0);
        this.insertion = input.getDoubleOr("insertion", 100.0);
        this.scrammed = input.getBooleanOr("scrammed", true);
        this.pressurized = input.getBooleanOr("pressurized", false);
        this.autoScram = input.getBooleanOr("auto_scram", true);
        this.scramTemp = input.getDoubleOr("scram_temp", NuclearConfig.get().defaultScramTemp);
        this.reliefValve = input.getBooleanOr("relief_valve", true);
        this.controlMode = ControlMode.byName(input.getStringOr("control_mode", "MANUAL"));
        this.targetTemp = input.getDoubleOr("target_temp", 400.0);
        this.targetPower = input.getDoubleOr("target_power", 50.0);
        this.water.load(input, "water");
        this.steam.load(input, "steam");
        this.xenon.load(input);
        this.structureDirty = true;
    }

    /**
     * Who moves the control rods.
     */
    public enum ControlMode {
        MANUAL("Manual", "You set the insertion with the buttons."),
        TEMPERATURE("Hold temperature", "Rods move to keep the core at the target temperature."),
        POWER("Hold power", "Rods move to keep fission power at a % of the fuel's nominal power."),
        REDSTONE("Redstone", "Signal strength sets the rods: 0 = fully in, 15 = fully out.");

        private final String title;
        private final String description;

        ControlMode(String title, String description) {
            this.title = title;
            this.description = description;
        }

        public String title() {
            return this.title;
        }

        public String description() {
            return this.description;
        }

        public static ControlMode byName(String name) {
            for (ControlMode mode : values()) {
                if (mode.name().equalsIgnoreCase(name)) return mode;
            }
            return MANUAL;
        }
    }
}
