package com.neoalive.tacz_sewv.entity.ai.support;

import java.util.Locale;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.tacz.guns.api.item.IGun;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

/**
 * Puts a PMC's role in its hands from its own inventory (Swap to) or from an item it just picked up
 * (Search equipment). The role is whatever {@link SupportRole#of} reads off the hands.
 *
 * <p>Writes go through SEM's handler only (slot 0 main hand, 1 off hand, 6-17 pockets), since the
 * handler mirrors onto the hands but never the other way. The hands are synced back into the handler
 * first: the engineer holster swaps hands directly and leaves slots 0/1 stale.
 */
public final class RoleKit {

    public static final int MAIN_SLOT = 0;
    public static final int OFF_SLOT = 1;
    public static final int FIRST_POCKET = 6;

    public enum Kit {
        INFANTRY(stack -> IGun.getIGunOrNull(stack) != null),
        MEDIC(SupportRole::isMedicalKit),
        ENGINEER(SupportRole::isRepairTool),
        COMBAT_ENGINEER(SupportRole::isShovel);

        private final Predicate<ItemStack> test;

        Kit(Predicate<ItemStack> test) {
            this.test = test;
        }

        public boolean matches(ItemStack stack) {
            return !stack.isEmpty() && this.test.test(stack);
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public String labelKey() {
            return "gui.tacz_sewv.wheel.role." + key();
        }

        /** A role tool that would beat or blur this kit's role from the off hand. */
        boolean conflicts(ItemStack stack) {
            return SupportRole.isRoleTool(stack) && !matches(stack);
        }
    }

    public enum Result { DONE, ALREADY, NO_ITEM, BUSY }

    private RoleKit() {}

    /** Mid grenade throw, drone flight or treatment: the hands are stashed or in use. */
    public static boolean busy(PmcUnitEntity pmc) {
        return UnitHolster.isThrowingGrenade(pmc)
                || pmc.getPersistentData().getBoolean(GrenadeSupport.TAG_STASH_PRESENT)
                || pmc.getPersistentData().getBoolean(DroneControl.STASH_PRESENT)
                || MedicControl.isTreating(pmc);
    }

    public static boolean holds(PmcUnitEntity pmc, Kit kit) {
        if (kit == Kit.INFANTRY) {
            return kit.matches(pmc.getMainHandItem()) && !SupportRole.isRoleTool(pmc.getOffhandItem());
        }
        return kit.matches(pmc.getMainHandItem());
    }

    /** True when the kit sits anywhere it can be swapped in from (off hand or a pocket). */
    public static boolean carries(PmcUnitEntity pmc, Kit kit) {
        IItemHandlerModifiable inv = inventory(pmc);
        return inv != null && findSource(inv, kit) >= 0;
    }

    public static Result swapTo(PmcUnitEntity pmc, Kit kit) {
        if (busy(pmc)) return Result.BUSY;
        IItemHandlerModifiable inv = inventory(pmc);
        if (inv == null) return Result.NO_ITEM;
        pmc.syncEquipmentToInventory();

        boolean moved = false;
        if (!kit.matches(inv.getStackInSlot(MAIN_SLOT))) {
            int src = findSource(inv, kit);
            if (src < 0) return Result.NO_ITEM;
            ItemStack wanted = inv.getStackInSlot(src).copy();
            ItemStack old = inv.getStackInSlot(MAIN_SLOT).copy();
            inv.setStackInSlot(src, old);
            inv.setStackInSlot(MAIN_SLOT, wanted);
            moved = true;
        }
        moved |= clearOffHand(pmc, inv, kit);
        return moved ? Result.DONE : Result.ALREADY;
    }

    /**
     * Equips one picked-up kit item into the main hand; the old main-hand item goes to a pocket, or
     * the ground when the pockets are full.
     */
    public static boolean equipPickup(PmcUnitEntity pmc, Kit kit, ItemStack one) {
        IItemHandlerModifiable inv = inventory(pmc);
        if (inv == null || one.isEmpty()) return false;
        pmc.syncEquipmentToInventory();
        ItemStack old = inv.getStackInSlot(MAIN_SLOT).copy();
        inv.setStackInSlot(MAIN_SLOT, one);
        stow(pmc, inv, old);
        clearOffHand(pmc, inv, kit);
        return true;
    }

    @Nullable
    public static IItemHandlerModifiable inventory(PmcUnitEntity pmc) {
        IItemHandler h = pmc.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null);
        return h instanceof IItemHandlerModifiable m && h.getSlots() > FIRST_POCKET ? m : null;
    }

    private static int findSource(IItemHandler inv, Kit kit) {
        if (kit.matches(inv.getStackInSlot(OFF_SLOT))) return OFF_SLOT;
        for (int slot = FIRST_POCKET; slot < inv.getSlots(); slot++) {
            if (kit.matches(inv.getStackInSlot(slot))) return slot;
        }
        return -1;
    }

    private static boolean clearOffHand(PmcUnitEntity pmc, IItemHandlerModifiable inv, Kit kit) {
        ItemStack off = inv.getStackInSlot(OFF_SLOT);
        if (!kit.conflicts(off)) return false;
        ItemStack copy = off.copy();
        inv.setStackInSlot(OFF_SLOT, ItemStack.EMPTY);
        stow(pmc, inv, copy);
        return true;
    }

    private static void stow(PmcUnitEntity pmc, IItemHandlerModifiable inv, ItemStack stack) {
        if (stack.isEmpty()) return;
        for (int slot = FIRST_POCKET; slot < inv.getSlots() && !stack.isEmpty(); slot++) {
            stack = inv.insertItem(slot, stack, false);
        }
        if (!stack.isEmpty()) {
            Containers.dropItemStack(pmc.level(), pmc.getX(), pmc.getY(), pmc.getZ(), stack);
        }
    }
}
