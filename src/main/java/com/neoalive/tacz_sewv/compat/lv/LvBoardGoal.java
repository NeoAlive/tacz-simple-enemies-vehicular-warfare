package com.neoalive.tacz_sewv.compat.lv;

import java.util.EnumSet;
import java.util.UUID;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import com.neoalive.tacz_sewv.bridge.IVehicleBoarder;
import com.neoalive.tacz_sewv.entity.ai.support.PmcDownedSupport;
import com.neoalive.tacz_sewv.order.OrderFailure;
import com.neoalive.tacz_sewv.order.OrderReport;

/**
 * {@code BoardVehicleGoal} for an order that names a Limitless Vehicles hull. It executes the same
 * standing {@link IVehicleBoarder} order and never decides to board anything itself, so the same
 * writers feed it: {@code PacketBoardVehicle} for a player's PMC, {@code SeekAbandonedVehicleGoal}
 * for RU/US scavenging. {@code BoardVehicleGoal} leaves any order naming an LV hull alone.
 *
 * <p>One difference from SBW: an LV hull seats a non-player in its first EMPTY seat, so a
 * passenger-only order cannot lean on "someone else is already aboard" to keep the unit off the
 * wheel. It mounts and then moves itself out of seat 0 with {@code changeSeat} — and dismounts again
 * if no other seat is free, rather than end up driving.
 */
final class LvBoardGoal extends Goal {

    private static final double MOUNT_DISTANCE = 5.0;
    private static final double NAV_STUCK_DISTANCE_SQ = 36.0;
    // 200 goal ticks ≈ 20 s wall clock: goals tick every other game tick.
    private static final int MAX_BOARDING_TICKS = 200;
    private static final int REPATH_INTERVAL = 10;

    private final AbstractUnit unit;
    private AbstractVehicle target;
    private int boardingTicks;

    LvBoardGoal(AbstractUnit unit) {
        this.unit = unit;
        this.setFlags(EnumSet.noneOf(Flag.class)); // same as BoardVehicleGoal: always evaluable
    }

    private IVehicleBoarder boarder() {
        return (IVehicleBoarder) this.unit;
    }

    @Override
    public boolean canUse() {
        if (this.unit.level().isClientSide() || !LvCompat.aiEnabled()) return false;
        if (PmcDownedSupport.isDowned(this.unit)) return false;
        if (!boarder().tacz_sewv$isBoarding() || this.unit.getVehicle() != null) return false;
        int mountId = boarder().tacz_sewv$getMountTargetId();
        if (mountId == -1) return false;
        Entity e = this.unit.level().getEntity(mountId);
        if (!(e instanceof AbstractVehicle v)) return false; // not ours (or not resolvable yet)
        if (!LvHulls.isCrewable(v)) {
            cancel(v.isDestroyed() ? OrderFailure.VEHICLE_WRECKED : OrderFailure.VEHICLE_GONE);
            return false;
        }
        if (!LvHulls.hasFreeSeat(v)) {
            cancel(OrderFailure.VEHICLE_FULL);
            return false;
        }
        this.target = v;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (PmcDownedSupport.isDowned(this.unit)) return false;
        if (!boarder().tacz_sewv$isBoarding() || this.unit.getVehicle() != null) return false;
        return this.target != null && LvHulls.isCrewable(this.target);
    }

    @Override
    public void start() {
        this.boardingTicks = 0;
        this.unit.getNavigation().moveTo(this.target, 1.0);
    }

    @Override
    public void tick() {
        if (this.target == null) return;
        if (++this.boardingTicks > MAX_BOARDING_TICKS) {
            cancel(OrderFailure.UNREACHABLE);
            return;
        }
        if (!LvHulls.hasFreeSeat(this.target)) {
            cancel(OrderFailure.VEHICLE_FULL);
            return;
        }
        this.unit.getLookControl().setLookAt(this.target, 30F, 30F);

        double distSq = this.unit.distanceToSqr(this.target);
        boolean navStuck = this.unit.getNavigation().isDone() && distSq <= NAV_STUCK_DISTANCE_SQ;
        if (distSq <= MOUNT_DISTANCE * MOUNT_DISTANCE || navStuck) {
            mount();
        } else if (this.unit.getNavigation().isDone() && this.boardingTicks % REPATH_INTERVAL == 0) {
            this.unit.getNavigation().moveTo(this.target, 1.0);
        }
    }

    private void mount() {
        boolean passengerOnly = boarder().tacz_sewv$isPassengerOnly();
        // A refused mount keeps the order; the full-hull check and the timeout bound the retries.
        if (!this.unit.startRiding(this.target)) return;
        if (passengerOnly && LvHulls.seatIndexOf(this.target, this.unit) == 0) {
            // LV range-checks changeSeat with <= seats.size(); only ever hand it a real index.
            int seat = LvHulls.firstFreeNonDriverSeat(this.target);
            if (seat < 0 || seat >= this.target.seats.size() || !this.target.changeSeat(this.unit, seat)) {
                this.unit.stopRiding();
                cancel(OrderFailure.VEHICLE_FULL);
                return;
            }
        }
        clearOrder();
        this.unit.getNavigation().stop();
    }

    @Override
    public void stop() {
        // The order deliberately survives an interruption, as in BoardVehicleGoal.
        this.unit.getNavigation().stop();
        this.boardingTicks = 0;
    }

    private void clearOrder() {
        boarder().tacz_sewv$setBoarding(false);
        boarder().tacz_sewv$setMountTargetId(-1);
        boarder().tacz_sewv$setPassengerOnly(false);
        boarder().tacz_sewv$setBoardCleared(false);
    }

    private void cancel(OrderFailure reason) {
        clearOrder();
        this.target = null;
        if (!(this.unit instanceof PmcUnitEntity pmc)) return;
        UUID ownerId = pmc.getOwnerUUID();
        if (ownerId == null || !(this.unit.level() instanceof ServerLevel level)) return;
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(ownerId);
        if (owner != null) OrderReport.fail(owner, reason, this.unit);
    }
}
