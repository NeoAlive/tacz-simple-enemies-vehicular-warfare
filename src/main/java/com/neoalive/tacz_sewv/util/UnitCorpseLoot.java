package com.neoalive.tacz_sewv.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.init.ModItems;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IAmmoBox;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.nbt.AmmoBoxItemDataAccessor;
import com.tacz.guns.api.item.nbt.GunItemDataAccessor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.config.SewvConfig;

/**
 * SEWV-side corpse fill math (no CorpseMod types). RU/US roll datapack tables under
 * {@code tacz_sewv:units/corpses/<faction>} and Fisher–Yates-scramble into a 36-slot main grid;
 * PMC snapshots come from {@link ForgeCapabilities#ITEM_HANDLER}.
 *
 * <p>Every stack is passed through {@link #sanitizeLootStack} so AI dummy/virtual ammo never
 * reaches the player as infinite reserve.
 */
public final class UnitCorpseLoot {

    public static final int MAIN_SLOTS = 36;
    public static final int ARMOR_SLOTS = 4;

    /** PMC handler: 0 main, 1 offhand, 2–5 FEET..HEAD, 6+ storage. */
    private static final int PMC_MAIN = 0;
    private static final int PMC_OFF = 1;
    private static final int PMC_ARMOR_START = 2;
    private static final int PMC_STORAGE_START = 6;

    private UnitCorpseLoot() {
    }

    /**
     * Snapshot of everything that should land in a Corpse {@code Death} inventory.
     * Arrays are never null; empty slots are {@link ItemStack#EMPTY}.
     */
    public record Fill(
            ItemStack[] main,
            ItemStack[] armor,
            ItemStack[] offhand,
            List<ItemStack> additional,
            ItemStack[] equipment) {

        public boolean isVisiblyEmpty() {
            return allEmpty(main) && allEmpty(armor) && allEmpty(offhand)
                    && additional.isEmpty() && allEmpty(equipment);
        }

        private static boolean allEmpty(ItemStack[] slots) {
            for (ItemStack stack : slots) {
                if (stack != null && !stack.isEmpty()) return false;
            }
            return true;
        }
    }

    /** Deep-copied PMC handler + equipment, taken before SEM empties pockets into drops. */
    public record PmcSnapshot(ItemStack[] handlerSlots, ItemStack[] equipment) {
    }

    /**
     * Strip AI-only infinite ammo state before a stack enters a corpse inventory.
     *
     * <ul>
     *   <li>TaCZ: remove {@code DummyAmmo}/{@code MaxDummyAmmo}; clamp magazine to datapack max</li>
     *   <li>TaCZ ammo boxes: clear creative flags</li>
     *   <li>SBW guns: zero {@code virtualAmmo}</li>
     *   <li>SBW creative ammo box: discarded</li>
     * </ul>
     */
    public static ItemStack sanitizeLootStack(ItemStack source) {
        if (source == null || source.isEmpty()) return ItemStack.EMPTY;
        if (source.is(ModItems.CREATIVE_AMMO_BOX.get())) return ItemStack.EMPTY;

        ItemStack stack = source.copy();

        IGun iGun = IGun.getIGunOrNull(stack);
        if (iGun != null) {
            CompoundTag tag = stack.getTag();
            if (tag != null) {
                // Presence of DummyAmmo alone enables infinite-reload mode for the looter.
                tag.remove(GunItemDataAccessor.GUN_DUMMY_AMMO);
                tag.remove(GunItemDataAccessor.GUN_MAX_DUMMY_AMMO);
            }
            int mag = iGun.getCurrentAmmoCount(stack);
            int max = TimelessAPI.getCommonGunIndex(iGun.getGunId(stack))
                    .map(index -> index.getGunData().getAmmoAmount())
                    .orElse(mag);
            if (max > 0) {
                iGun.setCurrentAmmoCount(stack, Mth.clamp(mag, 0, max));
            } else if (mag > 0) {
                iGun.setCurrentAmmoCount(stack, Math.min(mag, 30));
            }
        }

        if (stack.getItem() instanceof IAmmoBox) {
            CompoundTag tag = stack.getTag();
            if (tag != null) {
                tag.remove(AmmoBoxItemDataAccessor.CREATIVE_TAG);
                tag.remove(AmmoBoxItemDataAccessor.ALL_TYPE_CREATIVE_TAG);
            }
        }

        if (stack.getItem() instanceof com.atsuishio.superbwarfare.item.gun.GunItem) {
            try {
                GunData gun = GunData.from(stack);
                if (gun != null && gun.virtualAmmo.get() != 0) {
                    gun.virtualAmmo.set(0);
                    gun.save();
                }
            } catch (Throwable ignored) {
                // GunData.from can fail on incomplete SBW stacks.
            }
        }

        return stack;
    }

    @Nullable
    public static PmcSnapshot snapshotPmc(AbstractUnit unit) {
        IItemHandler handler = unit.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve().orElse(null);
        if (handler == null) return null;
        ItemStack[] slots = new ItemStack[handler.getSlots()];
        for (int i = 0; i < handler.getSlots(); i++) {
            slots[i] = sanitizeLootStack(handler.getStackInSlot(i));
        }
        return new PmcSnapshot(slots, copyEquipment(unit));
    }

    /** Clears the PMC handler so SEM death drops / remove paths find nothing left. */
    public static void clearPmcHandler(AbstractUnit unit) {
        IItemHandler handler = unit.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve().orElse(null);
        if (!(handler instanceof IItemHandlerModifiable mod)) return;
        for (int i = 0; i < mod.getSlots(); i++) {
            mod.setStackInSlot(i, ItemStack.EMPTY);
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            unit.setItemSlot(slot, ItemStack.EMPTY);
        }
    }

    public static Fill fromPmcSnapshot(PmcSnapshot snap) {
        ItemStack[] main = empty(MAIN_SLOTS);
        ItemStack[] armor = empty(ARMOR_SLOTS);
        ItemStack[] offhand = empty(1);
        List<ItemStack> additional = new ArrayList<>();
        ItemStack[] equipment = snap.equipment() != null
                ? copyStacks(snap.equipment())
                : empty(EquipmentSlot.values().length);

        ItemStack[] slots = snap.handlerSlots();
        if (slots.length > PMC_MAIN && !slots[PMC_MAIN].isEmpty()) {
            main[0] = sanitizeLootStack(slots[PMC_MAIN]);
            putEquipment(equipment, EquipmentSlot.MAINHAND, main[0]);
        }
        if (slots.length > PMC_OFF && !slots[PMC_OFF].isEmpty()) {
            offhand[0] = sanitizeLootStack(slots[PMC_OFF]);
            putEquipment(equipment, EquipmentSlot.OFFHAND, offhand[0]);
        }
        EquipmentSlot[] armorOrder = {
                EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD
        };
        for (int i = 0; i < ARMOR_SLOTS; i++) {
            int src = PMC_ARMOR_START + i;
            if (src >= slots.length || slots[src].isEmpty()) continue;
            armor[i] = sanitizeLootStack(slots[src]);
            putEquipment(equipment, armorOrder[i], armor[i]);
        }
        for (int i = PMC_STORAGE_START; i < slots.length; i++) {
            ItemStack cleaned = sanitizeLootStack(slots[i]);
            if (!cleaned.isEmpty()) additional.add(cleaned);
        }
        mergeMissingEquipment(equipment, armor, offhand, main);
        return new Fill(main, armor, offhand, additional, equipment);
    }

    /**
     * Roll {@code tacz_sewv:units/corpses/<faction>}, scale by {@link SewvConfig#VEHICLE_DEATH_DROPS},
     * Fisher–Yates into main slots; worn gear fills armor/offhand/equipment.
     */
    public static Fill rollFaction(AbstractUnit unit, String faction, ServerLevel level) {
        ItemStack[] main = empty(MAIN_SLOTS);
        ItemStack[] armor = empty(ARMOR_SLOTS);
        ItemStack[] offhand = empty(1);
        ItemStack[] equipment = copyEquipment(unit);
        List<ItemStack> additional = new ArrayList<>();

        copyWornIntoDeathSlots(unit, armor, offhand, equipment);

        List<ItemStack> toPlace = new ArrayList<>();
        ItemStack mainHand = sanitizeLootStack(unit.getItemBySlot(EquipmentSlot.MAINHAND));
        if (!mainHand.isEmpty()) {
            toPlace.add(mainHand);
        }

        String mode = SewvConfig.VEHICLE_DEATH_DROPS.get();
        if (!"disable".equals(mode)) {
            for (ItemStack stack : rollTable(unit, faction, level)) {
                ItemStack filtered = VehicleDrops.filterStack(stack, mode);
                ItemStack cleaned = sanitizeLootStack(filtered);
                if (!cleaned.isEmpty()) toPlace.add(cleaned);
            }
        }
        placeScrambled(main, toPlace, level.getRandom(), additional);
        return new Fill(main, armor, offhand, additional, equipment);
    }

    private static List<ItemStack> rollTable(AbstractUnit unit, String faction, ServerLevel level) {
        ResourceLocation tableId = new ResourceLocation(TaczSewv.MODID, "units/corpses/" + faction);
        LootTable table = level.getServer().getLootData().getLootTable(tableId);
        if (table == LootTable.EMPTY) return List.of();
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, unit.position())
                .create(LootContextParamSets.CHEST);
        return table.getRandomItems(params);
    }

    static void placeScrambled(
            ItemStack[] main, List<ItemStack> stacks, RandomSource random, List<ItemStack> overflow) {
        int cursor = 0;
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) continue;
            if (cursor < main.length) {
                main[cursor++] = stack;
            } else {
                overflow.add(stack);
            }
        }
        List<ItemStack> asList = Arrays.asList(main);
        RandomUtil.shuffle(asList, random);
    }

    private static void copyWornIntoDeathSlots(
            LivingEntity unit, ItemStack[] armor, ItemStack[] offhand, ItemStack[] equipment) {
        ItemStack feet = sanitizeLootStack(unit.getItemBySlot(EquipmentSlot.FEET));
        ItemStack legs = sanitizeLootStack(unit.getItemBySlot(EquipmentSlot.LEGS));
        ItemStack chest = sanitizeLootStack(unit.getItemBySlot(EquipmentSlot.CHEST));
        ItemStack head = sanitizeLootStack(unit.getItemBySlot(EquipmentSlot.HEAD));
        ItemStack mainHand = sanitizeLootStack(unit.getItemBySlot(EquipmentSlot.MAINHAND));
        ItemStack off = sanitizeLootStack(unit.getItemBySlot(EquipmentSlot.OFFHAND));
        if (!feet.isEmpty()) armor[0] = feet;
        if (!legs.isEmpty()) armor[1] = legs;
        if (!chest.isEmpty()) armor[2] = chest;
        if (!head.isEmpty()) armor[3] = head;
        if (!off.isEmpty()) offhand[0] = off;
        putEquipment(equipment, EquipmentSlot.FEET, feet);
        putEquipment(equipment, EquipmentSlot.LEGS, legs);
        putEquipment(equipment, EquipmentSlot.CHEST, chest);
        putEquipment(equipment, EquipmentSlot.HEAD, head);
        putEquipment(equipment, EquipmentSlot.MAINHAND, mainHand);
        putEquipment(equipment, EquipmentSlot.OFFHAND, off);
    }

    private static void mergeMissingEquipment(
            ItemStack[] equipment, ItemStack[] armor, ItemStack[] offhand, ItemStack[] main) {
        if (equipment == null) return;
        EquipmentSlot[] armorOrder = {
                EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD
        };
        for (int i = 0; i < ARMOR_SLOTS; i++) {
            ItemStack worn = getEquipment(equipment, armorOrder[i]);
            if (armor[i].isEmpty() && !worn.isEmpty()) armor[i] = worn.copy();
        }
        ItemStack off = getEquipment(equipment, EquipmentSlot.OFFHAND);
        if (offhand[0].isEmpty() && !off.isEmpty()) offhand[0] = off.copy();
        ItemStack hand = getEquipment(equipment, EquipmentSlot.MAINHAND);
        if (main[0].isEmpty() && !hand.isEmpty()) main[0] = hand.copy();
    }

    static ItemStack[] copyEquipment(LivingEntity unit) {
        EquipmentSlot[] slots = EquipmentSlot.values();
        ItemStack[] out = empty(slots.length);
        for (int i = 0; i < slots.length; i++) {
            out[i] = sanitizeLootStack(unit.getItemBySlot(slots[i]));
        }
        return out;
    }

    private static void putEquipment(ItemStack[] equipment, EquipmentSlot slot, ItemStack stack) {
        if (equipment == null || stack == null || stack.isEmpty()) return;
        int i = slot.ordinal();
        if (i >= 0 && i < equipment.length) {
            equipment[i] = stack.copy();
        }
    }

    private static ItemStack getEquipment(ItemStack[] equipment, EquipmentSlot slot) {
        int i = slot.ordinal();
        if (i < 0 || i >= equipment.length) return ItemStack.EMPTY;
        ItemStack stack = equipment[i];
        return stack == null ? ItemStack.EMPTY : stack;
    }

    private static ItemStack[] empty(int n) {
        ItemStack[] out = new ItemStack[n];
        Arrays.fill(out, ItemStack.EMPTY);
        return out;
    }

    private static ItemStack[] copyStacks(ItemStack[] src) {
        ItemStack[] out = new ItemStack[src.length];
        for (int i = 0; i < src.length; i++) {
            out[i] = sanitizeLootStack(src[i]);
        }
        return out;
    }
}
