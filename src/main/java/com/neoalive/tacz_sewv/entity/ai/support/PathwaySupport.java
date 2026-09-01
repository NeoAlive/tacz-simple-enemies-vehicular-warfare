package com.neoalive.tacz_sewv.entity.ai.support;

import java.util.ArrayList;
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
import com.neoalive.tacz_sewv.bridge.IVehiclePatrol;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.map.PreferredPathwayData;

/**
 * Preferred pathway leg logic and passive proximity matching.
 */
public final class PathwaySupport {

    /** 16-block corridor (blocks² = 256), horizontal — matches road-adjacent MOVE joins. */
    public static final double PROXIMITY_RADIUS_SQ = 256.0;
    /** Wider band when the MOVE destination is on the path but the unit is offset beside it. */
    public static final double MOVE_CORRIDOR_RADIUS_SQ = 576.0; // 24 blocks
    /** Infantry arrival — same band as sweep wander. */
    public static final double ARRIVE_SQ = 2.5 * 2.5;
    /** Per-leg timeout — same as sweep/cruise step timeout. */
    public static final long STEP_TIMEOUT = 60 * 20L;
    /** After a manual funnel finishes, passive matching stays off this long (game ticks). */
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
        if (held.size() != live.size()) {
            int step = Math.min(pathway.sewv$getPathwayStep(), live.size() - 1);
            pathway.sewv$setPathway(live, step, sourceId, pathway.sewv$isPathwayPassive());
            return true;
        }
        for (int i = 0; i < held.size(); i++) {
            if (!held.get(i).equals(live.get(i))) {
                int step = Math.min(pathway.sewv$getPathwayStep(), live.size() - 1);
                pathway.sewv$setPathway(live, step, sourceId, pathway.sewv$isPathwayPassive());
                return true;
            }
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
                pathway.sewv$clearPathway();
                return null;
            }
            pathway.sewv$setPathwayStep(step);
            pathway.sewv$setPathwayStepDeadline(now + STEP_TIMEOUT);
            leg = route.get(step);
        }
        return leg;
    }

    public static boolean canPassiveTrigger(PmcUnitEntity pmc) {
        if (pmc.getVehicle() != null) return false;
        if (pmc instanceof IPmcDowned d && d.sewv$isDowned()) return false;
        OrderType order = pmc.getOrder();
        if (order == OrderType.FREE_FIRE) {
            if (pmc.getTarget() != null && pmc.getTarget().isAlive()) return false;
        } else if (order == OrderType.MOVE_TO_POSITION) {
            if (!FollowLeash.enRouteToMove(pmc)) return false;
        } else {
            return false;
        }
        if (((IPathwayInfantry) pmc).sewv$hasPathway()) return false;
        if (((IPathwayInfantry) pmc).sewv$isPathwayPassiveBlocked()) return false;
        if (((ISweepInfantry) pmc).sewv$hasInfantrySweep()) return false;
        if (((IVehiclePatrol) pmc).sewv$getPatrolOrigin() != null) return false;
        return true;
    }

    /**
     * Horizontal movement intent: toward a MOVE target when ordered, else physical velocity
     * (smoothed delta movement from the passive goal).
     */
    public static Vec3 movementDirection(PmcUnitEntity pmc, Vec3 smoothedVelocity) {
        if (pmc.getOrder() == OrderType.MOVE_TO_POSITION) {
            Vec3 dest = pmc.getMoveToTarget();
            if (dest != null && !dest.equals(Vec3.ZERO)) {
                double dx = dest.x - pmc.getX();
                double dz = dest.z - pmc.getZ();
                return new Vec3(dx, 0, dz);
            }
            return Vec3.ZERO;
        }
        return new Vec3(smoothedVelocity.x, 0, smoothedVelocity.z);
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

        if (nearestSegmentIndex(route, pmc.getX(), pmc.getZ(), MOVE_CORRIDOR_RADIUS_SQ) < 0) return true;

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

    /**
     * Passive funnel: near a saved path and moving along it (or MOVE destination lies on the path).
     * Returns the step index to join at, or -1.
     */
    public static int matchPassive(PmcUnitEntity pmc, List<BlockPos> waypoints, Vec3 direction) {
        if (waypoints.size() < 2) return -1;
        double ex = pmc.getX();
        double ez = pmc.getZ();

        if (pmc.getOrder() == OrderType.MOVE_TO_POSITION) {
            Vec3 dest = pmc.getMoveToTarget();
            if (dest != null && !dest.equals(Vec3.ZERO) && isNearPath(dest.x, dest.z, waypoints)) {
                double unitDist = distanceToNearestSegmentSq(ex, ez, waypoints);
                if (unitDist <= MOVE_CORRIDOR_RADIUS_SQ) {
                    return joinStepToward(waypoints, ex, ez, dest.x, dest.z);
                }
            }
        }

        if (direction.lengthSqr() < MIN_SPEED * MIN_SPEED) return -1;
        if (!isNearPath(ex, ez, waypoints)) return -1;
        int seg = nearestParallelSegmentStep(waypoints, ex, ez, direction);
        if (seg < 0) return -1;
        return joinStepAlongSegment(waypoints, ex, ez, seg, direction);
    }

    /** Cheap reject before segment loops — path AABB expanded by margin blocks. */
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

    static double distanceToNearestSegmentSq(double ex, double ez, List<BlockPos> waypoints) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i < waypoints.size() - 1; i++) {
            best = Math.min(best, distanceToSegmentSqHorizontal(ex, ez, waypoints.get(i), waypoints.get(i + 1)));
        }
        return best;
    }

    static int joinStepAlongSegment(List<BlockPos> route, double ex, double ez, int seg, Vec3 forward) {
        double t = segmentProjectionT(route, seg, ex, ez);
        BlockPos a = route.get(seg);
        BlockPos b = route.get(seg + 1);
        double sx = b.getX() - a.getX();
        double sz = b.getZ() - a.getZ();
        double segLen = Math.sqrt(sx * sx + sz * sz);
        int step = seg;
        if (segLen > 1.0e-4 && t > 0.35) {
            double moveLen = Math.sqrt(forward.x * forward.x + forward.z * forward.z);
            if (moveLen > MIN_SPEED) {
                double dot = (forward.x * sx + forward.z * sz) / (moveLen * segLen);
                if (dot > 0) step = seg + 1;
            }
        }
        return Math.min(step, route.size() - 1);
    }

    static int joinStepToward(List<BlockPos> route, double ex, double ez, double destX, double destZ) {
        int destNode = nearestNodeIndex(route, destX, destZ);
        int seg = nearestSegmentIndex(route, ex, ez);
        int step = seg >= 0 ? joinStepAlongSegment(route, ex, ez, seg, new Vec3(destX - ex, 0, destZ - ez))
                : 0;
        if (step < destNode) {
            step = Math.min(destNode, route.size() - 1);
        }
        return Math.max(0, Math.min(step, route.size() - 1));
    }

    static double segmentProjectionT(List<BlockPos> route, int seg, double ex, double ez) {
        BlockPos a = route.get(seg);
        BlockPos b = route.get(seg + 1);
        double ax = a.getX() + 0.5;
        double az = a.getZ() + 0.5;
        double bx = b.getX() + 0.5;
        double bz = b.getZ() + 0.5;
        double abx = bx - ax;
        double abz = bz - az;
        double abLenSq = abx * abx + abz * abz;
        if (abLenSq < 1.0e-8) return 0.0;
        double t = ((ex - ax) * abx + (ez - az) * abz) / abLenSq;
        return Math.max(0.0, Math.min(1.0, t));
    }

    static int nearestNodeIndex(List<BlockPos> route, double ex, double ez) {
        int best = 0;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < route.size(); i++) {
            BlockPos wp = route.get(i);
            double dx = ex - (wp.getX() + 0.5);
            double dz = ez - (wp.getZ() + 0.5);
            double dist = dx * dx + dz * dz;
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        return best;
    }

    static boolean isNearPath(double ex, double ez, List<BlockPos> waypoints) {
        for (BlockPos wp : waypoints) {
            double dx = ex - (wp.getX() + 0.5);
            double dz = ez - (wp.getZ() + 0.5);
            if (dx * dx + dz * dz <= PROXIMITY_RADIUS_SQ) {
                return true;
            }
        }
        for (int i = 0; i < waypoints.size() - 1; i++) {
            if (distanceToSegmentSqHorizontal(ex, ez, waypoints.get(i), waypoints.get(i + 1))
                    <= PROXIMITY_RADIUS_SQ) {
                return true;
            }
        }
        return false;
    }

    /** Closest segment within proximity, or -1. */
    static int nearestSegmentIndex(List<BlockPos> waypoints, double ex, double ez) {
        return nearestSegmentIndex(waypoints, ex, ez, PROXIMITY_RADIUS_SQ);
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

    static boolean isParallelToSegment(List<BlockPos> waypoints, int segmentIndex, Vec3 direction) {
        if (segmentIndex < 0 || segmentIndex >= waypoints.size() - 1) return false;
        BlockPos a = waypoints.get(segmentIndex);
        BlockPos b = waypoints.get(segmentIndex + 1);
        double sx = b.getX() - a.getX();
        double sz = b.getZ() - a.getZ();
        double segLen = Math.sqrt(sx * sx + sz * sz);
        if (segLen < 1.0e-4) return false;
        double moveLen = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        if (moveLen < MIN_SPEED) return false;
        double dot = (direction.x * sx + direction.z * sz) / (moveLen * segLen);
        return dot >= PARALLEL_DOT;
    }

    private static int nearestParallelSegmentStep(List<BlockPos> waypoints, double ex, double ez,
                                                  Vec3 direction) {
        int bestStep = -1;
        double bestDistSq = PROXIMITY_RADIUS_SQ + 1.0;
        double moveLen = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        if (moveLen < MIN_SPEED) return -1;

        for (int i = 0; i < waypoints.size() - 1; i++) {
            BlockPos a = waypoints.get(i);
            BlockPos b = waypoints.get(i + 1);
            double distSq = distanceToSegmentSqHorizontal(ex, ez, a, b);
            if (distSq > PROXIMITY_RADIUS_SQ) continue;

            double sx = b.getX() - a.getX();
            double sz = b.getZ() - a.getZ();
            double segLen = Math.sqrt(sx * sx + sz * sz);
            if (segLen < 1.0e-4) continue;

            double dot = (direction.x * sx + direction.z * sz) / (moveLen * segLen);
            if (dot >= PARALLEL_DOT && distSq < bestDistSq) {
                bestDistSq = distSq;
                bestStep = i;
            }
        }
        return bestStep;
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
