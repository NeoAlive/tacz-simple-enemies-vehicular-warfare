package com.neoalive.tacz_sewv.entity.ai;

import com.neoalive.tacz_sewv.bridge.IVehiclePatrol;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.navigation.GroundVehicleNodeEvaluator;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.jetbrains.annotations.Nullable;

/**
 * The patrol order's brain, shared by whichever drive goal reads the destination
 * ({@link VehicleTargeting#resolveDestination}). A patrolling hull holds a waypoint inside its
 * circle and, once {@code patrolRotateIntervalTicks} of game time have passed, rolls a new valid
 * point to move to — "round robin" over the area. State lives on the unit via {@link IVehiclePatrol}.
 *
 * <p>Valid ground is judged with {@link GroundVehicleNodeEvaluator}'s own block classification, so a
 * patrol point is exactly the kind of node the driver's pathfinder can route to: air to sit in over
 * solid, non-hazard, dry footing ({@link BlockPathTypes#WALKABLE}).
 */
public final class PatrolSupport {

    private PatrolSupport() {}

    // A patrol point is chosen by rejection sampling — this many tries before giving up for now.
    private static final int PICK_ATTEMPTS = 24;
    // If a roll finds nothing valid (all sampled far chunks unloaded, or no dry ground), retry
    // soon rather than sitting on a stale/absent waypoint for the whole rotation interval.
    private static final int PICK_RETRY_TICKS = 100;

    // The evaluator's single-block classifier (getBlockPathType(level,x,y,z)) touches no instance
    // state, so one shared instance is safe on the single-threaded server tick.
    private static final GroundVehicleNodeEvaluator CLASSIFIER = new GroundVehicleNodeEvaluator();

    /**
     * The point a patrolling driver should currently head to, rotating to a fresh one when the
     * deadline passes. Returns null when the unit is not patrolling — resolveDestination then falls
     * through to the ordinary order handling. Cheap on the steady-state path (a few NBT reads); the
     * world sampling only runs when it is actually time to re-pick.
     */
    @Nullable
    public static BlockPos currentWaypoint(PmcUnitEntity pmc) {
        IVehiclePatrol patrol = (IVehiclePatrol) pmc;
        BlockPos origin = patrol.sewv$getPatrolOrigin();
        if (origin == null) return null;

        long now = pmc.level().getGameTime();
        BlockPos waypoint = patrol.sewv$getPatrolWaypoint();
        if (waypoint != null && now < patrol.sewv$getPatrolNextRotate()) {
            return waypoint;
        }

        BlockPos fresh = pickWaypoint(pmc.level(), origin, patrol.sewv$getPatrolRadius(), pmc.getRandom());
        if (fresh != null) {
            patrol.sewv$setPatrolWaypoint(fresh);
            patrol.sewv$setPatrolNextRotate(now + SewvConfig.PATROL_ROTATE_INTERVAL_TICKS.get());
            return fresh;
        }
        // Nothing valid this roll — hold what we have (or rally on the origin) and try again shortly.
        patrol.sewv$setPatrolNextRotate(now + PICK_RETRY_TICKS);
        return waypoint != null ? waypoint : origin;
    }

    public static void begin(PmcUnitEntity pmc, BlockPos origin, int radius) {
        ((IVehiclePatrol) pmc).sewv$setPatrol(origin, radius);
    }

    public static void clear(PmcUnitEntity pmc) {
        ((IVehiclePatrol) pmc).sewv$clearPatrol();
    }

    // Uniform random point over the patrol disk, snapped to the surface, kept only if it is drivable
    // ground. Unloaded columns are skipped rather than force-loaded, so a patrol stays within the
    // area the hull can actually reach.
    @Nullable
    private static BlockPos pickWaypoint(Level level, BlockPos origin, int radius, RandomSource random) {
        for (int i = 0; i < PICK_ATTEMPTS; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double dist = Math.sqrt(random.nextDouble()) * radius; // sqrt → uniform over the disk, not clustered at centre
            int x = origin.getX() + (int) Math.round(Math.cos(angle) * dist);
            int z = origin.getZ() + (int) Math.round(Math.sin(angle) * dist);
            if (!level.hasChunkAt(x, z)) continue;

            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos pos = new BlockPos(x, y, z);
            if (CLASSIFIER.getBlockPathType(level, x, y, z) == BlockPathTypes.WALKABLE) {
                return pos;
            }
        }
        return null;
    }
}
