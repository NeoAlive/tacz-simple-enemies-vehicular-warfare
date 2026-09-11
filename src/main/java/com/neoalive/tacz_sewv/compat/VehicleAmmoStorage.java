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
        IItemHandler handler = handler(hull);
        return handler != null && handler.getSlots() > 0;
    }

    public static int slots(VehicleEntity hull) {
        IItemHandler handler = handler(hull);
        return handler == null ? 0 : handler.getSlots();
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

    /** Insert with stacking; returns the remainder. */
    public static ItemStack insert(VehicleEntity hull, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return ItemStack.EMPTY;
        IItemHandler handler = handler(hull);
        if (handler == null) return stack;
        return ItemHandlerHelper.insertItemStacked(handler, stack, false);
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
        int n = mod.getSlots();
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
}
