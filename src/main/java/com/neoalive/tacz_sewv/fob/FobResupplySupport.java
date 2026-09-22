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
import net.minecraft.world.Containers;
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
import com.neoalive.tacz_sewv.compat.VehicleAmmoStorage;
import com.neoalive.tacz_sewv.entity.ai.support.PmcDownedSupport;
import com.neoalive.tacz_sewv.spawn.TankSpawner;

/**
 * Auto-resupply from the FOB stockpile: SBW vehicle ammo ({@link TankSpawner#resolveEligibleAmmo})
 * plus TACZ reserve ammo for assigned PMC infantry ({@link #resolveEligibleTaczAmmo}).
 */
public final class FobResupplySupport {

    /** PMC storage begins at slot 6 — equipment occupies 0-5 (see {@link com.neoalive.tacz_sewv.spawn.EmplacementSpawner}). */
    private static final int PMC_FIRST_STORAGE_SLOT = 6;

    private FobResupplySupport() {}

    /** How long a periodic trip to the stockpile may run before it is abandoned (60 s). */
    private static final long REQUEST_TTL_TICKS = 1200L;
    /** Horizontal reach of the stockpile block: the pad AABB is not the only place to draw from. */
    private static final double STOCKPILE_REACH_SQ = 3.5D * 3.5D;

    /**
     * Where an on-foot unit should walk to draw ammo, or null. Stale-safe by construction: an
     * on-foot trip only exists while the periodic dispatch has a live request for the unit, and the
     * request is dropped the moment the stockpile cannot serve it (or after the pull, or on expiry).
     */
    @Nullable
    public static BlockPos resupplyDestination(AbstractUnit unit, @Nullable VehicleEntity vehicle) {
        FobInstance fob = tripFob(unit, vehicle);
        if (fob == null) return null;
        ServerLevel level = (ServerLevel) unit.level();
        if (servable(unit, vehicle, fob, level) == null) return null;
        if (withinStockpile(fob, unit, level)) return null;
        return fob.stockpilePos;
    }

    public static boolean shouldResupply(AbstractUnit unit, @Nullable VehicleEntity vehicle) {
        FobInstance fob = tripFob(unit, vehicle);
        if (fob == null) return false;
        ServerLevel level = (ServerLevel) unit.level();
        if (servable(unit, vehicle, fob, level) == null) return false;
        return withinStockpile(fob, unit, level);
    }

    /** True while a unit is at the stockpile and still has room for eligible ammo. */
    public static boolean holdingForResupply(AbstractUnit unit, @Nullable VehicleEntity vehicle) {
        return shouldResupply(unit, vehicle);
    }

    /**
     * Common gate for a resupply trip: the FOB this unit may draw from, or null. An on-foot unit
     * additionally needs a live periodic request; a hull keeps its own park-and-top-up behaviour.
     */
    @Nullable
    private static FobInstance tripFob(AbstractUnit unit, @Nullable VehicleEntity vehicle) {
        if (!(unit.level() instanceof ServerLevel level)) return null;
        if (PmcDownedSupport.isDowned(unit)) return null;
        if (FobSupport.hasRoutePending(unit)) return null;
        // This goal holds MOVE, so without yielding here a unit that decided it wanted ammo simply
        // ignored the player's click for as long as the stockpile had stock.
        if (FobSupport.underPlayerMoveOrder(unit)) return null;
        if (unit.getTarget() != null) return null;

        FobInstance fob = activeFob(unit, level);
        if (fob == null || fob.scrambleActive || fob.stockpilePos == null) return null;
        if (isOnFoot(unit, vehicle) && !hasRequest(fob, unit, level.getGameTime())) return null;
        return fob;
    }

    private static boolean isOnFoot(AbstractUnit unit, @Nullable VehicleEntity vehicle) {
        return vehicle == null && !unit.isPassenger();
    }

    private static boolean hasRequest(FobInstance fob, AbstractUnit unit, long now) {
        Long until = fob.refillRequests.get(unit.getUUID());
        if (until == null) return false;
        if (now >= until) {
            fob.refillRequests.remove(unit.getUUID());
            return false;
        }
        return true;
    }

    /**
     * The unit's resupply target if it still wants ammo the stockpile actually holds. Otherwise
     * null, and an on-foot unit's request is dropped so the trip ends instead of camping the pad.
     */
    @Nullable
    private static ResupplyTarget servable(AbstractUnit unit, @Nullable VehicleEntity vehicle,
                                           FobInstance fob, ServerLevel level) {
        ResupplyTarget target = resolveTarget(unit, vehicle, fob, level);
        if (target != null && stockpileHasEligible(fob, level, needy(target))) return target;
        if (isOnFoot(unit, vehicle)) fob.refillRequests.remove(unit.getUUID());
        return null;
    }

    /**
     * Draws ammo while the unit holds still in range, then ends the trip: an on-foot unit's request
     * is consumed whether or not anything moved, so it walks away rather than waiting for more.
     */
    public static boolean tickResupply(AbstractUnit unit, @Nullable VehicleEntity vehicle) {
        if (!(unit.level() instanceof ServerLevel level)) return false;

        FobInstance fob = activeFob(unit, level);
        if (fob == null || fob.stockpilePos == null) return false;
        if (!withinStockpile(fob, unit, level)) return false;

        boolean moved = transferFromStockpile(unit, vehicle, fob, level);
        if (isOnFoot(unit, vehicle)) fob.refillRequests.remove(unit.getUUID());
        return moved;
    }

    private static boolean transferFromStockpile(AbstractUnit unit, @Nullable VehicleEntity vehicle,
                                                 FobInstance fob, ServerLevel level) {
        StockpileBlockEntity stockpile = stockpileAt(fob, level);
        if (stockpile == null) return false;

        ResupplyTarget target = resolveTarget(unit, vehicle, fob, level);
        if (target == null || target.eligible().isEmpty()) return false;

        boolean moved = false;
        IItemHandler dest = target.handler();
        if (dest == null) return false;

        for (int slot = 0; slot < StockpileBlockEntity.SIZE; slot++) {
            // Re-evaluated per slot: a kind stops being wanted the moment it is topped up, and a
            // kind the unit cannot hold is never pulled just because the stockpile has it.
            List<AmmoKind> needy = needy(target);
            if (needy.isEmpty()) break;
            ItemStack stack = stockpile.getItems().getStackInSlot(slot);
            if (match(needy, stack) == null) continue;

            ItemStack extracted = stockpile.getItems().extractItem(slot, stack.getMaxStackSize(), false);
            if (extracted.isEmpty()) continue;

            int took = extracted.getCount();
            ItemStack remainder = ItemHandlerHelper.insertItemStacked(dest, extracted, false);
            if (!remainder.isEmpty()) {
                ItemStack leftover = ItemHandlerHelper.insertItemStacked(stockpile.getItems(), remainder, false);
                if (!leftover.isEmpty()) {
                    Containers.dropItemStack(level, unit.getX(), unit.getY(), unit.getZ(), leftover);
                }
            }
            moved = true;
            FobDebug.logEntity(unit, "resupplied {} x{}", extracted.getItem(), took - remainder.getCount());
        }
        return moved;
    }

    /**
     * Periodic Refill: once per {@link FobInstance#periodicRefillTicks}, send every assigned
     * on-foot PMC that wants ammo the stockpile holds. Nobody is sent when the stockpile has
     * nothing for them, so an empty stockpile never draws a crowd.
     */
    public static void tickPeriodic(ServerLevel level, FobInstance fob, long now) {
        if (fob.periodicRefillTicks <= 0) {
            fob.refillRequests.clear();
            return;
        }
        if (now < fob.nextPeriodicRefill) return;
        fob.nextPeriodicRefill = now + fob.periodicRefillTicks;
        fob.refillRequests.values().removeIf(until -> now >= until);
        if (!fob.fobCommandActive || fob.scrambleActive || fob.stockpilePos == null) return;
        if (!level.isLoaded(fob.stockpilePos)) return;

        for (UUID id : fob.assignedLiving) {
            if (!(level.getEntity(id) instanceof PmcUnitEntity pmc) || !pmc.isAlive()) continue;
            if (pmc.isPassenger() || PmcDownedSupport.isDowned(pmc)) continue;
            if (FobSupport.hasRoutePending(pmc) || pmc.getTarget() != null) continue;
            ResupplyTarget target = resolveTarget(pmc, null, fob, level);
            if (target == null || !stockpileHasEligible(fob, level, needy(target))) continue;
            fob.refillRequests.put(id, now + REQUEST_TTL_TICKS);
        }
    }

    /** True when the FOB stockpile holds ammo matching this PMC's own guns (Quick Refill's test). */
    public static boolean stockpileHasAmmoFor(PmcUnitEntity pmc) {
        if (!(pmc.level() instanceof ServerLevel level)) return false;
        FobInstance fob = activeFob(pmc, level);
        if (fob == null) return false;
        StockpileBlockEntity stockpile = stockpileAt(fob, level);
        return stockpile != null && handlerHasEligible(pmc, stockpile.getItems());
    }

    public static boolean withinStockpile(FobInstance fob, Entity entity, ServerLevel level) {
        AABB box = fob.cachedStockpileAabb;
        if (box == null) {
            FobSupport.refreshCachedAabbs(fob, level);
            box = fob.cachedStockpileAabb;
        }
        if (box != null && box.inflate(0.5).contains(entity.getX(), entity.getY(), entity.getZ())) return true;
        // The pad can be smaller than a crowd standing round a solid block, so being beside the
        // block counts too - otherwise a unit that cannot squeeze into the pad waits there forever.
        BlockPos pos = fob.stockpilePos;
        if (pos == null) return false;
        double dx = entity.getX() - (pos.getX() + 0.5);
        double dz = entity.getZ() - (pos.getZ() + 0.5);
        return dx * dx + dz * dz <= STOCKPILE_REACH_SQ && Math.abs(entity.getY() - pos.getY()) <= 3.0;
    }

    /**
     * True when {@code source} holds at least one stack matching the PMC's TACZ guns
     * (same match rules as stockpile / {@link #refillFromHandler}).
     */
    public static boolean handlerHasEligible(PmcUnitEntity pmc, IItemHandler source) {
        if (source == null) return false;
        List<AmmoKind> eligible = resolveEligibleTaczAmmo(pmc);
        if (eligible.isEmpty()) return false;
        for (int slot = 0; slot < source.getSlots(); slot++) {
            if (match(eligible, source.getStackInSlot(slot)) != null) return true;
        }
        return false;
    }

    /**
     * Immediate stockpile transfer for a stamped FOB unit already in the stockpile pad
     * (Quick Refill). Same path as the auto resupply goal.
     */
    public static boolean forceStockpileRefill(PmcUnitEntity pmc) {
        if (!(pmc.level() instanceof ServerLevel level)) return false;
        FobInstance fob = activeFob(pmc, level);
        if (fob == null || fob.stockpilePos == null) return false;
        boolean moved = transferFromStockpile(pmc, null, fob, level);
        fob.refillRequests.remove(pmc.getUUID());
        return moved;
    }

    /**
     * Pull TACZ ammo matching the unit's guns from any {@link IItemHandler} (chest, barrel, …)
     * into PMC storage slots 6+. Same match rules as the FOB stockpile.
     */
    public static boolean refillFromHandler(PmcUnitEntity pmc, IItemHandler source) {
        if (source == null) return false;
        IItemHandler inv = pmc.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null);
        if (inv == null) return false;
        List<AmmoKind> eligible = resolveEligibleTaczAmmo(pmc);
        if (eligible.isEmpty()) return false;
        IItemHandler dest = new PmcStorageView(inv);

        boolean moved = false;
        for (int slot = 0; slot < source.getSlots(); slot++) {
            ItemStack stack = source.getStackInSlot(slot);
            AmmoKind kind = match(eligible, stack);
            if (kind == null) continue;
            if (!canAcceptMore(dest, kind)) continue;

            ItemStack extracted = source.extractItem(slot, stack.getMaxStackSize(), false);
            if (extracted.isEmpty()) continue;
            ItemStack remainder = ItemHandlerHelper.insertItemStacked(dest, extracted, false);
            if (!remainder.isEmpty()) {
                ItemStack leftover = ItemHandlerHelper.insertItemStacked(source, remainder, false);
                if (!leftover.isEmpty() && pmc.level() instanceof ServerLevel level) {
                    Containers.dropItemStack(level, pmc.getX(), pmc.getY(), pmc.getZ(), leftover);
                }
            }
            moved = true;
            if (needy(new ResupplyTarget(eligible, dest)).isEmpty()) break;
        }
        return moved;
    }

    public static boolean isUnderFobCommand(PmcUnitEntity pmc) {
        if (!(pmc.level() instanceof ServerLevel level)) return false;
        return activeFob(pmc, level) != null;
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
            // Own rifle ammo only. An on-foot PMC's backpack has no use for a vehicle's shells —
            // nothing reads ammo out of an infantryman's pockets to load a cannon — and handing
            // every assigned vehicle's ammo kind to every idle PMC filled backpacks (and then the
            // stockpile) with items nobody could ever consume, which is what was landing on the
            // ground once both were full. A mounted crew still gets its hull's ammo, above.
            List<AmmoKind> eligible = resolveEligibleTaczAmmo(pmc);
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
        if (!VehicleAmmoStorage.hasStorage(hull)) return null;
        return VehicleAmmoStorage.handler(hull);
    }

    /** Minimum reserve before an assigned unit walks to the stockpile (per eligible item). */
    private static final int RESUPPLY_MIN_STACKS = 2;

    /** Kinds this unit is below the reserve on and still has room for. */
    private static List<AmmoKind> needy(ResupplyTarget target) {
        List<AmmoKind> out = new ArrayList<>();
        IItemHandler dest = target.handler();
        if (dest == null) return out;
        for (AmmoKind kind : target.eligible()) {
            int want = kind.prototype().getMaxStackSize() * RESUPPLY_MIN_STACKS;
            if (countOf(dest, kind) < want && canAcceptMore(dest, kind)) out.add(kind);
        }
        return out;
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
