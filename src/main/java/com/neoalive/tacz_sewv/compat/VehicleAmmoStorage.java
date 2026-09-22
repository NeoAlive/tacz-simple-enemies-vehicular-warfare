package com.neoalive.tacz_sewv.compat;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.ItemHandlerHelper;

import com.neoalive.tacz_sewv.util.RandomUtil;

/**
 * Stock / read / clear the inventory channel vehicle guns actually use:
 * {@link ForgeCapabilities#ITEM_HANDLER}.
 *
 * <p>Vanilla SBW exposes its container there. Addon packs that replace the hold (notably FCP's
 * {@code VehicleInventory}) override the same capability, so writing through
 * {@code hasContainer}/{@code setItem}/{@code getItems} fills a dead SBW container while guns
 * stay dry. One helper keeps spawn stock, board restock, FOB resupply and unlock loot on the
 * live channel without naming any addon class.
 */
public final class VehicleAmmoStorage {

    private VehicleAmmoStorage() {
    }

    @Nullable
    public static IItemHandler handler(VehicleEntity hull) {
        if (hull == null) return null;
        return hull.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve().orElse(null);
    }

    public static boolean hasStorage(VehicleEntity hull) {
        return containerSlots(hull) > 0;
    }

    /**
     * The vehicle's REAL, savable slot count — never the raw {@code getSlots()} of the underlying
     * handler. SBW builds every hull's container at a fixed max (102 slots, {@code 6 * 17}) and
     * only shrinks it to the hull's actual {@code getContainerSize()} the first time the entity is
     * written to NBT ({@code VehicleEntity.resizeItems()}, called from both
     * {@code add}/{@code readAdditionalSaveData} — i.e. on the next world autosave or chunk
     * unload, not at spawn). Anything sitting past the real size at that point is dropped on the
     * ground by SBW itself. A freshly spawned/crewed hull's handler still reports the full 102
     * until then, so filling "every slot the handler has" silently overstocked hulls whose real
     * container is far smaller (a helicopter's few ammo slots, say) and dumped the rest the moment
     * the world next saved — independent of anything this mod tracks, FOB included.
     */
    public static int containerSlots(VehicleEntity hull) {
        if (hull == null) return 0;
        IItemHandler handler = handler(hull);
        if (handler == null) return 0;
        return Math.max(0, Math.min(handler.getSlots(), hull.getContainerSize()));
    }

    public static boolean isEmpty(VehicleEntity hull) {
        IItemHandler handler = handler(hull);
        if (handler == null) return true;
        for (int i = 0; i < handler.getSlots(); i++) {
            if (!handler.getStackInSlot(i).isEmpty()) return false;
        }
        return true;
    }

    /** Overwrite one slot when the handler is modifiable; otherwise no-op. */
    public static void setStack(VehicleEntity hull, int slot, ItemStack stack) {
        IItemHandler handler = handler(hull);
        if (!(handler instanceof IItemHandlerModifiable mod)) return;
        if (slot < 0 || slot >= mod.getSlots()) return;
        mod.setStackInSlot(slot, stack == null ? ItemStack.EMPTY : stack);
    }

    /** Insert with stacking; returns the remainder. Never touches a slot past {@link #containerSlots}. */
    public static ItemStack insert(VehicleEntity hull, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return ItemStack.EMPTY;
        IItemHandler handler = handler(hull);
        int usable = containerSlots(hull);
        if (handler == null || usable <= 0) return stack;
        return ItemHandlerHelper.insertItemStacked(new BoundedView(handler, usable), stack, false);
    }

    public static void clear(VehicleEntity hull) {
        IItemHandler handler = handler(hull);
        if (!(handler instanceof IItemHandlerModifiable mod)) return;
        for (int i = 0; i < mod.getSlots(); i++) {
            mod.setStackInSlot(i, ItemStack.EMPTY);
        }
    }

    /** Snapshot of every non-empty stack (copies). */
    public static List<ItemStack> copyContents(VehicleEntity hull) {
        List<ItemStack> out = new ArrayList<>();
        IItemHandler handler = handler(hull);
        if (handler == null) return out;
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack stack = handler.getStackInSlot(i);
            if (!stack.isEmpty()) out.add(stack.copy());
        }
        return out;
    }

    public static void scramble(VehicleEntity hull, RandomSource random) {
        IItemHandler handler = handler(hull);
        if (!(handler instanceof IItemHandlerModifiable mod)) return;
        // Bounded, not mod.getSlots(): shuffling across the raw 102 could carry a stack that is
        // sitting in a real, savable slot out into one the next save wipes.
        int n = containerSlots(hull);
        if (n <= 1) return;
        ItemStack[] slots = new ItemStack[n];
        for (int i = 0; i < n; i++) {
            slots[i] = mod.getStackInSlot(i);
        }
        RandomUtil.shuffle(java.util.Arrays.asList(slots), random);
        for (int i = 0; i < n; i++) {
            mod.setStackInSlot(i, slots[i] == null ? ItemStack.EMPTY : slots[i]);
        }
    }

    /** {@code backing} with its slot count capped — keeps {@link ItemHandlerHelper} off the tail. */
    private static final class BoundedView implements IItemHandler {
        private final IItemHandler backing;
        private final int slots;

        BoundedView(IItemHandler backing, int slots) {
            this.backing = backing;
            this.slots = slots;
        }

        @Override
        public int getSlots() {
            return this.slots;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return this.backing.getStackInSlot(slot);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return this.backing.insertItem(slot, stack, simulate);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return this.backing.extractItem(slot, amount, simulate);
        }

        @Override
        public int getSlotLimit(int slot) {
            return this.backing.getSlotLimit(slot);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return this.backing.isItemValid(slot, stack);
        }
    }
}
