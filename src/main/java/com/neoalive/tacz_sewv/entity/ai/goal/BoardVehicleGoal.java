package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.EnumSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.IVehicleBoarder;
import com.neoalive.tacz_sewv.entity.ai.support.CommanderSeating;
import com.neoalive.tacz_sewv.item.LockItem;
import com.neoalive.tacz_sewv.order.OrderFailure;
import com.neoalive.tacz_sewv.order.OrderReport;
import com.neoalive.tacz_sewv.spawn.TankSpawner;

/**
 * Walks a unit to the vehicle it has been told to board and puts it in a seat.
 *
 * <p>This goal only ever <em>executes</em> a standing order on {@link IVehicleBoarder}; it never
 * decides to board anything. That split is what lets one goal serve both factions, which arrive
 * at an order by completely different routes:
 * <ul>
 *   <li><b>PMC</b> — the player points at a hull and presses the board key, and
 *       {@code PacketBoardVehicle} writes the order server-side.
 *   <li><b>RU/US</b> — {@link SeekAbandonedVehicleGoal} spots an abandoned hull and writes the
 *       same order. They have no order queue a player command could arrive through.
 * </ul>
 *
 * <p>Nothing here is player-specific, which is why the network bridge lives entirely on the
 * writing side: an order is three fields, and by the time this goal reads them it cannot tell
 * (and has no reason to care) which one put them there.
 */
public class BoardVehicleGoal extends Goal {

    private static final double MOUNT_DISTANCE = 5.0;
    private static final double NAV_STUCK_DISTANCE_SQ = 36.0; // 6 blocks²
    // 200 goal ticks ≈ 20 s wall clock: goals tick every other game tick.
    private static final int MAX_BOARDING_TICKS = 200;
    /** Goal ticks between repath attempts while the path keeps coming up empty. */
    private static final int REPATH_INTERVAL = 10;
    /** Min game ticks between cancel feedbacks to the same owner (squad cancel spam). */
    private static final int CANCEL_FEEDBACK_COOLDOWN = 40;

    private static final ConcurrentHashMap<UUID, Long> LAST_CANCEL_FEEDBACK = new ConcurrentHashMap<>();

    /** Drop all cancel-feedback throttle rows (server stop). */
    public static void clearCancelFeedback() {
        LAST_CANCEL_FEEDBACK.clear();
    }

    /** Drop one owner's cancel-feedback throttle (logout). */
    public static void clearCancelFeedback(UUID ownerId) {
        LAST_CANCEL_FEEDBACK.remove(ownerId);
    }

    private enum CancelReason { FULL, WRECKED, TIMEOUT, GONE }

    private final AbstractUnit unit;
    private VehicleEntity targetVehicle;
    private int boardingTicks;

    public BoardVehicleGoal(AbstractUnit unit) {
        this.unit = unit;
        // Claim no flags so the goal selector never gates canUse() behind MOVE/LOOK
        // contention — boarding must stay evaluable even while other goals run.
        this.setFlags(EnumSet.noneOf(Flag.class));
    }

    private IVehicleBoarder boarder() {
        return (IVehicleBoarder) this.unit;
    }

    @Override
    public boolean canUse() {
        if (this.unit.level().isClientSide()) return false;
        if (!boarder().tacz_sewv$isBoarding()) return false;
        if (this.unit.getVehicle() != null) return false;

        int mountId = boarder().tacz_sewv$getMountTargetId();
        if (mountId == -1) return false;

        Entity e = this.unit.level().getEntity(mountId);
        if (e == null) return false; // not resolvable right now — keep the order pending

        // The target resolved but can't be boarded (destroyed, wrecked, or full): drop the
        // order instead of leaving the boarding flag latched — otherwise the unit would
        // spontaneously walk off to board whenever a seat frees up later.
        if (!(e instanceof VehicleEntity v) || !v.isAlive()) {
            cancelBoarding(CancelReason.GONE);
            return false;
        }
        if (v.isWreck()) {
            cancelBoarding(CancelReason.WRECKED);
            return false;
        }
        if (LockItem.blocksNpc(v, this.unit)) {
            cancelBoarding(CancelReason.GONE);
            return false;
        }
        if (isFull(v)) {
            cancelBoarding(CancelReason.FULL);
            return false;
        }

        this.targetVehicle = v;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (!boarder().tacz_sewv$isBoarding()) return false; // order cancelled
        if (this.unit.getVehicle() != null) return false;    // mounted — success
        if (this.targetVehicle == null) return false;
        // A vehicle that filled up is deliberately NOT stopped on here; tick() cancels the
        // order for that, so the reason the unit gave up is recorded rather than just lost.
        return this.targetVehicle.isAlive() && !this.targetVehicle.isWreck();
    }

    @Override
    public void start() {
        this.boardingTicks = 0;
        this.unit.getNavigation().moveTo(this.targetVehicle, 1.0);
    }

    @Override
    public void tick() {
        if (this.targetVehicle == null) return;

        // Stuck in a crowd, or it can't be reached at all.
        if (++this.boardingTicks > MAX_BOARDING_TICKS) {
            cancelBoarding(CancelReason.TIMEOUT);
            return;
        }
        if (isFull(this.targetVehicle)) { // filled up while we walked over
            cancelBoarding(CancelReason.FULL);
            return;
        }
        if (LockItem.blocksNpc(this.targetVehicle, this.unit)) {
            cancelBoarding(CancelReason.GONE);
            return;
        }

        this.unit.getLookControl().setLookAt(this.targetVehicle, 30F, 30F);

        double distSq = this.unit.distanceToSqr(this.targetVehicle);
        // Navigation can finish just short of a large vehicle's hull; treat "path done and
        // already within 6 blocks" as close enough to board rather than stalling.
        boolean navStuck = this.unit.getNavigation().isDone() && distSq <= NAV_STUCK_DISTANCE_SQ;

        if (distSq <= MOUNT_DISTANCE * MOUNT_DISTANCE || navStuck) {
            // Passenger-only order: never take the wheel, and never board ahead of the player.
            // SBW's driver is just the FIRST passenger, so boarding an empty hull would make this
            // unit the driver. Wait beside it until the owning player presses "board my vehicle"
            // from a seat in THIS hull (PacketClearBoarding) — giving them first pick of seat —
            // and only then pile in; MAX_BOARDING_TICKS still bounds the wait.
            if (boarder().tacz_sewv$isPassengerOnly() && !boarder().tacz_sewv$isBoardCleared()) {
                return;
            }
            // A refused mount (seat raced away, another mod cancelled it) keeps the order:
            // the full-vehicle check above and the timeout still bound the retries.
            CommanderSeating.install(this.targetVehicle);
            if (this.unit.startRiding(this.targetVehicle)) {
                // Clear the order so it doesn't loop, or re-board after a dismount.
                boarder().tacz_sewv$setBoarding(false);
                boarder().tacz_sewv$setMountTargetId(-1);
                boarder().tacz_sewv$setPassengerOnly(false);
                boarder().tacz_sewv$setBoardCleared(false);
                this.unit.getNavigation().stop();
                TankSpawner.maybeStockFactionBoardAmmo(this.targetVehicle, this.unit);
            }
        } else if (this.unit.getNavigation().isDone() && this.boardingTicks % REPATH_INTERVAL == 0) {
            // Throttled: an unreachable vehicle leaves navigation "done" every tick, which
            // would otherwise force a full repath every tick until the timeout.
            this.unit.getNavigation().moveTo(this.targetVehicle, 1.0);
        }
    }

    @Override
    public void stop() {
        // The order deliberately survives: stop() fires on any interruption and the unit
        // still wants to board. It's dropped by the timeout, an unusable target, or a bail-out.
        this.unit.getNavigation().stop();
        this.boardingTicks = 0;
    }

    private void cancelBoarding(CancelReason reason) {
        boarder().tacz_sewv$setBoarding(false);
        boarder().tacz_sewv$setMountTargetId(-1);
        boarder().tacz_sewv$setPassengerOnly(false);
        boarder().tacz_sewv$setBoardCleared(false);
        this.targetVehicle = null;
        notifyOwner(reason);
    }

    private void notifyOwner(CancelReason reason) {
        if (!(this.unit instanceof PmcUnitEntity pmc)) return;
        UUID ownerId = pmc.getOwnerUUID();
        if (ownerId == null) return;
        if (!(this.unit.level() instanceof ServerLevel level)) return;
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(ownerId);
        if (owner == null) return;

        long now = level.getGameTime();
        Long last = LAST_CANCEL_FEEDBACK.get(ownerId);
        if (last != null) {
            if (now - last < CANCEL_FEEDBACK_COOLDOWN) return;
            LAST_CANCEL_FEEDBACK.remove(ownerId, last);
        }
        LAST_CANCEL_FEEDBACK.put(ownerId, now);

        OrderReport.fail(owner, switch (reason) {
            case FULL -> OrderFailure.VEHICLE_FULL;
            case WRECKED -> OrderFailure.VEHICLE_WRECKED;
            case TIMEOUT -> OrderFailure.UNREACHABLE;
            case GONE -> OrderFailure.VEHICLE_GONE;
        }, this.unit);
    }

    private static boolean isFull(VehicleEntity v) {
        return v.getPassengers().size() >= v.getMaxPassengers();
    }
}
