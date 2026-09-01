package com.neoalive.tacz_sewv.inventory;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.SlotItemHandler;

import com.neoalive.tacz_sewv.block.StockpileBlockEntity;
import com.neoalive.tacz_sewv.init.ModMenus;

public class StockpileMenu extends AbstractContainerMenu {

    private final StockpileBlockEntity stockpile;
    private final ContainerLevelAccess access;

    public StockpileMenu(int id, Inventory playerInv, FriendlyByteBuf buf) {
        this(id, playerInv, playerInv.player.level().getBlockEntity(buf.readBlockPos()));
    }

    public StockpileMenu(int id, Inventory playerInv, BlockEntity be) {
        super(ModMenus.STOCKPILE.get(), id);
        this.stockpile = (StockpileBlockEntity) be;
        this.access = ContainerLevelAccess.create(this.stockpile.getLevel(), this.stockpile.getBlockPos());
        IItemHandler handler = this.stockpile.getItems();
        int slot = 0;
        for (int row = 0; row < 9; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new SlotItemHandler(handler, slot++, 8 + col * 18, 18 + row * 18));
            }
        }
        int playerInvY = 18 + 9 * 18 + 14;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInv, col + row * 9 + 9, 8 + col * 18, playerInvY + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInv, col, 8 + col * 18, playerInvY + 58));
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(this.access, player, this.stockpile.getBlockState().getBlock());
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack empty = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) return empty;
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();
        int stockSlots = StockpileBlockEntity.SIZE;
        if (index < stockSlots) {
            if (!this.moveItemStackTo(stack, stockSlots, this.slots.size(), true)) return empty;
        } else if (!this.moveItemStackTo(stack, 0, stockSlots, false)) {
            return empty;
        }
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return copy;
    }
}
