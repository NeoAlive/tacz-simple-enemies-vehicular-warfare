package com.neoalive.tacz_sewv.entity.ai.support;

import java.util.List;
import java.util.UUID;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.jetbrains.annotations.Nullable;

import com.neoalive.tacz_sewv.bridge.IVehiclePatrol;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.debug.SewvDiag;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.goal.DriveVehicleGoal;
import com.neoalive.tacz_sewv.entity.ai.navigation.GroundVehicleNodeEvaluator;

/**
 * The area tasks a ground crew can be given, all read by whichever drive goal resolves the
 * destination ({@link VehicleTargeting#resolveDestination}). They share one state slot on the unit
 * ({@link IVehiclePatrol}) because a hull can only be doing one of them.
 *
 * <ul>
 * <li><b>Patrol</b> — endless: hold a waypoint inside the circle, roll a new one every
 *     {@code patrolRotateIntervalTicks}.</li>
 * <li><b>Search &amp; Destroy</b> — one-time: each hull is handed its own angular sector at order
 *     time and zig-zags across it to cover the ground, then stands down. The first hull to acquire
 *     a target alerts every other hull on the task, which ends the sweep for all of them and puts
 *     them on that target.</li>
 * <li><b>Cruise</b> — endless: drive the waypoints the player plotted on the world map, in order,
 *     looping. The only task whose points are given rather than generated, which is why it is the
 *     only one that does no ground sampling.</li>
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
    // Slack added to the drive goal's own stop distance before a cruise leg counts as reached.
    // It has to be MORE than the goal stops at, or the hull parks short of the leg and the route
    // only advances when the timeout fires — the goal halts at hull width - 1 + 8 blocks, so a
    // tighter "arrived" test than that can never be satisfied.
    private static final double CRUISE_ARRIVE_SLACK = 2.0;
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

        return switch (task.sewv$getPatrolMode()) {
            case IVehiclePatrol.MODE_SEARCH, IVehiclePatrol.MODE_SWEEP ->
                    searchWaypoint(pmc, vehicle, task, origin);
            case IVehiclePatrol.MODE_CRUISE -> cruiseWaypoint(pmc, vehicle, task);
            default -> patrolWaypoint(pmc, task, origin);
        };
    }

    /**
     * The leg of a plotted cruise this crew is on, advancing to the next one as each is reached and
     * wrapping at the end — a cruise is a loop, so it never stands the crew down by itself.
     *
     * <p>Unlike patrol and search, the points are the player's, so none of the ground-validity
     * sampling applies: if they plotted a leg the hull cannot reach, the timeout moves it on rather
     * than the route being silently edited. That timeout is what keeps one bad node from stalling
     * the loop forever, and it is the sweep's own — an unreachable leg is the same problem there.
     *
     * <p>"Reached" is measured off {@link VehicleTargeting#arrivalDistance} rather than a constant
     * of its own, because that is the distance the drive goal actually parks at: any tighter test
     * is unreachable by construction and would turn every leg into a 60-second wait.
     */
    @Nullable
    private static BlockPos cruiseWaypoint(PmcUnitEntity pmc, @Nullable VehicleEntity vehicle, IVehiclePatrol task) {
        List<BlockPos> route = task.sewv$getCruiseRoute();
        if (route.isEmpty()) return null;

        long now = pmc.level().getGameTime();
        int step = Math.floorMod(task.sewv$getPatrolStep(), route.size());
        BlockPos leg = route.get(step);

        if (task.sewv$getPatrolStepDeadline() == 0L) {
            task.sewv$setPatrolStepDeadline(now + SWEEP_STEP_TIMEOUT);
        }

        boolean arrived = false;
        if (vehicle != null) {
            double reach = VehicleTargeting.arrivalDistance(pmc, vehicle) + CRUISE_ARRIVE_SLACK;
            arrived = horizontalDistSq(vehicle, leg) <= reach * reach;
        }
        if (arrived || now >= task.sewv$getPatrolStepDeadline()) {
            task.sewv$setPatrolStep(step + 1 >= route.size() ? 0 : step + 1);
            task.sewv$setPatrolStepDeadline(now + SWEEP_STEP_TIMEOUT);
        }
        return leg;
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

        if (task.sewv$getPatrolMode() == IVehiclePatrol.MODE_SEARCH
                || task.sewv$getPatrolMode() == IVehiclePatrol.MODE_SWEEP) {
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

    /** Sweep &amp; Advance: zig-zag inside a chunk AABB (same contact/alert behaviour as search). */
    public static void beginSweep(PmcUnitEntity pmc, int left, int top, int right, int bottom,
                                  int sector, int sectorCount) {
        ((IVehiclePatrol) pmc).sewv$setSweepRect(left, top, right, bottom, sector, sectorCount);
    }

    /** Loop these waypoints in order, endlessly, until dismissed. */
    public static void beginCruise(PmcUnitEntity pmc, List<BlockPos> route) {
        ((IVehiclePatrol) pmc).sewv$setCruise(route);
    }

    /**
     * Whether this crew is on a plotted cruise, which is the one area task that does <b>not</b>
     * yield the wheel to a contact — see {@code DriveVehicleGoal.tick}. Prefer
     * {@link #holdsCourseThroughContact} for new call sites: patrol / search / sweep now share
     * that same commitment (fight from the area, do not abandon it to chase).
     */
    public static boolean isCruising(AbstractUnit unit) {
        return unit instanceof PmcUnitEntity
                && ((IVehiclePatrol) unit).sewv$getPatrolOrigin() != null
                && ((IVehiclePatrol) unit).sewv$getPatrolMode() == IVehiclePatrol.MODE_CRUISE;
    }

    /**
     * Mounted equivalent of {@link FollowLeash}: any live area task (patrol / S&amp;D / sweep /
     * cruise) keeps steering the area destination through contact. Weapon selection and fire
     * assist still run; only movement stays on the order. Badly-hurt crews still break off via
     * {@code DriveVehicleGoal}'s low-health path.
     */
    public static boolean holdsCourseThroughContact(AbstractUnit unit) {
        return unit instanceof PmcUnitEntity pmc
                && ((IVehiclePatrol) pmc).sewv$getPatrolOrigin() != null;
    }

    /**
     * True when {@code pos} lies inside this crew's standing area task (chunk rect for sweep,
     * origin+radius disk for patrol/search). Cruise has no disk — always true while cruising.
     * Used to refuse target locks outside the ordered ground so a sweep does not chase every
     * zombie on the horizon.
     */
    public static boolean isInsideAreaTask(PmcUnitEntity pmc, double x, double z) {
        IVehiclePatrol task = (IVehiclePatrol) pmc;
        if (task.sewv$getPatrolOrigin() == null) return true;
        int mode = task.sewv$getPatrolMode();
        if (mode == IVehiclePatrol.MODE_CRUISE) return true;
        if (mode == IVehiclePatrol.MODE_SWEEP && task.sewv$hasSweepRect()) {
            int minX = task.sewv$getSweepLeft() << 4;
            int maxX = (task.sewv$getSweepRight() << 4) + 16;
            int minZ = task.sewv$getSweepTop() << 4;
            int maxZ = (task.sewv$getSweepBottom() << 4) + 16;
            return x >= minX && x < maxX && z >= minZ && z < maxZ;
        }
        BlockPos origin = task.sewv$getPatrolOrigin();
        int radius = task.sewv$getPatrolRadius();
        double dx = x - origin.getX();
        double dz = z - origin.getZ();
        return dx * dx + dz * dz <= (double) radius * radius;
    }

    public static boolean isInsideAreaTask(PmcUnitEntity pmc, LivingEntity e) {
        return isInsideAreaTask(pmc, e.getX(), e.getZ());
    }

    /**
     * True when this unit is on a bounded area task and {@code target} lies outside it — callers
     * should refuse the lock. Cruise is unbounded (route, not a disk); returns false there.
     */
    public static boolean refusesOutOfAreaTarget(PmcUnitEntity pmc, LivingEntity target) {
        if (((com.neoalive.tacz_sewv.bridge.ISweepInfantry) pmc).sewv$hasInfantrySweep()) {
            var sweep = (com.neoalive.tacz_sewv.bridge.ISweepInfantry) pmc;
            int minX = sweep.sewv$getInfSweepLeft() << 4;
            int maxX = (sweep.sewv$getInfSweepRight() << 4) + 16;
            int minZ = sweep.sewv$getInfSweepTop() << 4;
            int maxZ = (sweep.sewv$getInfSweepBottom() << 4) + 16;
            double x = target.getX();
            double z = target.getZ();
            return !(x >= minX && x < maxX && z >= minZ && z < maxZ);
        }
        IVehiclePatrol task = (IVehiclePatrol) pmc;
        if (task.sewv$getPatrolOrigin() == null) return false;
        if (task.sewv$getPatrolMode() == IVehiclePatrol.MODE_CRUISE) return false;
        return !isInsideAreaTask(pmc, target);
    }

    public static void clear(PmcUnitEntity pmc) {
        IVehiclePatrol task = (IVehiclePatrol) pmc;
        int mode = task.sewv$getPatrolOrigin() == null ? -1 : task.sewv$getPatrolMode();
        boolean hadSweepRect = task.sewv$hasSweepRect();
        task.sewv$clearPatrol();
        if (mode == IVehiclePatrol.MODE_SWEEP || hadSweepRect) {
            SewvDiag.sweep(
                    "areaTaskClear ONLY (not membership) unit={}#{} priorMode={} hadRect={} "
                            + "— SweepAdvancement assignees UNCHANGED; claim is NOT this path",
                    pmc.getClass().getSimpleName(), pmc.getId(), mode, hadSweepRect);
        }
    }

    /**
     * Dismiss / SEM order / bail / dismount: drop area task + infantry sweep + operation assignee.
     * {@code reason} is logged so a live run can tell cancel-without-claim from completion.
     */
    public static void clearSweepMembership(PmcUnitEntity pmc, String reason) {
        boolean hadMounted = ((IVehiclePatrol) pmc).sewv$getPatrolOrigin() != null
                || ((IVehiclePatrol) pmc).sewv$hasSweepRect();
        boolean hadInf = ((com.neoalive.tacz_sewv.bridge.ISweepInfantry) pmc).sewv$hasInfantrySweep();
        SewvDiag.sweep(
                "clearSweepMembership reason={} unit={}#{} hadMountedTask={} hadInfSweep={} "
                        + "→ unregisterUnit (cancel op if last assignee; NO claim)",
                reason, pmc.getClass().getSimpleName(), pmc.getId(), hadMounted, hadInf);
        clear(pmc);
        if (hadInf) {
            ((com.neoalive.tacz_sewv.bridge.ISweepInfantry) pmc).sewv$clearInfantrySweep();
        }
        com.neoalive.tacz_sewv.invasion.SweepAdvancement.unregisterUnit(pmc, reason);
    }

    /** @deprecated use {@link #clearSweepMembership(PmcUnitEntity, String)} */
    @Deprecated
    public static void clearSweepMembership(PmcUnitEntity pmc) {
        clearSweepMembership(pmc, "unspecified");
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
    // Package-private rather than private: IdleSupport wants exactly this rejection sample for an
    // idle hull's short drift, at a much smaller radius.
    @Nullable
    public static BlockPos pickWaypoint(Level level, BlockPos origin, int radius, RandomSource random) {
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

    // --- Search & destroy / Sweep & Advance: sector or rect legs; fight from the area on contact ---

    @Nullable
    private static BlockPos searchWaypoint(PmcUnitEntity pmc, @Nullable VehicleEntity vehicle,
                                           IVehiclePatrol task, BlockPos origin) {
        // Contact: share with the group, but keep working the sector/rect. Returning null here
        // used to park the hull (getTargetPos null → stop) and, with the old alertGroup clear,
        // permanently ended the area task so quiet never resolved.
        LivingEntity target = pmc.getTarget();
        if (target != null && target.isAlive()) {
            alertGroup(pmc, task, target);
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
        int sector = task.sewv$getPatrolSector();
        int sectorCount = task.sewv$getPatrolSectorCount();
        boolean rect = task.sewv$getPatrolMode() == IVehiclePatrol.MODE_SWEEP && task.sewv$hasSweepRect();
        while (step < SWEEP_STEPS) {
            BlockPos next = rect
                    ? rectSweepPoint(pmc.level(), task, sector, sectorCount, step, pmc.getRandom())
                    : sweepPoint(pmc.level(), origin, task.sewv$getPatrolRadius(),
                            sector, sectorCount, step, pmc.getRandom());
            if (next != null) {
                task.sewv$setPatrolStep(step);
                task.sewv$setPatrolWaypoint(next);
                task.sewv$setPatrolStepDeadline(now + SWEEP_STEP_TIMEOUT);
                return next;
            }
            step++;
        }

        int exhaustedMode = task.sewv$getPatrolMode();
        SewvDiag.sweep(
                "sectorExhausted → areaTaskClear unit={}#{} modeWas={} — units go idle/FREE here; "
                        + "claim still depends on SweepAdvancement quiet+defensive (assignees kept)",
                pmc.getClass().getSimpleName(), pmc.getId(), exhaustedMode);
        clear(pmc); // sector swept — ends the area task only; Sweep & Advance claim is independent
        return null;
    }

    /** Zig-zag legs sampled inside the chunk rectangle (MODE_SWEEP). */
    @Nullable
    private static BlockPos rectSweepPoint(Level level, IVehiclePatrol task,
                                           int sector, int sectorCount, int step, RandomSource random) {
        int left = task.sewv$getSweepLeft() << 4;
        int right = (task.sewv$getSweepRight() << 4) + 15;
        int top = task.sewv$getSweepTop() << 4;
        int bottom = (task.sewv$getSweepBottom() << 4) + 15;
        double sectorWidth = 1.0 / Math.max(1, sectorCount);
        double u0 = sectorWidth * sector;
        double u = u0 + sectorWidth * ((step + 0.5) / SWEEP_STEPS);
        double v = step % 2 == 0 ? 0.85 : 0.35;
        double x = left + (right - left) * u;
        double z = top + (bottom - top) * v;
        return findDrivableNear(level, x, z, random);
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

    /** Share a contact with the rest of the search/sweep group without abandoning the area task. */
    private static void alertGroup(PmcUnitEntity finder, IVehiclePatrol task, LivingEntity target) {
        double range = areaReach(task.sewv$getPatrolRadius());
        UUID owner = finder.getOwnerUUID();

        // Commitment: do NOT clear the area task. Clearing used to hand movement to chase AI and
        // permanently leave Sweep & Advance / S&D — quiet never resolved. Fight from the area;
        // DriveVehicleGoal holds course via holdsCourseThroughContact.
        finder.setTarget(target);
        if (owner == null) return;

        for (PmcUnitEntity other : finder.level().getEntitiesOfClass(
                PmcUnitEntity.class, finder.getBoundingBox().inflate(range))) {
            if (other == finder || !other.isAlive()) continue;
            if (!owner.equals(other.getOwnerUUID())) continue;

            IVehiclePatrol otherTask = (IVehiclePatrol) other;
            if (otherTask.sewv$getPatrolOrigin() == null) continue;
            int mode = otherTask.sewv$getPatrolMode();
            if (mode != IVehiclePatrol.MODE_SEARCH && mode != IVehiclePatrol.MODE_SWEEP) continue;

            other.setTarget(target);
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
    public static BlockPos drivableColumn(Level level, int x, int z) {
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
