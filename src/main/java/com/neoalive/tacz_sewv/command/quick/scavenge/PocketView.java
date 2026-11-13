package com.neoalive.tacz_sewv.command.quick.scavenge;

import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandlerModifiable;

import com.neoalive.tacz_sewv.entity.ai.support.RoleKit;

/** A PMC's pockets (slot 6 up) as their own handler, so inserts never land in the hands or armour. */
final class PocketView implements IItemHandlerModifiable {

    private final IItemHandlerModifiable backing;

    PocketView(IItemHandlerModifiable backing) {
        this.backing = backing;
    }

    @Override
    public int getSlots() {
        return Math.max(0, this.backing.getSlots() - RoleKit.FIRST_POCKET);
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        return this.backing.getStackInSlot(slot + RoleKit.FIRST_POCKET);
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        return this.backing.insertItem(slot + RoleKit.FIRST_POCKET, stack, simulate);
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        return this.backing.extractItem(slot + RoleKit.FIRST_POCKET, amount, simulate);
    }

    @Override
    public int getSlotLimit(int slot) {
        return this.backing.getSlotLimit(slot + RoleKit.FIRST_POCKET);
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return this.backing.isItemValid(slot + RoleKit.FIRST_POCKET, stack);
    }

    @Override
    public void setStackInSlot(int slot, ItemStack stack) {
        this.backing.setStackInSlot(slot + RoleKit.FIRST_POCKET, stack);
    }

    boolean full() {
        int n = getSlots();
        int[] counts = new int[n];
        int[] maxes = new int[n];
        for (int i = 0; i < n; i++) {
            ItemStack s = getStackInSlot(i);
            counts[i] = s.getCount();
            maxes[i] = s.isEmpty() ? 1 : Math.min(s.getMaxStackSize(), getSlotLimit(i));
        }
        return ScavengeRules.pocketsFull(counts, maxes);
    }
}
