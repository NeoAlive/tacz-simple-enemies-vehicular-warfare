package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.bridge.IVehiclePatrol;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.navigation.GroundVehicleNodeEvaluator;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * The two area tasks a ground crew can be given from the TDT, both read by whichever drive goal
 * resolves the destination ({@link VehicleTargeting#resolveDestination}). They share one state slot
 * on the unit ({@link IVehiclePatrol}) because a hull can only be doing one of them.
 *
 * <ul>
 * <li><b>Patrol</b> — endless: hold a waypoint inside the circle, roll a new one every
 *     {@code patrolRotateIntervalTicks}.</li>
 * <li><b>Search &amp; Destroy</b> — one-time: each hull is handed its own angular sector at order
 *     time and zig-zags across it to cover the ground, then stands down. The first hull to acquire
 *     a target alerts every other hull on the task, which ends the sweep for all of them and puts
 *     them on that target.</li>
 * </ul>
 *
 * <p>Valid ground is judged with {@link GroundVehicleNodeEvaluator}'s own block classification, so a
 * waypoint is exactly the kind of node the driver's pathfinder can route to: air to sit in over
 * solid, non-hazard, dry footing ({@link BlockPathTypes#WALKABLE}).
 */
public final class PatrolSupport {

    private PatrolSupport() {}

    // Patrol: a waypoint is chosen by rejection sampling — this many tries before giving up for now.
    private static final int PICK_ATTEMPTS = 24;
    // If a roll finds nothing valid (all sampled far chunks unloaded, or no dry ground), retry
    // soon rather than sitting on a stale/absent waypoint for the whole rotation interval.
    private static final int PICK_RETRY_TICKS = 100;

    // Search & destroy: legs per sector, and how far off an ideal leg point we will settle for
    // drivable ground before skipping the leg.
    private static final int SWEEP_STEPS = 5;
    private static final int NEAR_ATTEMPTS = 10;
    private static final int NEAR_JITTER = 16;
    // Arrival radius for a sweep leg — looser than a formation slot; this is "swept past", not "parked on".
    private static final double SWEEP_ARRIVE_SQ = 12.0 * 12.0;
    // A leg the hull cannot reach must not stall the sweep: move on after this long (game time).
    private static final int SWEEP_STEP_TIMEOUT = 1200; // 60s
    // Contact alert reaches across the whole area (a hull can be a full diameter away), with a floor.
    private static final double ALERT_MIN_RANGE = 64.0;

    // Mutual support while on an area task, gated differently per task:
    //   Search & destroy — hulls back each other up freely, but a hull may only be pulled onto a NEW
    //   ally this often, or a single contact collapses the whole sweep into one scrum.
    //   Patrol — a patrol is a standing posture, so it is only broken for an ally that is actually
    //   in trouble. That is DriveVehicleGoal's own low-health test, not a threshold of our own: it
    //   is the same point the hurt hull starts retreating at, so help is called exactly when it
    //   breaks contact.
    private static final int SEARCH_ASSIST_COOLDOWN = 400; // 20s at 20 ticks/s

    // The evaluator's single-block classifier (getBlockPathType(level,x,y,z)) touches no instance
    // state, so one shared instance is safe on the single-threaded server tick.
    private static final GroundVehicleNodeEvaluator CLASSIFIER = new GroundVehicleNodeEvaluator();

    /**
     * The point this crew's area task currently wants, or null when it has none (or has just
     * finished one) — resolveDestination then falls through to the ordinary order handling.
     */
    @Nullable
    public static BlockPos currentWaypoint(PmcUnitEntity pmc, @Nullable VehicleEntity vehicle) {
        IVehiclePatrol task = (IVehiclePatrol) pmc;
        BlockPos origin = task.sewv$getPatrolOrigin();
        if (origin == null) return null;

        return task.sewv$getPatrolMode() == IVehiclePatrol.MODE_SEARCH
                ? searchWaypoint(pmc, vehicle, task, origin)
                : patrolWaypoint(pmc, task, origin);
    }

    /**
     * Where this crew should go to reinforce an ally in contact instead of working its own leg of
     * the area, or null when nothing qualifies. Only meaningful while an area task is live —
     * {@link VehicleTargeting#resolveDestination} calls it only then.
     */
    @Nullable
    public static BlockPos assistPos(PmcUnitEntity pmc, VehicleEntity vehicle,
                                     @Nullable VehicleTargeting.AllyAssist assist) {
        if (assist == null || vehicle == null) return null;
        IVehiclePatrol task = (IVehiclePatrol) pmc;

        // Scanning out across the whole tasked area, not the configured assist range, is what makes
        // EVERY hull on the order answer the call rather than only those that happen to be close.
        double reach = areaReach(task.sewv$getPatrolRadius());

        if (task.sewv$getPatrolMode() == IVehiclePatrol.MODE_SEARCH) {
            // Sweeping hulls support each other equally — any ally in contact — but only commit to a
            // new one on the cooldown, so the sweep survives its own first contact.
            return assist.assistTargetPos(pmc, vehicle, null, SEARCH_ASSIST_COOLDOWN, reach);
        }
        // Patrol: answer only an ally hurt badly enough that the drive goal has it breaking contact.
        return assist.assistTargetPos(pmc, vehicle, DriveVehicleGoal::isLowHealth, 0, reach);
    }

    /**
     * Reach that spans the whole tasked area: two hulls both inside the circle can be a full
     * diameter apart, so anything less would leave the far side of the area unable to answer.
     */
    private static double areaReach(int radius) {
        return Math.max(radius * 2.0, ALERT_MIN_RANGE);
    }

    public static void beginPatrol(PmcUnitEntity pmc, BlockPos origin, int radius) {
        ((IVehiclePatrol) pmc).sewv$setAreaTask(origin, radius, IVehiclePatrol.MODE_PATROL, 0, 1);
    }

    /** {@code sector} of {@code sectorCount} is this hull's slice of the circle to sweep. */
    public static void beginSearch(PmcUnitEntity pmc, BlockPos origin, int radius, int sector, int sectorCount) {
        ((IVehiclePatrol) pmc).sewv$setAreaTask(origin, radius, IVehiclePatrol.MODE_SEARCH, sector, sectorCount);
    }

    public static void clear(PmcUnitEntity pmc) {
        ((IVehiclePatrol) pmc).sewv$clearPatrol();
    }

    // --- Patrol: endless random wander, re-rolled on the config interval ---------------------

    @Nullable
    private static BlockPos patrolWaypoint(PmcUnitEntity pmc, IVehiclePatrol task, BlockPos origin) {
        long now = pmc.level().getGameTime();
        BlockPos waypoint = task.sewv$getPatrolWaypoint();
        if (waypoint != null && now < task.sewv$getPatrolNextRotate()) {
            return waypoint;
        }

        BlockPos fresh = pickWaypoint(pmc.level(), origin, task.sewv$getPatrolRadius(), pmc.getRandom());
        if (fresh != null) {
            task.sewv$setPatrolWaypoint(fresh);
            task.sewv$setPatrolNextRotate(now + SewvConfig.PATROL_ROTATE_INTERVAL_TICKS.get());
            return fresh;
        }
        // Nothing valid this roll — hold what we have (or rally on the origin) and try again shortly.
        task.sewv$setPatrolNextRotate(now + PICK_RETRY_TICKS);
        return waypoint != null ? waypoint : origin;
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
            BlockPos pos = drivableColumn(level, x, z);
            if (pos != null) return pos;
        }
        return null;
    }

    // --- Search & destroy: one-time sector sweep, ending on contact ---------------------------

    @Nullable
    private static BlockPos searchWaypoint(PmcUnitEntity pmc, @Nullable VehicleEntity vehicle,
                                           IVehiclePatrol task, BlockPos origin) {
        // Contact is the whole point of the sweep: pass it to the rest of the group and stand down.
        LivingEntity target = pmc.getTarget();
        if (target != null && target.isAlive()) {
            alertGroup(pmc, task, target);
            return null; // resolveDestination now falls through to the ATTACK_THAT_TARGET we just set
        }

        long now = pmc.level().getGameTime();
        BlockPos waypoint = task.sewv$getPatrolWaypoint();
        int step = task.sewv$getPatrolStep();

        if (waypoint != null) {
            boolean reached = vehicle != null && horizontalDistSq(vehicle, waypoint) <= SWEEP_ARRIVE_SQ;
            // A leg it cannot reach must not stall the sweep — time out and move on.
            if (!reached && now < task.sewv$getPatrolStepDeadline()) return waypoint;
            step++;
        }

        // Advance to the next leg that actually has drivable ground; skipping a dud beats stalling.
        int radius = task.sewv$getPatrolRadius();
        int sector = task.sewv$getPatrolSector();
        int sectorCount = task.sewv$getPatrolSectorCount();
        while (step < SWEEP_STEPS) {
            BlockPos next = sweepPoint(pmc.level(), origin, radius, sector, sectorCount, step, pmc.getRandom());
            if (next != null) {
                task.sewv$setPatrolStep(step);
                task.sewv$setPatrolWaypoint(next);
                task.sewv$setPatrolStepDeadline(now + SWEEP_STEP_TIMEOUT);
                return next;
            }
            step++;
        }

        clear(pmc); // sector swept and nothing found — a one-time assignment ends here
        return null;
    }

    /**
     * The {@code step}-th leg of this hull's sector: the bearing walks across the sector while the
     * range alternates deep/shallow, so the hull zig-zags over its slice instead of driving one line
     * through it.
     */
    @Nullable
    private static BlockPos sweepPoint(Level level, BlockPos origin, int radius,
                                       int sector, int sectorCount, int step, RandomSource random) {
        double sectorWidth = (Math.PI * 2.0) / Math.max(1, sectorCount);
        double angle = sectorWidth * sector + sectorWidth * ((step + 0.5) / SWEEP_STEPS);
        double dist = radius * (step % 2 == 0 ? 0.9 : 0.45);
        return findDrivableNear(level,
                origin.getX() + Math.cos(angle) * dist,
                origin.getZ() + Math.sin(angle) * dist,
                random);
    }

    /** Alert every other hull on this sweep, end the task for all of them, and hand them the contact. */
    private static void alertGroup(PmcUnitEntity finder, IVehiclePatrol task, LivingEntity target) {
        double range = areaReach(task.sewv$getPatrolRadius());
        UUID owner = finder.getOwnerUUID();

        // The finder stands down too, and gets pinned on what it found (SEM's AttackSpecificTargetGoal
        // re-forces the target from this id, so the contact isn't lost to a rescan).
        clear(finder);
        finder.setAttackTargetId(target.getId());
        finder.setOrder(OrderType.ATTACK_THAT_TARGET);
        if (owner == null) return;

        for (PmcUnitEntity other : finder.level().getEntitiesOfClass(
                PmcUnitEntity.class, finder.getBoundingBox().inflate(range))) {
            if (other == finder || !other.isAlive()) continue;
            if (!owner.equals(other.getOwnerUUID())) continue;

            IVehiclePatrol otherTask = (IVehiclePatrol) other;
            if (otherTask.sewv$getPatrolOrigin() == null
                    || otherTask.sewv$getPatrolMode() != IVehiclePatrol.MODE_SEARCH) continue;

            otherTask.sewv$clearPatrol();
            other.setAttackTargetId(target.getId());
            other.setOrder(OrderType.ATTACK_THAT_TARGET);
        }
    }

    // --- Shared ground checks -----------------------------------------------------------------

    // The ideal point rarely lands on drivable ground, so settle for something close to it.
    @Nullable
    private static BlockPos findDrivableNear(Level level, double x, double z, RandomSource random) {
        for (int i = 0; i < NEAR_ATTEMPTS; i++) {
            int bx = Mth.floor(x);
            int bz = Mth.floor(z);
            if (i > 0) { // first try is the exact column; after that, jitter around it
                bx += random.nextInt(NEAR_JITTER * 2 + 1) - NEAR_JITTER;
                bz += random.nextInt(NEAR_JITTER * 2 + 1) - NEAR_JITTER;
            }
            BlockPos pos = drivableColumn(level, bx, bz);
            if (pos != null) return pos;
        }
        return null;
    }

    // The surface of this column if a ground hull could sit and be pathed to there, else null.
    // Unloaded columns answer null rather than being force-loaded.
    @Nullable
    private static BlockPos drivableColumn(Level level, int x, int z) {
        if (!level.hasChunkAt(x, z)) return null;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return CLASSIFIER.getBlockPathType(level, x, y, z) == BlockPathTypes.WALKABLE
                ? new BlockPos(x, y, z) : null;
    }

    private static double horizontalDistSq(VehicleEntity vehicle, BlockPos pos) {
        double dx = pos.getX() + 0.5 - vehicle.getX();
        double dz = pos.getZ() + 0.5 - vehicle.getZ();
        return dx * dx + dz * dz;
    }
}
