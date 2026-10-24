package com.neoalive.tacz_sewv.entity.ai.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.IPathwayInfantry;
import com.neoalive.tacz_sewv.bridge.IPmcDowned;
import com.neoalive.tacz_sewv.bridge.ISweepInfantry;
import com.neoalive.tacz_sewv.bridge.ITerritoryPost;
import com.neoalive.tacz_sewv.bridge.IVehiclePatrol;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.fob.FobSupport;
import com.neoalive.tacz_sewv.map.PreferredPathwayData;

/**
 * Preferred pathway leg logic and passive entry matching.
 *
 * <p>A path is a funnel with two mouths: a unit near EITHER end node joins it, oriented from that
 * end, only when its MOVE destination is coaxial with the path (see {@link #coaxialExit}). Joining
 * mid-path is deliberately impossible: a unit merely crossing a path must never be captured by it.
 */
public final class PathwaySupport {

    /** Released when a passive walker strays this far from every segment (24 blocks). */
    public static final double MOVE_CORRIDOR_RADIUS_SQ = 576.0;
    /** cos 30°: a destination past the exit within this bearing of the path axis still counts as coaxial. */
    public static final double COAX_COS = 0.866;
    /** Infantry arrival — same band as sweep wander. */
    public static final double ARRIVE_SQ = 2.5 * 2.5;
    /** Per-leg timeout — same as sweep/cruise step timeout. */
    public static final long STEP_TIMEOUT = 60 * 20L;
    /** After a route completes, FREE_FIRE velocity matching stays off this long (game ticks). */
    public static final long PASSIVE_COOLDOWN = 60 * 20L;
    /** How often an active pathway re-reads the saved catalog for edits/deletes. */
    public static final int ROUTE_STALE_CHECK_INTERVAL = 100;
    public static final double PARALLEL_DOT = 0.707;
    /** Minimum horizontal speed to count as moving. */
    public static final double MIN_SPEED = 0.05;
    /** Passive scan cadence (game ticks). */
    public static final int PASSIVE_INTERVAL = 40;
    /** Horizontal scan for manual funnel — matches TDT / commander ribbon range. */
    public static final double FUNNEL_RADIUS = 512.0;

    private PathwaySupport() {}

    public static void begin(PmcUnitEntity pmc, List<BlockPos> route, int startStep,
                             @Nullable String pathId, boolean passive) {
        if (route.size() < 2) return;
        int step = Math.max(0, Math.min(startStep, route.size() - 1));
        IPathwayInfantry pathway = (IPathwayInfantry) pmc;
        pathway.sewv$setPathway(route, step, pathId == null ? "" : pathId, passive);
        pathway.sewv$setPathwayStepDeadline(pmc.level().getGameTime() + STEP_TIMEOUT);
    }

    /**
     * Drops a pathway whose saved id was deleted, or whose route no longer matches the catalog
     * (path edited after the unit joined).
     */
    public static boolean refreshPathwayRoute(PmcUnitEntity pmc) {
        IPathwayInfantry pathway = (IPathwayInfantry) pmc;
        if (!pathway.sewv$hasPathway()) return true;

        long now = pmc.level().getGameTime();
        if (now < pathway.sewv$getPathwayStaleCheck()) return true;
        pathway.sewv$setPathwayStaleCheck(now + ROUTE_STALE_CHECK_INTERVAL);

        String sourceId = pathway.sewv$getPathwaySourceId();
        if (sourceId == null || sourceId.isEmpty()) return true;

        UUID owner = pmc.getOwnerUUID();
        if (owner == null) {
            pathway.sewv$clearPathway();
            return false;
        }

        PreferredPathwayData.PathCatalog catalog = PreferredPathwayData.forOwner(
                pmc.level(), owner, pmc.level().dimension());
        List<BlockPos> live = catalog.waypoints(sourceId);
        if (live == null || live.size() < 2) {
            pathway.sewv$clearPathway();
            return false;
        }

        List<BlockPos> held = pathway.sewv$getPathwayRoute();
        if (pathway.sewv$isPathwayPassive()) {
            // A passive route is the catalog path oriented from its entry and maybe truncated, so it
            // is a prefix of the live path one way or the other. An edit drops it: the next scan
            // re-matches against the new geometry, which beats patching an oriented copy.
            if (!isPrefix(held, live) && !isPrefix(held, reversed(live))) {
                pathway.sewv$clearPathway();
                return false;
            }
            // The route was chosen for one MOVE destination: if the order moved under us, drop it and
            // let the next scan decide afresh. Re-running the geometry here instead would drop a unit
            // that legitimately came in from off-axis on the detour rule.
            Vec3 dest = moveDest(pmc);
            if (dest != null && !pathway.sewv$wasFunneledFor(BlockPos.containing(dest).asLong())) {
                pathway.sewv$clearPathway();
                return false;
            }
            return true;
        }
        if (!held.equals(live)) {
            int step = Math.min(pathway.sewv$getPathwayStep(), live.size() - 1);
            pathway.sewv$setPathway(live, step, sourceId, false);
        }
        return true;
    }

    /**
     * Current leg waypoint, advancing on arrival or timeout. Returns null when the route is finished
     * (one-shot — no loop).
     */
    @Nullable
    public static BlockPos currentLeg(PmcUnitEntity pmc) {
        if (!refreshPathwayRoute(pmc)) return null;

        IPathwayInfantry pathway = (IPathwayInfantry) pmc;
        List<BlockPos> route = pathway.sewv$getPathwayRoute();
        if (route.size() < 2) {
            pathway.sewv$clearPathway();
            return null;
        }

        long now = pmc.level().getGameTime();
        int step = pathway.sewv$getPathwayStep();
        if (step >= route.size()) {
            pathway.sewv$clearPathway();
            return null;
        }

        BlockPos leg = route.get(step);
        if (pathway.sewv$getPathwayStepDeadline() == 0L) {
            pathway.sewv$setPathwayStepDeadline(now + STEP_TIMEOUT);
        }

        double dx = leg.getX() + 0.5 - pmc.getX();
        double dz = leg.getZ() + 0.5 - pmc.getZ();
        boolean arrived = dx * dx + dz * dz <= ARRIVE_SQ;
        if (arrived || now >= pathway.sewv$getPathwayStepDeadline()) {
            step++;
            if (step >= route.size()) {
                pathway.sewv$finishPathway();
                return null;
            }
            pathway.sewv$setPathwayStep(step);
            pathway.sewv$setPathwayStepDeadline(now + STEP_TIMEOUT);
            leg = route.get(step);
        }
        return leg;
    }

    /**
     * Whether the passive scan may put this unit on a path now. On foot: an unfinished MOVE, or an
     * idle FREE_FIRE (velocity matching, behind the completion cooldown). Mounted: the driver of a
     * ground hull under an unfinished MOVE only — a FREE_FIRE hull is idle-wandering, and its idle
     * anchor would just drive it back across the river it was funnelled over.
     * A MOVE funnels at most once per destination ({@code sewv$wasFunneledFor}), which is what stops
     * a unit standing at a short path's exit from re-joining it forever.
     */
    public static boolean canPassiveTrigger(PmcUnitEntity pmc) {
        if (pmc instanceof IPmcDowned d && d.sewv$isDowned()) return false;
        IPathwayInfantry pathway = (IPathwayInfantry) pmc;
        if (pathway.sewv$hasPathway()) return false;
        if (((IVehiclePatrol) pmc).sewv$getPatrolOrigin() != null) return false;
        if (((ITerritoryPost) pmc).sewv$hasTerritoryPost()) return false;
        if (FobSupport.hasRoutePending(pmc)) return false;
        Vec3 dest = moveDest(pmc);
        if (dest != null && pathway.sewv$wasFunneledFor(BlockPos.containing(dest).asLong())) return false;

        if (pmc.getVehicle() != null) {
            if (!(pmc.getVehicle() instanceof VehicleEntity hull) || !isGroundFunnelUnit(pmc)) return false;
            if (dest == null) return false;
            double arrive = VehicleTargeting.arrivalDistance(pmc, hull);
            return hull.distanceToSqr(dest) > arrive * arrive;
        }

        if (((ISweepInfantry) pmc).sewv$hasInfantrySweep()) return false;
        OrderType order = pmc.getOrder();
        if (order == OrderType.FREE_FIRE) {
            if (pmc.getTarget() != null && pmc.getTarget().isAlive()) return false;
            return !pathway.sewv$isPathwayPassiveBlocked();
        }
        return order == OrderType.MOVE_TO_POSITION && FollowLeash.enRouteToMove(pmc);
    }

    /** The standing MOVE_TO_POSITION destination, or null. */
    @Nullable
    public static Vec3 moveDest(PmcUnitEntity pmc) {
        if (pmc.getOrder() != OrderType.MOVE_TO_POSITION) return null;
        Vec3 dest = pmc.getMoveToTarget();
        return dest == null || dest.equals(Vec3.ZERO) ? null : dest;
    }

    public static double entryRadius() {
        return SewvConfig.PATHWAY_ENTRY_RADIUS.get();
    }

    public static double coaxRadius() {
        return SewvConfig.PATHWAY_COAX_RADIUS.get();
    }

    public static double detourTolerance() {
        return SewvConfig.PATHWAY_DETOUR_TOLERANCE.get();
    }

    /**
     * Passive MOVE releases when the unit leaves the path corridor or reaches its MOVE destination.
     * Parallel bearing is a join gate only — not re-checked every tick (adjacent MOVE paths rarely
     * aim exactly along the segment).
     */
    public static boolean shouldAbandonPassivePath(PmcUnitEntity pmc) {
        IPathwayInfantry pathway = (IPathwayInfantry) pmc;
        if (!pathway.sewv$hasPathway() || !pathway.sewv$isPathwayPassive()) return false;
        if (pmc.getOrder() != OrderType.MOVE_TO_POSITION) return false;

        List<BlockPos> route = pathway.sewv$getPathwayRoute();
        if (route.size() < 2) return true;

        // Step 0 is the walk TO the entry, possibly from far off the path (detour rule): the corridor
        // only applies once the unit is on it. A stuck approach is bounded by the leg timeout.
        if (pathway.sewv$getPathwayStep() > 0
                && nearestSegmentIndex(route, pmc.getX(), pmc.getZ(), MOVE_CORRIDOR_RADIUS_SQ) < 0) return true;

        Vec3 dest = pmc.getMoveToTarget();
        if (dest != null && !dest.equals(Vec3.ZERO) && pmc.distanceToSqr(dest) < ARRIVE_SQ) {
            return true;
        }
        return false;
    }

    /**
     * On-foot infantry or the driver of a non-air, non-ship ground hull — what manual funnel targets.
     */
    public static boolean isGroundFunnelUnit(PmcUnitEntity pmc) {
        if (pmc instanceof IPmcDowned d && d.sewv$isDowned()) return false;
        Entity vehicle = pmc.getVehicle();
        if (vehicle == null) return true;
        if (!(vehicle instanceof VehicleEntity hull)) return false;
        if (hull.getFirstPassenger() != pmc) return false;
        return !HullFacts.isHelicopterHull(hull) && !HullFacts.isPlaneHull(hull) && !HullFacts.isShipHull(hull);
    }

    /** Every owned ground PMC within funnel range (on foot or driving a ground hull). */
    public static List<PmcUnitEntity> funnelCandidates(ServerPlayer player) {
        double r = FUNNEL_RADIUS;
        AABB box = player.getBoundingBox().inflate(r, r + 512.0, r);
        double rSq = r * r;
        List<PmcUnitEntity> out = new ArrayList<>();
        for (PmcUnitEntity pmc : player.serverLevel().getEntitiesOfClass(PmcUnitEntity.class, box)) {
            if (!pmc.isOwnedBy(player)) continue;
            if (!isGroundFunnelUnit(pmc)) continue;
            double dx = pmc.getX() - player.getX();
            double dz = pmc.getZ() - player.getZ();
            if (dx * dx + dz * dz > rSq) continue;
            out.add(pmc);
        }
        return out;
    }

    /** A route to walk (entry first, possibly truncated) and its detour ratio: lower is better. */
    public record Match(List<BlockPos> route, double cost) {}

    /**
     * Passive entry, or null. A MOVE destination is matched two ways:
     * <ul>
     * <li><b>at the mouth</b> (within {@code entryR} of an end node): a coaxial destination always
     * qualifies (see {@link #coaxialExit});</li>
     * <li><b>anywhere</b>: when going unit → entry → along the path → destination is at most
     * {@code detourTol} times the straight line. This is what makes a unit standing off to the side
     * of a bridge walk over to it instead of wading the river; {@code detourTol <= 1} turns it off.</li>
     * </ul>
     * The unit leaves at the exit, or at the node nearest a destination on the path. {@code dest} null
     * means "no MOVE": only the mouth counts, matched on {@code velocity}. Both orientations are
     * tried and the cheaper wins. Pure (no world reads), so the self-check covers it.
     */
    @Nullable
    public static Match matchEntry(double ux, double uz, @Nullable Vec3 dest, Vec3 velocity,
                                   List<BlockPos> waypoints, double entryR, double coaxR, double detourTol) {
        if (waypoints.size() < 2) return null;
        Match fwd = orient(ux, uz, dest, velocity, waypoints, entryR, coaxR, detourTol);
        Match back = orient(ux, uz, dest, velocity, reversed(waypoints), entryR, coaxR, detourTol);
        if (fwd == null || back == null) return fwd != null ? fwd : back;
        return fwd.cost() <= back.cost() ? fwd : back;
    }

    @Nullable
    private static Match orient(double ux, double uz, @Nullable Vec3 dest, Vec3 velocity,
                                List<BlockPos> route, double entryR, double coaxR, double detourTol) {
        boolean atMouth = distSq(route.get(0), ux, uz) <= entryR * entryR;

        if (dest == null) {
            double[] axis = axis(route);
            double speed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
            if (!atMouth || axis == null || speed < MIN_SPEED) return null;
            return (velocity.x * axis[0] + velocity.z * axis[1]) / speed >= PARALLEL_DOT
                    ? new Match(route, 0.0) : null;
        }

        // Leave at the far end, never part-way: a path exists to get across something, and the
        // shortest-distance node is the middle of the bridge. Only a destination ON the path stops early.
        int exit = leaveIndex(route, dest.x, dest.z, coaxR);
        double best = Math.sqrt(distSq(route.get(0), ux, uz)) + Math.sqrt(distSq(route.get(exit), dest.x, dest.z));
        for (int k = 1; k <= exit; k++) {
            best += Math.sqrt(distSq(route.get(k - 1), route.get(k).getX() + 0.5, route.get(k).getZ() + 0.5));
        }
        double direct = Math.max(Math.sqrt((dest.x - ux) * (dest.x - ux) + (dest.z - uz) * (dest.z - uz)), 1.0e-4);
        double cost = best / direct;
        boolean accepted = cost <= detourTol || (atMouth && coaxialExit(route, dest.x, dest.z, coaxR) >= 0);
        return accepted ? new Match(List.copyOf(route.subList(0, exit + 1)), cost) : null;
    }

    /**
     * Last route index to walk for a destination at ({@code dx}, {@code dz}), or -1 when the
     * destination is not coaxial with the path. The axis is the entry-to-exit chord. Ahead of the
     * entry and short of the exit, the destination has to lie on the path itself (within
     * {@code coaxR} of a segment) and the walk stops at the node nearest it. Past the exit it has to
     * sit inside a cylinder of {@code coaxR} round the extended axis, or within 30 degrees of the
     * axis seen from the exit: the cone is what keeps a far destination slightly off-axis.
     */
    static int coaxialExit(List<BlockPos> route, double dx, double dz, double coaxR) {
        double[] axis = axis(route);
        if (axis == null) return -1;
        BlockPos entry = route.get(0);
        BlockPos exit = route.get(route.size() - 1);
        double rx = dx - (entry.getX() + 0.5);
        double rz = dz - (entry.getZ() + 0.5);
        double along = rx * axis[0] + rz * axis[1];
        if (along <= 0) return -1;

        if (along < axis[2]) {
            return nearestSegmentIndex(route, dx, dz, coaxR * coaxR) < 0 ? -1 : leaveIndex(route, dx, dz, coaxR);
        }
        if (Math.abs(rx * axis[1] - rz * axis[0]) <= coaxR) return route.size() - 1;
        double ox = dx - (exit.getX() + 0.5);
        double oz = dz - (exit.getZ() + 0.5);
        double off = Math.sqrt(ox * ox + oz * oz);
        return ox * axis[0] + oz * axis[1] >= COAX_COS * off ? route.size() - 1 : -1;
    }

    /**
     * Node to leave the path at: the node nearest a destination lying on the path (within
     * {@code coaxR} of a segment), else the exit. Never 0, the entry itself.
     */
    private static int leaveIndex(List<BlockPos> route, double dx, double dz, double coaxR) {
        int seg = nearestSegmentIndex(route, dx, dz, coaxR * coaxR);
        if (seg < 0) return route.size() - 1;
        int node = distSq(route.get(seg), dx, dz) <= distSq(route.get(seg + 1), dx, dz) ? seg : seg + 1;
        return Math.max(1, node);
    }

    /** {unit x, unit z, length} of the entry-to-exit chord, or null for a degenerate path. */
    @Nullable
    private static double[] axis(List<BlockPos> route) {
        BlockPos a = route.get(0);
        BlockPos b = route.get(route.size() - 1);
        double x = b.getX() - a.getX();
        double z = b.getZ() - a.getZ();
        double len = Math.sqrt(x * x + z * z);
        return len < 1.0e-4 ? null : new double[] {x / len, z / len, len};
    }

    private static double distSq(BlockPos p, double x, double z) {
        double dx = x - (p.getX() + 0.5);
        double dz = z - (p.getZ() + 0.5);
        return dx * dx + dz * dz;
    }

    static List<BlockPos> reversed(List<BlockPos> route) {
        List<BlockPos> out = new ArrayList<>(route);
        Collections.reverse(out);
        return out;
    }

    static boolean isPrefix(List<BlockPos> prefix, List<BlockPos> route) {
        return prefix.size() <= route.size() && prefix.equals(route.subList(0, prefix.size()));
    }

    /** Cheap reject before segment loops: path AABB expanded by margin blocks. */
    public static boolean pathBboxNear(double ex, double ez, List<BlockPos> waypoints, double margin) {
        if (waypoints.isEmpty()) return false;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos wp : waypoints) {
            minX = Math.min(minX, wp.getX());
            maxX = Math.max(maxX, wp.getX());
            minZ = Math.min(minZ, wp.getZ());
            maxZ = Math.max(maxZ, wp.getZ());
        }
        int m = (int) Math.ceil(margin);
        return ex >= minX - m && ex <= maxX + m + 1 && ez >= minZ - m && ez <= maxZ + m + 1;
    }

    static int nearestSegmentIndex(List<BlockPos> waypoints, double ex, double ez, double maxDistSq) {
        int best = -1;
        double bestDistSq = maxDistSq + 1.0;
        for (int i = 0; i < waypoints.size() - 1; i++) {
            double distSq = distanceToSegmentSqHorizontal(ex, ez, waypoints.get(i), waypoints.get(i + 1));
            if (distSq <= maxDistSq && distSq < bestDistSq) {
                bestDistSq = distSq;
                best = i;
            }
        }
        return best;
    }

    /** Squared horizontal distance from point to segment A→B. */
    public static double distanceToSegmentSqHorizontal(double ex, double ez, BlockPos a, BlockPos b) {
        double ax = a.getX() + 0.5;
        double az = a.getZ() + 0.5;
        double bx = b.getX() + 0.5;
        double bz = b.getZ() + 0.5;

        double abx = bx - ax;
        double abz = bz - az;
        double abLenSq = abx * abx + abz * abz;
        if (abLenSq < 1.0e-8) {
            double dx = ex - ax;
            double dz = ez - az;
            return dx * dx + dz * dz;
        }

        double t = ((ex - ax) * abx + (ez - az) * abz) / abLenSq;
        t = Math.max(0.0, Math.min(1.0, t));
        double px = ax + t * abx;
        double pz = az + t * abz;
        double dx = ex - px;
        double dz = ez - pz;
        return dx * dx + dz * dz;
    }

    /** Squared distance from point E to segment A→B (3D) — kept for self-check compatibility. */
    public static double distanceToSegmentSq(double ex, double ey, double ez,
                                             BlockPos a, BlockPos b) {
        double ax = a.getX() + 0.5;
        double ay = a.getY();
        double az = a.getZ() + 0.5;
        double bx = b.getX() + 0.5;
        double by = b.getY();
        double bz = b.getZ() + 0.5;

        double abx = bx - ax;
        double aby = by - ay;
        double abz = bz - az;
        double abLenSq = abx * abx + aby * aby + abz * abz;
        if (abLenSq < 1.0e-8) {
            double dx = ex - ax;
            double dy = ey - ay;
            double dz = ez - az;
            return dx * dx + dy * dy + dz * dz;
        }

        double t = ((ex - ax) * abx + (ey - ay) * aby + (ez - az) * abz) / abLenSq;
        t = Math.max(0.0, Math.min(1.0, t));
        double px = ax + t * abx;
        double py = ay + t * aby;
        double pz = az + t * abz;
        double dx = ex - px;
        double dy = ey - py;
        double dz = ez - pz;
        return dx * dx + dy * dy + dz * dz;
    }

    public static boolean isValidPathId(String pathId) {
        if (pathId == null || pathId.isEmpty() || pathId.length() > 32) return false;
        return pathId.matches("[a-z0-9_-]+");
    }

    public static String suggestPathId(PreferredPathwayData.PathCatalog catalog) {
        for (int i = 1; i <= PreferredPathwayData.MAX_PATHS_PER_DIMENSION; i++) {
            String id = "path_" + i;
            if (!catalog.hasPath(id)) return id;
        }
        return "path_1";
    }
}
