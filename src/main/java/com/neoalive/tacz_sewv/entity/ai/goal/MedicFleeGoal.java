package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.EnumSet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.bridge.IMedicCaptured;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;

/**
 * Make an RU/US medic flee when it spots a nearby hostile. Runs at priority 1 so it outranks
 * the MedicGoal (priority 2) but is preempted by the MedicCapturedGoal (priority 0). Reuses
 * the ring-sample-and-standable-ground idiom from {@code BailOutVehicleGoal} but implements
 * it independently — this is a threat-avoidance flee, not a wreck-clearance scramble.
 *
 * <p><b>Flee direction:</b> away from threat only (no faction-base path — that mechanism
 * doesn't exist generally outside invasion-mode matches). This is a deliberate, simpler design.
 *
 * <p><b>No re-scan while fleeing:</b> the goal just runs to completion (arrival or capture
 * preemption). If the threat persists, a fresh flee kicks off on the next goal-selection pass.
 * If the threat is gone, the next priority (MedicGoal or idle wandering) takes over.
 */
public class MedicFleeGoal extends Goal {
    private final AbstractUnit medic;
    private BlockPos fleeTarget;
    private int moveFailCount = 0;
    private static final int REPATH_INTERVAL = 10;
    private static final int ARRIVE_DISTANCE_SQ = 4;

    public MedicFleeGoal(AbstractUnit medic) {
        this.medic = medic;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        // Medic-type check via VehicleTargeting.isMedic (airtight, reused, not reinvented).
        if (!VehicleTargeting.isMedic(this.medic)) {
            return false;
        }

        // Skip scan if currently captured — the MedicCapturedGoal outranks us anyway.
        if (this.medic instanceof IMedicCaptured captured && captured.sewv$isCaptured()) {
            return false;
        }

        // Scan for a nearby hostile. Reuse VehicleTargeting.isNonHostile for consistency.
        double scanRadius = SewvConfig.MEDIC_FLEE_DETECTION_RADIUS.get();
        var threats = this.medic.level().getEntitiesOfClass(
                LivingEntity.class,
                this.medic.getBoundingBox().inflate(scanRadius),
                (e) -> {
                    if (e == this.medic) return false;
                    if (!e.isAlive()) return false;
                    if (e instanceof AbstractUnit) {
                        return !VehicleTargeting.isNonHostile(this.medic, e);
                    }
                    if (e instanceof Player p) {
                        return !VehicleTargeting.isNonHostile(this.medic, p);
                    }
                    return false;
                });

        if (threats.isEmpty()) {
            return false;
        }

        // Pick the nearest threat.
        LivingEntity nearest = threats.get(0);
        double nearestDist = this.medic.distanceToSqr(nearest);
        for (LivingEntity threat : threats) {
            double dist = this.medic.distanceToSqr(threat);
            if (dist < nearestDist) {
                nearest = threat;
                nearestDist = dist;
            }
        }

        // Compute a bearing away from the threat and sample a ring-point.
        Vec3 threatDir = nearest.position().subtract(this.medic.position()).normalize();
        Vec3 fleeDir = this.medic.position().subtract(nearest.position()).normalize();

        double minDist = SewvConfig.MEDIC_FLEE_MIN_DISTANCE.get();
        double maxDist = SewvConfig.MEDIC_FLEE_MAX_DISTANCE.get();

        this.fleeTarget = sampleFleePoint(fleeDir, minDist, maxDist);
        return this.fleeTarget != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (this.fleeTarget == null) {
            return false;
        }
        if (this.medic.getNavigation().isDone()) {
            return false;
        }
        double arriveDistSq = ARRIVE_DISTANCE_SQ;
        if (this.medic.distanceToSqr(Vec3.atCenterOf(this.fleeTarget)) < arriveDistSq) {
            return false;
        }
        return true;
    }

    @Override
    public void start() {
        this.moveFailCount = 0;
        if (this.fleeTarget != null) {
            this.medic.getNavigation().moveTo(this.fleeTarget.getX() + 0.5, this.fleeTarget.getY(),
                    this.fleeTarget.getZ() + 0.5, 1.2);
        }
    }

    @Override
    public void tick() {
        if (this.medic.getNavigation().isDone()) {
            this.moveFailCount++;
            if (this.moveFailCount >= REPATH_INTERVAL && this.fleeTarget != null) {
                this.medic.getNavigation().moveTo(this.fleeTarget.getX() + 0.5, this.fleeTarget.getY(),
                        this.fleeTarget.getZ() + 0.5, 1.2);
                this.moveFailCount = 0;
            }
        }
    }

    @Override
    public void stop() {
        this.medic.getNavigation().stop();
        this.fleeTarget = null;
    }

    /**
     * Sample a point in the ring between minDist and maxDist from the medic's current position,
     * biased toward the fleeDir direction, that has standable ground (not a void, wall, or lava).
     */
    private BlockPos sampleFleePoint(Vec3 fleeDir, double minDist, double maxDist) {
        Vec3 medPos = this.medic.position();

        // Compute the heading of the flee direction (as if it's a unit vector in the XZ plane).
        double fleeHeading = Math.atan2(fleeDir.z, fleeDir.x);

        // Try up to 8 samples in the flee direction's half-plane (±45°).
        for (int attempt = 0; attempt < 8; attempt++) {
            double angle = fleeHeading + (Math.random() - 0.5) * Math.PI / 2;
            double dist = minDist + Math.random() * (maxDist - minDist);

            double x = medPos.x + Math.cos(angle) * dist;
            double z = medPos.z + Math.sin(angle) * dist;
            BlockPos testPos = BlockPos.containing(x, medPos.y, z);

            if (isStandableGround(testPos)) {
                return testPos;
            }
        }

        // Fallback: if biased samples fail, try unbiased ring samples (full 360°).
        for (int attempt = 0; attempt < 8; attempt++) {
            double angle = Math.random() * 2 * Math.PI;
            double dist = minDist + Math.random() * (maxDist - minDist);

            double x = medPos.x + Math.cos(angle) * dist;
            double z = medPos.z + Math.sin(angle) * dist;
            BlockPos testPos = BlockPos.containing(x, medPos.y, z);

            if (isStandableGround(testPos)) {
                return testPos;
            }
        }

        return null; // No valid flee point found.
    }

    /**
     * Check if a block position is safe to stand on: solid ground, not lava, and
     * reachable (no wall above).
     */
    private boolean isStandableGround(BlockPos pos) {
        var level = this.medic.level();

        // Check the block below pos (the footing) — must have a solid top surface.
        BlockPos below = pos.below();
        var footingState = level.getBlockState(below);
        if (!footingState.isFaceSturdy(level, below, Direction.UP)) {
            return false; // No solid top surface (water, leaves, air, etc).
        }

        // No lava at foot level.
        if (footingState.getFluidState().is(FluidTags.LAVA)) {
            return false;
        }

        // Check the block at pos and above (where the medic would stand) — must be passable.
        if (!isPassable(level, pos) || !isPassable(level, pos.above())) {
            return false; // Wall or roof in the way.
        }

        return true;
    }

    private static boolean isPassable(net.minecraft.world.level.Level level, BlockPos pos) {
        var state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty() && !state.getFluidState().is(FluidTags.LAVA);
    }
}
