package com.neoalive.tacz_sewv.fob;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.builder.AmmoItemBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.block.StockpileBlockEntity;
import com.neoalive.tacz_sewv.spawn.TankSpawner;

/**
 * Auto-resupply from the FOB stockpile: SBW vehicle ammo ({@link TankSpawner#resolveEligibleAmmo})
 * plus TACZ reserve ammo for assigned PMC infantry ({@link #resolveEligibleTaczAmmo}).
 */
public final class FobResupplySupport {

    /** PMC storage begins at slot 6 — equipment occupies 0-5 (see {@link com.neoalive.tacz_sewv.spawn.EmplacementSpawner}). */
    private static final int PMC_FIRST_STORAGE_SLOT = 6;

    private FobResupplySupport() {}

    @Nullable
    public static BlockPos resupplyDestination(AbstractUnit unit, @Nullable VehicleEntity vehicle) {
        if (!(unit.level() instanceof ServerLevel level)) return null;
        if (FobSupport.hasRoutePending(unit)) return null;
        // This goal holds MOVE, so without yielding here a unit that decided it wanted ammo simply
        // ignored the player's click for as long as the stockpile had stock.
        if (FobSupport.underPlayerMoveOrder(unit)) return null;
        if (unit.getTarget() != null) return null;

        FobInstance fob = activeFob(unit, level);
        if (fob == null || fob.scrambleActive || fob.stockpilePos == null) return null;

        ResupplyTarget target = resolveTarget(unit, vehicle, fob, level);
        if (target == null || target.eligible().isEmpty()) return null;
        if (!needsResupply(target)) return null;
        if (!stockpileHasEligible(fob, level, target.eligible())) return null;
        if (withinStockpile(fob, unit, level)) return null;

        return fob.stockpilePos;
    }

    public static boolean shouldResupply(AbstractUnit unit, @Nullable VehicleEntity vehicle) {
        if (!(unit.level() instanceof ServerLevel level)) return false;
        if (FobSupport.hasRoutePending(unit)) return false;
        if (FobSupport.underPlayerMoveOrder(unit)) return false;
        if (unit.getTarget() != null) return false;

        FobInstance fob = activeFob(unit, level);
        if (fob == null || fob.scrambleActive || fob.stockpilePos == null) return false;

        ResupplyTarget target = resolveTarget(unit, vehicle, fob, level);
        if (target == null || target.eligible().isEmpty()) return false;
        if (!needsResupply(target)) return false;
        if (!stockpileHasEligible(fob, level, target.eligible())) return false;
        return withinStockpile(fob, unit, level);
    }

    /** True while a unit is at the stockpile and still has room for eligible ammo. */
    public static boolean holdingForResupply(AbstractUnit unit, @Nullable VehicleEntity vehicle) {
        return shouldResupply(unit, vehicle);
    }

    /** Transfers eligible stacks from the stockpile while the unit holds still in range. */
    public static boolean tickResupply(AbstractUnit unit, @Nullable VehicleEntity vehicle) {
        if (!(unit.level() instanceof ServerLevel level)) return false;

        FobInstance fob = activeFob(unit, level);
        if (fob == null || fob.stockpilePos == null) return false;
        if (!withinStockpile(fob, unit, level)) return false;

        StockpileBlockEntity stockpile = stockpileAt(fob, level);
        if (stockpile == null) return false;

        ResupplyTarget target = resolveTarget(unit, vehicle, fob, level);
        if (target == null || target.eligible().isEmpty()) return false;

        boolean moved = false;
        IItemHandler dest = target.handler();
        if (dest == null) return false;

        List<AmmoKind> eligible = target.eligible();
        ItemStackHandlerLoop:
        for (int slot = 0; slot < StockpileBlockEntity.SIZE; slot++) {
            ItemStack stack = stockpile.getItems().getStackInSlot(slot);
            AmmoKind kind = match(eligible, stack);
            if (kind == null) continue;
            if (!canAcceptMore(dest, kind)) continue;

            ItemStack extracted = stockpile.getItems().extractItem(slot, stack.getMaxStackSize(), false);
            if (extracted.isEmpty()) continue;

            ItemStack remainder = ItemHandlerHelper.insertItemStacked(dest, extracted, false);
            if (!remainder.isEmpty()) {
                stockpile.getItems().insertItem(slot, remainder, false);
            }
            moved = true;
            FobDebug.logEntity(unit, "resupplied {} x{}", extracted.getItem(), extracted.getCount());
            if (!needsResupply(target)) break ItemStackHandlerLoop;
        }
        return moved;
    }

    public static boolean withinStockpile(FobInstance fob, Entity entity, ServerLevel level) {
        AABB box = fob.cachedStockpileAabb;
        if (box == null) {
            FobSupport.refreshCachedAabbs(fob, level);
            box = fob.cachedStockpileAabb;
        }
        if (box == null) return false;
        return box.inflate(0.5).contains(entity.getX(), entity.getY(), entity.getZ());
    }

    @Nullable
    private static FobInstance activeFob(AbstractUnit unit, ServerLevel level) {
        if (!FobSupport.isStamped(unit)) return null;
        FobInstance fob = FobSupport.fobForEntity(unit, level);
        if (fob == null || !fob.fobCommandActive) return null;
        if (!fob.assignedLiving.contains(unit.getUUID())) return null;
        return fob;
    }

    @Nullable
    private static StockpileBlockEntity stockpileAt(FobInstance fob, ServerLevel level) {
        if (fob.stockpilePos == null) return null;
        BlockEntity be = level.getBlockEntity(fob.stockpilePos);
        return be instanceof StockpileBlockEntity stock ? stock : null;
    }

    private static boolean stockpileHasEligible(FobInstance fob, ServerLevel level, List<AmmoKind> eligible) {
        StockpileBlockEntity stockpile = stockpileAt(fob, level);
        if (stockpile == null) return false;
        for (int slot = 0; slot < StockpileBlockEntity.SIZE; slot++) {
            if (match(eligible, stockpile.getItems().getStackInSlot(slot)) != null) return true;
        }
        return false;
    }

    @Nullable
    private static AmmoKind match(List<AmmoKind> eligible, ItemStack stack) {
        if (stack.isEmpty()) return null;
        for (AmmoKind kind : eligible) {
            if (kind.matches(stack)) return kind;
        }
        return null;
    }

    @Nullable
    private static ResupplyTarget resolveTarget(AbstractUnit unit, @Nullable VehicleEntity vehicle,
                                                 FobInstance fob, ServerLevel level) {
        VehicleEntity hull = vehicle;
        if (hull == null && unit.isPassenger() && unit.getVehicle() instanceof VehicleEntity mounted) {
            hull = mounted;
        }
        if (hull != null && fob.assignedVehicles.contains(hull.getUUID())) {
            List<AmmoKind> eligible = new ArrayList<>();
            addItems(eligible, TankSpawner.resolveEligibleAmmo(hull));
            return new ResupplyTarget(eligible, hullContainerHandler(hull));
        }
        if (unit instanceof PmcUnitEntity pmc) {
            List<AmmoKind> eligible = eligibleForInfantry(pmc, fob, level);
            IItemHandler inv = unit.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null);
            if (inv == null || eligible.isEmpty()) return null;
            return new ResupplyTarget(eligible, new PmcStorageView(inv));
        }
        return null;
    }

    /**
     * TACZ reserve ammo an infantry PMC consumes — resolved from whichever hand or slot 0
     * holds an {@link IGun}, same index path SEM uses when equipping loadouts.
     */
    private static List<AmmoKind> resolveEligibleTaczAmmo(PmcUnitEntity pmc) {
        List<AmmoKind> out = new ArrayList<>();
        collectTaczAmmoFromGun(pmc.getMainHandItem(), out);
        collectTaczAmmoFromGun(pmc.getOffhandItem(), out);
        pmc.getCapability(ForgeCapabilities.ITEM_HANDLER).ifPresent(handler -> {
            if (handler.getSlots() > 0) {
                collectTaczAmmoFromGun(handler.getStackInSlot(0), out);
            }
        });
        return out;
    }

    private static void collectTaczAmmoFromGun(ItemStack stack, List<AmmoKind> out) {
        IGun gun = IGun.getIGunOrNull(stack);
        if (gun == null || gun.useDummyAmmo(stack)) return;

        ResourceLocation gunId = gun.getGunId(stack);
        if (gunId == null) return;

        ResourceLocation ammoId = TimelessAPI.getCommonGunIndex(gunId)
                .map(index -> index.getGunData().getAmmoId())
                .orElse(null);
        if (ammoId == null) return;

        ItemStack prototype = AmmoItemBuilder.create().setId(ammoId).setCount(1).build();
        if (prototype.isEmpty()) return;
        add(out, new AmmoKind(prototype, ammoId));
    }

    private static List<AmmoKind> eligibleForInfantry(PmcUnitEntity pmc, FobInstance fob, ServerLevel level) {
        List<AmmoKind> out = new ArrayList<>(resolveEligibleTaczAmmo(pmc));
        for (UUID id : fob.assignedVehicles) {
            if (level.getEntity(id) instanceof VehicleEntity hull) {
                addItems(out, TankSpawner.resolveEligibleAmmo(hull));
            }
        }
        return out;
    }

    private static void addItems(List<AmmoKind> out, List<Item> items) {
        for (Item item : items) {
            add(out, new AmmoKind(new ItemStack(item), null));
        }
    }

    private static void add(List<AmmoKind> out, AmmoKind kind) {
        for (AmmoKind existing : out) {
            if (existing.sameAs(kind)) return;
        }
        out.add(kind);
    }

    @Nullable
    private static IItemHandler hullContainerHandler(VehicleEntity hull) {
        if (!hull.hasContainer() || hull.getContainerSize() <= 0) return null;
        return new VehicleContainerView(hull);
    }

    /** Minimum reserve before an assigned unit walks to the stockpile (per eligible item). */
    private static final int RESUPPLY_MIN_STACKS = 2;

    private static boolean needsResupply(ResupplyTarget target) {
        IItemHandler dest = target.handler();
        if (dest == null) return false;
        for (AmmoKind kind : target.eligible()) {
            int want = kind.prototype().getMaxStackSize() * RESUPPLY_MIN_STACKS;
            if (countOf(dest, kind) < want && canAcceptMore(dest, kind)) return true;
        }
        return false;
    }

    private static int countOf(IItemHandler handler, AmmoKind kind) {
        int total = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (kind.matches(stack)) total += stack.getCount();
        }
        return total;
    }

    private static boolean canAcceptMore(IItemHandler handler, AmmoKind kind) {
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack existing = handler.getStackInSlot(slot);
            if (existing.isEmpty()) return true;
            if (kind.matches(existing) && existing.getCount() < existing.getMaxStackSize()) return true;
        }
        return false;
    }

    /**
     * One kind of ammunition the stockpile can hand over.
     *
     * <p>TACZ ships a <b>single</b> {@code tacz:ammo} item with the calibre in NBT, so matching on
     * {@link Item} alone makes every calibre interchangeable — a 5.56 rifleman would walk to the
     * stockpile and fill its pockets with .50 BMG, and then read as resupplied. {@code ammoId} is
     * the only thing that actually separates them, and {@link IAmmo} is the supported reader for
     * it (full-NBT equality would be brittle against however a stack was created). A null
     * {@code ammoId} is an ordinary SBW shell, where the item IS the identity.
     */
    private record AmmoKind(ItemStack prototype, @Nullable ResourceLocation ammoId) {

        boolean matches(ItemStack stack) {
            if (stack.isEmpty() || !stack.is(this.prototype.getItem())) return false;
            if (this.ammoId == null) return true;
            IAmmo ammo = IAmmo.getIAmmoOrNull(stack);
            return ammo != null && this.ammoId.equals(ammo.getAmmoId(stack));
        }

        boolean sameAs(AmmoKind other) {
            return this.prototype.is(other.prototype.getItem())
                    && java.util.Objects.equals(this.ammoId, other.ammoId);
        }
    }

    private record ResupplyTarget(List<AmmoKind> eligible, @Nullable IItemHandler handler) {}

    /** Vehicle container as a single {@link IItemHandler} view. */
    private static final class VehicleContainerView implements IItemHandler {

        private final VehicleEntity hull;

        private VehicleContainerView(VehicleEntity hull) {
            this.hull = hull;
        }

        @Override
        public int getSlots() {
            return this.hull.getContainerSize();
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return this.hull.getItem(slot);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (stack.isEmpty()) return stack;
            ItemStack existing = this.hull.getItem(slot);
            if (!existing.isEmpty() && !ItemStack.isSameItemSameTags(existing, stack)) {
                return stack;
            }
            int max = Math.min(stack.getMaxStackSize(), stack.getCount());
            int room = existing.isEmpty() ? max : max - existing.getCount();
            if (room <= 0) return stack;
            int move = Math.min(room, stack.getCount());
            if (!simulate) {
                if (existing.isEmpty()) {
                    this.hull.setItem(slot, stack.copyWithCount(move));
                } else {
                    existing.grow(move);
                    this.hull.setItem(slot, existing);
                }
            }
            ItemStack rem = stack.copy();
            rem.shrink(move);
            return rem;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            return 64;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return true;
        }
    }

    /** PMC pockets (slots 6+) only — keeps rifles and kits in equipment slots untouched. */
    private static final class PmcStorageView implements IItemHandler {

        private final IItemHandler backing;

        private PmcStorageView(IItemHandler backing) {
            this.backing = backing;
        }

        @Override
        public int getSlots() {
            return Math.max(0, this.backing.getSlots() - PMC_FIRST_STORAGE_SLOT);
        }

        private int map(int slot) {
            return slot + PMC_FIRST_STORAGE_SLOT;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return this.backing.getStackInSlot(map(slot));
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return this.backing.insertItem(map(slot), stack, simulate);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return this.backing.extractItem(map(slot), amount, simulate);
        }

        @Override
        public int getSlotLimit(int slot) {
            return this.backing.getSlotLimit(map(slot));
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return this.backing.isItemValid(map(slot), stack);
        }
    }
}
