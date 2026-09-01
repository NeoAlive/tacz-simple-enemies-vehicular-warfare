package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.List;
import java.util.Map;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.entity.ai.support.PathwaySupport;
import com.neoalive.tacz_sewv.map.PreferredPathwayData;

/**
 * Autonomous passive funnel: idle on-foot PMCs near a saved pathway join it when moving parallel,
 * or when a MOVE destination lies on the path. Flagless — only scans and assigns;
 * {@link PathwayGoal} owns movement.
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
        return this.unit.getOwnerUUID() != null && !this.unit.isPassenger();
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
        if (this.unit.getOwnerUUID() == null) return;

        Vec3 vel = smoothedVelocity();
        Vec3 dir = PathwaySupport.movementDirection(this.unit, vel);
        ResourceKey<Level> dim = this.unit.level().dimension();
        PreferredPathwayData.PathCatalog catalog = PreferredPathwayData.forOwner(
                this.unit.level(), this.unit.getOwnerUUID(), dim);
        if (catalog.isEmpty()) return;

        double ex = this.unit.getX();
        double ez = this.unit.getZ();
        double margin = 24.0;

        for (Map.Entry<String, List<net.minecraft.core.BlockPos>> entry : catalog.paths().entrySet()) {
            List<net.minecraft.core.BlockPos> waypoints = entry.getValue();
            if (!PathwaySupport.pathBboxNear(ex, ez, waypoints, margin)) continue;

            int step = PathwaySupport.matchPassive(this.unit, waypoints, dir);
            if (step >= 0) {
                PathwaySupport.begin(this.unit, waypoints, step, entry.getKey(), true);
                return;
            }
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
