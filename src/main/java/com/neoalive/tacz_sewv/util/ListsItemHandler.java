package com.neoalive.tacz_sewv.util;

import java.util.List;

import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandlerModifiable;

/** Several fixed-size stack lists read and written as one handler, in order. Writes go through {@code set}. */
public final class ListsItemHandler implements IItemHandlerModifiable {

    private final List<NonNullList<ItemStack>> lists;
    private final Runnable onChange;

    public ListsItemHandler(List<NonNullList<ItemStack>> lists, Runnable onChange) {
        this.lists = lists;
        this.onChange = onChange;
    }

    @Override
    public int getSlots() {
        int n = 0;
        for (NonNullList<ItemStack> list : this.lists) n += list.size();
        return n;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        for (NonNullList<ItemStack> list : this.lists) {
            if (slot < list.size()) return list.get(slot);
            slot -= list.size();
        }
        return ItemStack.EMPTY;
    }

    @Override
    public void setStackInSlot(int slot, ItemStack stack) {
        for (NonNullList<ItemStack> list : this.lists) {
            if (slot < list.size()) {
                list.set(slot, stack);
                this.onChange.run();
                return;
            }
            slot -= list.size();
        }
    }

    /** Take-only: nothing is ever put back into a body. */
    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        return stack;
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        ItemStack in = getStackInSlot(slot);
        if (in.isEmpty() || amount <= 0) return ItemStack.EMPTY;
        int n = Math.min(amount, in.getCount());
        ItemStack out = in.copyWithCount(n);
        if (!simulate) {
            ItemStack rest = in.copy();
            rest.shrink(n);
            setStackInSlot(slot, rest.isEmpty() ? ItemStack.EMPTY : rest);
        }
        return out;
    }

    @Override
    public int getSlotLimit(int slot) {
        return 64;
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return false;
    }
}
