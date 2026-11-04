package com.sumutiu.goingnuclear.util;

import com.sumutiu.goingnuclear.config.NuclearConfig;
import com.sumutiu.goingnuclear.reactor.CoriumBlockEntity;
import com.sumutiu.goingnuclear.reactor.FuelRods;
import com.sumutiu.goingnuclear.reactor.ReactorControllerBlockEntity;
import com.sumutiu.goingnuclear.reactor.ReactorRegistry;
import com.sumutiu.goingnuclear.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Radiation: corium and carried spent fuel harm players, running reactors leak a little (harmless, but a
 * Geiger counter picks it up), and a hazmat suit blocks 25% per piece.
 * <p>
 * Dose rates are on a 0..1 scale (1 = standing on fresh corium). A Geiger counter shows them as counts
 * per minute: background is about 20 CPM.
 */
public final class RadiationHandler {
    /**
     * Custom data key that marks leather armor as part of a hazmat suit (see the hazmat recipes).
     */
    public static final String HAZMAT_TAG = "goingnuclear_hazmat";
    public static final int HAZMAT_COLOR = 0xE8C21E;
    private static final double BACKGROUND = 0.001;
    private static final double CPM_PER_DOSE = 20000.0;
    private static final double CARRIED_ROD_DOSE = 0.03;
    private static final Map<ServerLevel, Set<BlockPos>> CORIUM = new WeakHashMap<>();
    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private RadiationHandler() {
    }

    // ---------- Sources ----------

    public static void trackCorium(ServerLevel level, BlockPos pos) {
        CORIUM.computeIfAbsent(level, _ -> new HashSet<>()).add(pos.immutable());
    }

    public static void untrackCorium(ServerLevel level, BlockPos pos) {
        Set<BlockPos> set = CORIUM.get(level);
        if (set != null) set.remove(pos);
    }

    public static void clearAll() {
        CORIUM.clear();
    }

    /**
     * Dose rate from the surroundings at a point, before any protection.
     */
    public static double doseAt(ServerLevel level, Vec3 point) {
        double dose = BACKGROUND;
        Set<BlockPos> corium = CORIUM.get(level);
        if (corium != null) {
            for (BlockPos pos : corium) {
                if (!level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof CoriumBlockEntity be)) continue;
                dose += be.doseAt(point);
            }
        }
        // A running core leaks a little radiation through its walls.
        for (ReactorControllerBlockEntity controller : ReactorRegistry.formedControllers(level)) {
            double power = controller.powerLevel();
            if (power <= 0.0) continue;
            ReactorRegistry.Bounds bounds = ReactorRegistry.bounds(level, controller.getBlockPos());
            if (bounds == null) continue;
            Vec3 center = new Vec3((bounds.min().getX() + bounds.max().getX() + 1) / 2.0, (bounds.min().getY() + bounds.max().getY() + 1) / 2.0,
                    (bounds.min().getZ() + bounds.max().getZ() + 1) / 2.0);
            double distanceSqr = center.distanceToSqr(point);
            if (distanceSqr > 48.0 * 48.0) continue;
            dose += 0.01 * Math.min(2.0, power) / (1.0 + distanceSqr / 16.0);
        }
        return dose;
    }

    // ---------- Protection ----------

    public static boolean isHazmat(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().contains(HAZMAT_TAG);
    }

    /**
     * Share of radiation blocked: 25% for each hazmat piece worn.
     */
    public static double protection(LivingEntity entity) {
        int pieces = 0;
        for (EquipmentSlot slot : ARMOR) {
            if (isHazmat(entity.getItemBySlot(slot))) pieces++;
        }
        return pieces / 4.0;
    }

    /**
     * A hazmat suit piece: dyed leather armor marked with {@link #HAZMAT_TAG}. The crafting recipes make
     * exactly the same stack; this is for the creative tab.
     */
    public static ItemStack hazmatPiece(Item leatherArmor, String name) {
        ItemStack stack = new ItemStack(leatherArmor);
        stack.set(DataComponents.ITEM_NAME, Component.literal(name));
        stack.set(DataComponents.DYED_COLOR, new DyedItemColor(HAZMAT_COLOR));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("☢ Radiation protection: 25%").withStyle(style -> style.withColor(ChatFormatting.YELLOW).withItalic(false)),
                Component.literal("Wear all four pieces for full protection").withStyle(style -> style.withColor(ChatFormatting.GRAY).withItalic(false)))));
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(HAZMAT_TAG, true);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    // ---------- Ticking ----------

    public static void tick(MinecraftServer server) {
        int tick = server.getTickCount();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.isSpectator()) continue;
            int rods = carriedRods(player);
            if (holdsGeigerCounter(player)) geigerCounter(player, tick, rods);
            if (tick % 40 == 0 && rods > 0 && !player.isCreative() && NuclearConfig.get().depletedFuelRadiation) {
                carriedFuel(player, rods);
            }
        }
    }

    private static int carriedRods(ServerPlayer player) {
        Inventory inventory = player.getInventory();
        int count = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (FuelRods.isSpent(FuelRods.tierOf(stack))) count += stack.getCount();
        }
        return count;
    }

    private static void carriedFuel(ServerPlayer player, int rods) {
        double protection = protection(player);
        double effective = rods * (1.0 - protection);
        if (effective <= 0.0) return;
        player.addEffect(new MobEffectInstance(MobEffects.HUNGER, 80, 0));
        if (effective >= 4.0) player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 80, 0));
        if (effective >= 8.0) player.addEffect(new MobEffectInstance(MobEffects.POISON, 80, 0));
        player.sendOverlayMessage(Component.literal("☢ The spent fuel you are carrying is radioactive").withStyle(ChatFormatting.YELLOW));
    }

    private static boolean holdsGeigerCounter(ServerPlayer player) {
        return player.getMainHandItem().is(ModItems.GEIGER_COUNTER) || player.getOffhandItem().is(ModItems.GEIGER_COUNTER);
    }

    /**
     * Clicks at a rate that follows the dose (background: one every few seconds) and shows the reading.
     */
    private static void geigerCounter(ServerPlayer player, int tick, int rods) {
        double dose = doseAt(player.level(), player.position().add(0.0, 1.0, 0.0)) + rods * CARRIED_ROD_DOSE;
        double cpm = dose * CPM_PER_DOSE;
        double chance = Math.min(1.0, cpm / 1200.0);
        if (player.getRandom().nextDouble() < chance) {
            Sounds.toPlayer(player, SoundEvents.NOTE_BLOCK_HAT.value(), 0.6f, 1.6f + player.getRandom().nextFloat() * 0.4f);
        }
        if (tick % 10 != 0) return;
        ChatFormatting color;
        String label;
        if (cpm < 50.0) {
            color = ChatFormatting.GREEN;
            label = "background";
        } else if (cpm < 500.0) {
            color = ChatFormatting.YELLOW;
            label = "elevated";
        } else if (cpm < 5000.0) {
            color = ChatFormatting.GOLD;
            label = "high";
        } else {
            color = ChatFormatting.RED;
            label = "DANGER";
        }
        double protection = protection(player);
        String suit = protection > 0.0 ? " · suit blocks " + Math.round(protection * 100.0) + "%" : "";
        player.sendOverlayMessage(Component.literal(String.format(Locale.ROOT, "☢ %,d CPM · %s%s", Math.round(cpm), label, suit)).withStyle(color));
    }
}
