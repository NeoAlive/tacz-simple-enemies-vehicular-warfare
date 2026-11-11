package com.neoalive.tacz_sewv.inventory;

import java.util.List;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.SlotItemHandler;

import com.neoalive.tacz_sewv.block.ZoneBeaconBlockEntity;
import com.neoalive.tacz_sewv.fpas.RadarContact;
import com.neoalive.tacz_sewv.fpas.SignalOrders;
import com.neoalive.tacz_sewv.init.ModBlocks;
import com.neoalive.tacz_sewv.init.ModMenus;

/** Battery slot, radius stepper, mode toggle, MTI attack buttons and status readout of the FPAS. */
public class ZoneBeaconMenu extends AbstractContainerMenu {

    public static final int BUTTON_SMALLER = 0;
    public static final int BUTTON_LARGER = 1;
    public static final int BUTTON_MODE = 2;
    /** Attack buttons are this plus the contact class ordinal. */
    public static final int BUTTON_ATTACK = 3;
    public static final int DATA_COUNT = 10;
    /** Slot positions are the grid of {@code textures/gui/fpas_inventory.png}. */
    public static final int SLOT_X = 150;
    public static final int SLOT_Y = 80;
    public static final int INV_X = 7;
    public static final int INV_Y = 119;

    private final ContainerLevelAccess access;
    private final ContainerData data;
    private final ZoneBeaconBlockEntity beacon;

    public ZoneBeaconMenu(int id, Inventory inv, FriendlyByteBuf buf) {
        this(id, inv, inv.player.level().getBlockEntity(buf.readBlockPos()), new SimpleContainerData(DATA_COUNT));
    }

    public ZoneBeaconMenu(int id, Inventory inv, BlockEntity be, ContainerData data) {
        super(ModMenus.ZONE_BEACON.get(), id);
        this.beacon = be instanceof ZoneBeaconBlockEntity b ? b : null;
        this.access = be != null ? ContainerLevelAccess.create(be.getLevel(), be.getBlockPos())
                : ContainerLevelAccess.NULL;
        this.data = data;
        ItemStackHandler slot = this.beacon != null ? this.beacon.battery() : new ItemStackHandler(1);
        this.addSlot(new SlotItemHandler(slot, 0, SLOT_X, SLOT_Y));
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 9; col++)
                this.addSlot(new Slot(inv, col + row * 9 + 9, INV_X + col * 18, INV_Y + row * 18));
        for (int col = 0; col < 9; col++) this.addSlot(new Slot(inv, col, INV_X + col * 18, INV_Y + 58));
        this.addDataSlots(data);
    }

    public int energy() {
        return (this.data.get(0) & 0xFFFF) | (this.data.get(1) << 16);
    }

    public int capacity() {
        return (this.data.get(2) & 0xFFFF) | (this.data.get(3) << 16);
    }

    public int radius() {
        return this.data.get(4);
    }

    public boolean powered() {
        return this.data.get(5) != 0;
    }

    public int upkeep() {
        return (this.data.get(6) & 0xFFFF) | (this.data.get(7) << 16);
    }

    public int maxRadius() {
        return this.data.get(8);
    }

    public ZoneBeaconBlockEntity.Mode mode() {
        return this.data.get(9) == 1 ? ZoneBeaconBlockEntity.Mode.MTI : ZoneBeaconBlockEntity.Mode.ECM;
    }

    public boolean isFor(ZoneBeaconBlockEntity be) {
        return this.beacon == be;
    }

    @javax.annotation.Nullable
    public ZoneBeaconBlockEntity beacon() {
        return this.beacon;
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (this.beacon == null || !this.beacon.mayEdit(player)) return false;
        return switch (id) {
            case BUTTON_SMALLER -> this.beacon.stepRadius(-1);
            case BUTTON_LARGER -> this.beacon.stepRadius(1);
            case BUTTON_MODE -> {
                this.beacon.toggleMode();
                yield true;
            }
            default -> {
                int cls = id - BUTTON_ATTACK;
                if (cls < 0 || cls >= RadarContact.Cls.values().length || !(player instanceof ServerPlayer sp)) {
                    yield false;
                }
                SignalOrders.attack(sp, List.of(this.beacon), RadarContact.Cls.values()[cls]);
                yield true;
            }
        };
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(this.access, player, ModBlocks.ZONE_BEACON.get());
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();
        if (index == 0) {
            if (!this.moveItemStackTo(stack, 1, this.slots.size(), true)) return ItemStack.EMPTY;
        } else if (!this.moveItemStackTo(stack, 0, 1, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return copy;
    }
}
