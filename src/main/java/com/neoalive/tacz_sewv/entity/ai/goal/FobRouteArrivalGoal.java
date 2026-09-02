package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.EnumSet;
import java.util.List;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.fob.FobDebug;
import com.neoalive.tacz_sewv.fob.FobInstance;
import com.neoalive.tacz_sewv.fob.FobManager;
import com.neoalive.tacz_sewv.fob.FobSupport;

/**
 * Completes a {@link com.neoalive.tacz_sewv.fob.FobNetworking#routeToFob} order: a hull that
 * reaches the parking pad puts its <b>whole</b> crew on the ground, and infantry that walks in
 * simply stands down there.
 *
 * <p>Nobody is re-boarded on arrival. Routing home is a stand-down, so a unit that walked back and
 * then climbed into a parked tank was not at the FOB in any sense the player asked for; putting
 * crews back in hulls is {@link FobScrambleGoal}'s job and only happens on a real threat.
 */
public class FobRouteArrivalGoal extends Goal {

    private final PmcUnitEntity unit;

    public FobRouteArrivalGoal(PmcUnitEntity unit) {
        this.unit = unit;
        this.setFlags(EnumSet.noneOf(Goal.Flag.class));
    }

    @Override
    public boolean canUse() {
        if (this.unit.level().isClientSide()) return false;
        if (!FobSupport.hasRoutePending(this.unit)) return false;
        if (!(this.unit.level() instanceof ServerLevel level)) return false;

        if (FobSupport.sanitizeRoutePending(this.unit, level)) {
            FobDebug.logEntity(this.unit, "route goal canUse=false — stale route cleared");
            return false;
        }

        // Deliberately NOT gated on fob.scrambleActive. FobScrambleGoal already yields to a route,
        // so a scramble that started mid-route would leave the unit parked on the pad with a route
        // that can never finish — and a route blocks every player order, so that wedged the unit
        // for good.
        FobInstance fob = fob(level);
        if (fob == null || fob.parkingPos == null) {
            FobDebug.logEntity(this.unit, "route goal canUse=false — fob={}, parking={}",
                    fob != null, fob != null && fob.parkingPos != null);
            return false;
        }
        // No log for "still driving" — it is the normal state of every routing unit, and printed
        // ten lines a second per unit it buried everything else in the debug channel.
        return readyToFinish(level, fob);
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void tick() {
        if (!(this.unit.level() instanceof ServerLevel level)) return;
        FobInstance fob = fob(level);
        if (fob == null) return;

        if (this.unit.getVehicle() instanceof VehicleEntity hull) {
            if (atParkingStandoff(hull, fob, level)) {
                disembark(hull);
            }
            return;
        }

        if (atParkingStandoff(this.unit, fob, level)) {
            FobDebug.logEntity(this.unit, "route arrival — infantry at parking standoff");
            finishRoute();
        }
    }

    /**
     * Empties the hull. A gunner is not the driver, so its own arrival test could never pass while
     * it was seated — it stayed mounted with a route that never finished, and a pending route
     * blocks every player order, so it was stuck for good. Emptying the hull from whichever
     * crewman's goal runs first fixes both ends at once, and the rest of the crew's routes are
     * cleared here rather than waiting for a goal of their own.
     *
     * <p>A ground hull always empties. Only an <b>aircraft</b> has to be down first: the pad is a
     * horizontal box spanning the full world height, so a helicopter passing over it reads as "at
     * the parking standoff" too, and bailing its crew out there would drop them. The test is not
     * {@code onGround()} for everything, because a hull SBW is floating a fraction above the
     * surface would then never dismount and its route would hang until the timeout.
     * Players aboard are never ejected.
     */
    private void disembark(VehicleEntity hull) {
        boolean aircraft = HullFacts.isHelicopterHull(hull) || HullFacts.isPlaneHull(hull);
        if (aircraft && !hull.onGround()) return;
        FobDebug.logEntity(this.unit, "route arrival — crew dismount at parking standoff");
        for (Entity passenger : List.copyOf(hull.getPassengers())) {
            if (passenger instanceof Player) continue;
            passenger.stopRiding();
            FobSupport.clearRoutePending(passenger);
            if (passenger instanceof PmcUnitEntity crew) {
                crew.setOrder(OrderType.FREE_FIRE);
            }
        }
        finishRoute();
    }

    private boolean readyToFinish(ServerLevel level, FobInstance fob) {
        if (fob.parkingPos == null) return false;

        if (this.unit.getVehicle() instanceof VehicleEntity hull) {
            return atParkingStandoff(hull, fob, level);
        }
        return !this.unit.isPassenger() && atParkingStandoff(this.unit, fob, level);
    }

    private static boolean atParkingStandoff(Entity entity, FobInstance fob, Level level) {
        return FobSupport.withinParkingPad(fob, entity, level);
    }

    private void finishRoute() {
        FobSupport.clearRoutePending(this.unit);
        this.unit.setOrder(OrderType.FREE_FIRE);
        FobDebug.logEntity(this.unit, "route finished — order reset to FREE_FIRE");
    }

    @Nullable
    private FobInstance fob(ServerLevel level) {
        BlockPos cmd = FobSupport.routeCommandPos(this.unit);
        if (cmd == null) cmd = FobSupport.stampPos(this.unit);
        if (cmd == null) return null;
        return FobManager.get(level).getFob(cmd);
    }
}
