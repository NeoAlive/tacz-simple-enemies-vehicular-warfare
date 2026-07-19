package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.bridge.IVehicleBoarder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import java.util.EnumSet;

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
        if (!(e instanceof VehicleEntity v) || !v.isAlive() || v.isWreck() || isFull(v)) {
            cancelBoarding();
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
            cancelBoarding();
            return;
        }
        if (isFull(this.targetVehicle)) { // filled up while we walked over
            cancelBoarding();
            return;
        }

        this.unit.getLookControl().setLookAt(this.targetVehicle, 30F, 30F);

        double distSq = this.unit.distanceToSqr(this.targetVehicle);
        // Navigation can finish just short of a large vehicle's hull; treat "path done and
        // already within 6 blocks" as close enough to board rather than stalling.
        boolean navStuck = this.unit.getNavigation().isDone() && distSq <= NAV_STUCK_DISTANCE_SQ;

        if (distSq <= MOUNT_DISTANCE * MOUNT_DISTANCE || navStuck) {
            // Passenger-only order: never take the wheel. SBW's driver is just the FIRST
            // passenger, so boarding an empty hull would make this unit the driver. Wait beside
            // it until someone else is aboard; MAX_BOARDING_TICKS bounds the wait.
            if (boarder().tacz_sewv$isPassengerOnly() && this.targetVehicle.getFirstPassenger() == null) {
                return;
            }
            // A refused mount (seat raced away, another mod cancelled it) keeps the order:
            // the full-vehicle check above and the timeout still bound the retries.
            if (this.unit.startRiding(this.targetVehicle)) {
                // Clear the order so it doesn't loop, or re-board after a dismount.
                boarder().tacz_sewv$setBoarding(false);
                boarder().tacz_sewv$setMountTargetId(-1);
                boarder().tacz_sewv$setPassengerOnly(false);
                this.unit.getNavigation().stop();
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

    private void cancelBoarding() {
        boarder().tacz_sewv$setBoarding(false);
        boarder().tacz_sewv$setMountTargetId(-1);
        boarder().tacz_sewv$setPassengerOnly(false);
        this.targetVehicle = null;
    }

    private static boolean isFull(VehicleEntity v) {
        return v.getPassengers().size() >= v.getMaxPassengers();
    }
}
