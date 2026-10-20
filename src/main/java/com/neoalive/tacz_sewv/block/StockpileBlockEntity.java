package com.neoalive.tacz_sewv.block;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.ItemStackHandler;

import com.neoalive.tacz_sewv.init.ModBlockEntities;
import com.neoalive.tacz_sewv.inventory.StockpileMenu;

public class StockpileBlockEntity extends BlockEntity implements MenuProvider {

    public static final int SIZE = 72;

    private final ItemStackHandler items = new ItemStackHandler(SIZE) {
        @Override
        protected void onContentsChanged(int slot) {
            StockpileBlockEntity.this.setChanged();
        }
    };

    public StockpileBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.STOCKPILE.get(), pos, state);
    }

    // NeoForge port: the item-handler capability is registered in TaczSewv.registerCapabilities.

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.tacz_sewv.stockpile");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int id, Inventory playerInventory, Player player) {
        return new StockpileMenu(id, playerInventory, this);
    }

    public ItemStackHandler getItems() {
        return this.items;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("items", this.items.serializeNBT(registries));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.items.deserializeNBT(registries, tag.getCompound("items"));
    }
}
