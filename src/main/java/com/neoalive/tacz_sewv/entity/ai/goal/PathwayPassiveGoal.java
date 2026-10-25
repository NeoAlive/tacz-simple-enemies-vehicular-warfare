package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.IPathwayInfantry;
import com.neoalive.tacz_sewv.entity.ai.support.PathwaySupport;
import com.neoalive.tacz_sewv.entity.ai.support.PatrolSupport;
import com.neoalive.tacz_sewv.map.PreferredPathwayData;

/**
 * Autonomous passive funnel: an owned PMC (on foot, or driving a ground hull) at either end of a
 * saved pathway takes it when its MOVE destination is coaxial with the path; an idle on-foot
 * FREE_FIRE unit takes it when walking along it. Flagless: it only scans and assigns.
 * {@link PathwayGoal} walks infantry; a driver gets a one-shot cruise, which outranks MOVE in
 * {@code VehicleTargeting.resolveDestination} and hands back to the still-standing MOVE at the exit.
 */
public class PathwayPassiveGoal extends Goal {

    private long nextScan;

    private final PmcUnitEntity unit;
    private final Vec3[] velocityHistory = new Vec3[5];
    private int velocityIndex;

    public PathwayPassiveGoal(PmcUnitEntity unit) {
        this.unit = unit;
        // Stagger scans so every PMC does not hit SavedData on the same tick.
        this.nextScan = unit.level().getGameTime() + (unit.getId() & 31);
    }

    @Override
    public boolean canUse() {
        return this.unit.getOwnerUUID() != null
                && (!this.unit.isPassenger() || PathwaySupport.isGroundFunnelUnit(this.unit));
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void tick() {
        recordVelocity();

        long now = this.unit.level().getGameTime();
        if (now < this.nextScan) return;
        this.nextScan = now + PathwaySupport.PASSIVE_INTERVAL;

        if (!PathwaySupport.canPassiveTrigger(this.unit)) return;
        PreferredPathwayData.PathCatalog catalog = PreferredPathwayData.forOwner(
                this.unit.level(), this.unit.getOwnerUUID(), this.unit.level().dimension());
        if (catalog.isEmpty()) return;

        Entity body = this.unit.getVehicle() != null ? this.unit.getVehicle() : this.unit;
        // A hull's centre can never reach a node as closely as a soldier can.
        double entryR = PathwaySupport.entryRadius() + (body == this.unit ? 0.0 : body.getBbWidth());
        Vec3 dest = PathwaySupport.moveDest(this.unit);
        Vec3 vel = smoothedVelocity();

        // Cheapest detour across every path wins. A MOVE may take a path from well off it (detour
        // rule), so the bbox prefilter only applies to velocity matching, which needs the mouth.
        PathwaySupport.Match best = null;
        String bestId = null;
        for (Map.Entry<String, List<BlockPos>> entry : catalog.paths().entrySet()) {
            if (dest == null && !PathwaySupport.pathBboxNear(body.getX(), body.getZ(), entry.getValue(), entryR)) {
                continue;
            }
            PathwaySupport.Match match = PathwaySupport.matchEntry(body.getX(), body.getZ(), dest, vel,
                    entry.getValue(), entryR, PathwaySupport.coaxRadius(), PathwaySupport.detourTolerance());
            if (match != null && (best == null || match.cost() < best.cost())) {
                best = match;
                bestId = entry.getKey();
            }
        }
        if (best == null) return;

        if (dest != null) {
            ((IPathwayInfantry) this.unit).sewv$markFunneledFor(BlockPos.containing(dest).asLong());
        }
        if (body == this.unit) {
            PathwaySupport.begin(this.unit, best.route(), 0, bestId, true);
        } else {
            PatrolSupport.beginFunnelRoute(this.unit, best.route());
        }
    }

    private void recordVelocity() {
        this.velocityHistory[this.velocityIndex] = this.unit.getDeltaMovement();
        this.velocityIndex = (this.velocityIndex + 1) % this.velocityHistory.length;
    }

    private Vec3 smoothedVelocity() {
        double x = 0;
        double y = 0;
        double z = 0;
        int count = 0;
        for (Vec3 v : this.velocityHistory) {
            if (v == null) continue;
            x += v.x;
            y += v.y;
            z += v.z;
            count++;
        }
        if (count == 0) return Vec3.ZERO;
        return new Vec3(x / count, y / count, z / count);
    }
}
