package com.neoalive.tacz_sewv.block;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.item.projectile.MortarShellItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import com.neoalive.tacz_sewv.init.ModBlockEntities;

/**
 * Nine-slot ammo crate for an {@link EmplacementBlock}. Accepts mortar shells now; missiles later.
 */
public class EmplacementBlockEntity extends BlockEntity implements WorldlyContainer, MenuProvider {

    public static final int SIZE = 9;

    private static final int[] SLOTS = new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8};

    private NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);

    public EmplacementBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.EMPLACEMENT.get(), pos, state);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.tacz_sewv.emplacement");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int id, Inventory playerInventory, Player player) {
        return new ChestMenu(MenuType.GENERIC_9x1, id, playerInventory, this, 1);
    }

    @Override
    public int getContainerSize() {
        return SIZE;
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack stack : this.items) {
            if (!stack.isEmpty()) return false;
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return this.items.get(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack result = ContainerHelper.removeItem(this.items, slot, amount);
        if (!result.isEmpty()) setChanged();
        return result;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ContainerHelper.takeItem(this.items, slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        this.items.set(slot, stack);
        if (stack.getCount() > getMaxStackSize()) {
            stack.setCount(getMaxStackSize());
        }
        setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        if (this.level == null || this.level.getBlockEntity(this.worldPosition) != this) return false;
        return player.distanceToSqr(
                this.worldPosition.getX() + 0.5,
                this.worldPosition.getY() + 0.5,
                this.worldPosition.getZ() + 0.5) <= 64.0;
    }

    @Override
    public void clearContent() {
        this.items.clear();
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return stack.getItem() instanceof MortarShellItem || isMissileLike(stack);
    }

    private static boolean isMissileLike(ItemStack stack) {
        var key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (key == null) return false;
        String path = key.getPath();
        return path.contains("missile") || path.contains("tow") || path.contains("javelin")
                || path.contains("rpg");
    }

    public ItemStack extractShell(int count) {
        for (int slot = 0; slot < SIZE; slot++) {
            ItemStack stack = this.items.get(slot);
            if (stack.getItem() instanceof MortarShellItem) {
                ItemStack taken = removeItem(slot, count);
                setChanged();
                return taken;
            }
        }
        return ItemStack.EMPTY;
    }

    /** Pull one stack matching {@code test} (TOW missile AmmoConsumer, etc.). */
    public ItemStack extractMatching(int count, java.util.function.Predicate<ItemStack> test) {
        for (int slot = 0; slot < SIZE; slot++) {
            ItemStack stack = this.items.get(slot);
            if (!stack.isEmpty() && test.test(stack)) {
                ItemStack taken = removeItem(slot, count);
                setChanged();
                return taken;
            }
        }
        return ItemStack.EMPTY;
    }

    public void insertOrDrop(ItemStack stack) {
        if (stack.isEmpty()) return;
        ItemStack remaining = stack.copy();
        for (int slot = 0; slot < SIZE && !remaining.isEmpty(); slot++) {
            if (!canPlaceItem(slot, remaining)) continue;
            ItemStack inSlot = this.items.get(slot);
            if (inSlot.isEmpty()) {
                int put = Math.min(remaining.getCount(), remaining.getMaxStackSize());
                this.items.set(slot, remaining.split(put));
            } else if (ItemStack.isSameItemSameTags(inSlot, remaining)
                    && inSlot.getCount() < inSlot.getMaxStackSize()) {
                int space = inSlot.getMaxStackSize() - inSlot.getCount();
                int put = Math.min(space, remaining.getCount());
                inSlot.grow(put);
                remaining.shrink(put);
            }
        }
        setChanged();
        if (!remaining.isEmpty() && this.level != null && !this.level.isClientSide) {
            net.minecraft.world.Containers.dropItemStack(
                    this.level, this.worldPosition.getX() + 0.5, this.worldPosition.getY() + 1.0,
                    this.worldPosition.getZ() + 0.5, remaining);
        }
    }

    @Override
    public int[] getSlotsForFace(Direction side) {
        return SLOTS;
    }

    @Override
    public boolean canPlaceItemThroughFace(int index, ItemStack stack, @Nullable Direction direction) {
        return canPlaceItem(index, stack);
    }

    @Override
    public boolean canTakeItemThroughFace(int index, ItemStack stack, Direction direction) {
        return true;
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        this.items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(tag, this.items);
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        ContainerHelper.saveAllItems(tag, this.items);
    }
}
