package com.neoalive.tacz_sewv.invasion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.jetbrains.annotations.Nullable;

import com.neoalive.tacz_sewv.block.CapturePointBlockEntity;
import com.neoalive.tacz_sewv.block.TeamBaseBlockEntity;
import com.neoalive.tacz_sewv.bridge.ICaptureOrder;
import com.neoalive.tacz_sewv.debug.SewvDiag;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.invasion.force.ForceKind;

/**
 * Invasion CAPTURE_POINT pipeline for event-spawned crews: AI fleets ({@link InvasionTags#AI})
 * and PMC units tagged {@link InvasionTags#SPAWN} (player-base PMC crew / PMC-faction AI).
 * Destination ahead of idle/wander/SEM chase; combat holds course via {@link #holdsCourseThroughContact}.
 */
public final class CaptureOrderSupport {

    private CaptureOrderSupport() {}

    /**
     * Hulls the pipeline steers: ground and fixed mounts (which capture) and aircraft (which fly
     * the objective as their area of operations but never count toward capture — see
     * {@link CaptureSupport}). Ships stay out: nothing in invasion fields them.
     */
    public static boolean isEligibleHull(VehicleEntity vehicle) {
        EngineType type = HullFacts.engineType(vehicle);
        return type == EngineType.WHEEL || type == EngineType.TRACK || type == EngineType.FIXED
                || type == EngineType.HELICOPTER || type == EngineType.AIRCRAFT;
    }

    /** The {@link ForceKind} a unit or hull was fielded as; untagged (pre-rework) reads as ground. */
    public static ForceKind kindOf(Entity entity) {
        var data = entity.getPersistentData();
        return data.contains(InvasionTags.KIND) ? ForceKind.fromOrdinal(data.getByte(InvasionTags.KIND)) : ForceKind.GROUND;
    }

    /**
     * AI fleet of any faction, or ownerless invasion-spawned PMC. Player-commandable PMC
     * ({@link PmcOwnerSupport#isPlayerCommandable}) stay off the capture pipeline so orders work.
     */
    public static boolean isCaptureCrew(AbstractUnit unit) {
        if (!unit.getPersistentData().getBoolean(InvasionTags.SPAWN)) return false;
        if (PmcOwnerSupport.isPlayerCommandable(unit)) return false;
        if (unit.getPersistentData().getBoolean(InvasionTags.AI)) return true;
        // Infantry delivered by an AI helicopter: no longer fleet (no AI tag), still on the order.
        if (kindOf(unit) == ForceKind.INFANTRY) return true;
        return unit instanceof PmcUnitEntity;
    }

    /**
     * Ground crews keep driving for the point through contact — that is how a point gets taken.
     * Aircraft do not: they capture nothing, so breaking off to fight is the whole of their job.
     */
    public static boolean holdsCourseThroughContact(AbstractUnit unit) {
        return unit instanceof ICaptureOrder order
                && order.sewv$hasCaptureOrder()
                && isCaptureCrew(unit)
                && !kindOf(unit).isAircraft();
    }

    /**
     * Assign the order to every live invasion capture crew. Seat-0 drivers use it;
     * other seats are harmless — they never call {@link com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting#resolveDestination}.
     */
    public static void beginAll(ServerLevel level) {
        int n = 0;
        for (Entity entity : level.getAllEntities()) {
            if (!(entity instanceof AbstractUnit unit)) continue;
            if (!isCaptureCrew(unit)) continue;
            if (!(unit instanceof ICaptureOrder order)) continue;
            order.sewv$beginCaptureOrder();
            pickObjective(level, unit, order);
            n++;
        }
        SewvDiag.invasion("captureOrder beginAll n={}", n);
    }

    public static void beginUnit(AbstractUnit unit) {
        if (!(unit instanceof ICaptureOrder order)) return;
        if (!isCaptureCrew(unit)) return;
        order.sewv$beginCaptureOrder();
        if (unit.level() instanceof ServerLevel level) {
            pickObjective(level, unit, order);
        }
    }

    public static void clearAll(ServerLevel level) {
        int n = 0;
        for (Entity entity : level.getAllEntities()) {
            if (!(entity instanceof ICaptureOrder order)) continue;
            if (!order.sewv$hasCaptureOrder()) continue;
            order.sewv$clearCaptureOrder();
            n++;
        }
        SewvDiag.invasion("captureOrder clearAll n={}", n);
    }

    /**
     * Where an invasion capture driver should go, or null when inactive / ineligible.
     */
    @Nullable
    public static BlockPos currentDestination(AbstractUnit unit, VehicleEntity vehicle) {
        if (!(unit instanceof ICaptureOrder order) || !order.sewv$hasCaptureOrder()) return null;
        if (!isCaptureCrew(unit)) return null;
        if (!(unit.level() instanceof ServerLevel level)) return null;
        if (!InvasionSession.isActive(level)) return null;
        if (!isEligibleHull(vehicle)) return null;

        advanceIfOwned(level, unit, order);
        BlockPos target = order.sewv$getCaptureTarget();
        if (target == null) {
            pickObjective(level, unit, order);
            target = order.sewv$getCaptureTarget();
        }
        return target;
    }

    /**
     * Where an on-foot invasion rifleman should walk, or null when it has no live order. A
     * capturing rifleman runs the same pipeline as a hull; a contest-only one
     * ({@link InvasionTags#CAPTURES} false) is re-pointed by {@link #pickDefenseObjective} whenever
     * {@code repick} is set — the caller throttles that, since what needs defending changes.
     */
    @Nullable
    public static BlockPos infantryDestination(AbstractUnit unit, boolean repick) {
        if (!(unit instanceof ICaptureOrder order) || !order.sewv$hasCaptureOrder()) return null;
        if (!isCaptureCrew(unit)) return null;
        if (!(unit.level() instanceof ServerLevel level)) return null;
        if (!InvasionSession.isActive(level)) return null;

        boolean captures = unit.getPersistentData().getBoolean(InvasionTags.CAPTURES);
        if (!captures) {
            if (repick || order.sewv$getCaptureTarget() == null) pickDefenseObjective(level, unit, order);
            return order.sewv$getCaptureTarget();
        }
        advanceIfOwned(level, unit, order);
        BlockPos target = order.sewv$getCaptureTarget();
        if (target == null) {
            pickObjective(level, unit, order);
            target = order.sewv$getCaptureTarget();
        }
        return target;
    }

    /** On foot, fielded (or dismounted) as infantry, with a live capture order. */
    public static boolean isInvasionInfantry(AbstractUnit unit) {
        return !unit.isPassenger()
                && kindOf(unit) == ForceKind.INFANTRY
                && unit instanceof ICaptureOrder order
                && order.sewv$hasCaptureOrder()
                && isCaptureCrew(unit);
    }

    /**
     * Invasion infantry standing inside its objective zone. {@code FollowLeash} reads this so SEM's
     * long chase cannot walk a defender off the point it was sent to hold.
     */
    public static boolean onStation(AbstractUnit unit) {
        if (!unit.getPersistentData().getBoolean(InvasionTags.SPAWN)) return false;
        if (!isInvasionInfantry(unit)) return false;
        BlockPos target = ((ICaptureOrder) unit).sewv$getCaptureTarget();
        if (target == null) return false;
        int r = zoneRadius(unit.level(), target);
        double dx = unit.getX() - (target.getX() + 0.5);
        double dz = unit.getZ() - (target.getZ() + 0.5);
        return dx * dx + dz * dz <= (double) r * r;
    }

    /** Capture radius of the zone at {@code pos}; a sensible default while its chunk is unloaded. */
    public static int zoneRadius(net.minecraft.world.level.Level level, BlockPos pos) {
        if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof CapturableBlockEntity zone) {
            return Math.max(1, zone.getRadiusInBlocks());
        }
        return 8;
    }

    /**
     * The point {@code team} is currently pushing for from {@code origin} (its base): nearest
     * point it does not hold, else nearest enemy player base it does not hold, else null. What
     * {@link #pickObjective} hands a fresh crew — exposed so spawns can face the fight.
     */
    @Nullable
    public static BlockPos frontObjective(ServerLevel level, String team, BlockPos origin) {
        Objective o = objectiveFor(level, team, origin);
        return o == null ? null : o.pos();
    }

    private record Objective(BlockPos pos, boolean base) {}

    /**
     * If the current objective is already held by this crew's invasion team, advance the pipeline.
     */
    public static void advanceIfOwned(ServerLevel level, AbstractUnit unit, ICaptureOrder order) {
        String team = unit.getPersistentData().getString(InvasionTags.TEAM);
        if (team.isEmpty()) return;

        int kind = order.sewv$getCaptureKind();
        BlockPos target = order.sewv$getCaptureTarget();
        if (kind == ICaptureOrder.KIND_NONE || target == null) return;

        CapturableBlockEntity zone = zoneAt(level, target);
        if (zone == null) {
            // Chunk unloaded or block gone — re-pick rather than sit forever.
            SewvDiag.invasion("captureOrder missingZone unit={} pos={} — re-pick",
                    unit.getId(), target);
            pickObjective(level, unit, order);
            return;
        }

        String holder = CaptureSupport.holdingTeam(zone);
        if (!team.equals(holder)) return;

        SewvDiag.invasion("captureOrder advance unit={} team={} wasKind={} pos={}",
                unit.getId(), team, kind, target);
        pickObjective(level, unit, order);
    }

    private static void pickObjective(ServerLevel level, AbstractUnit unit, ICaptureOrder order) {
        String team = unit.getPersistentData().getString(InvasionTags.TEAM);
        BlockPos home = homeBase(level, team);
        Objective o = objectiveFor(level, team, home != null ? home : unit.blockPosition());
        if (o == null) {
            order.sewv$setCaptureDone();
            SewvDiag.invasion("captureOrder done team={} unit={} holding={}",
                    team, unit.getId(), order.sewv$getCaptureTarget());
        } else if (o.base()) {
            order.sewv$setCaptureBase(o.pos());
            SewvDiag.invasion("captureOrder objective BASE pos={} team={}", o.pos(), team);
        } else {
            order.sewv$setCapturePoint(o.pos());
            SewvDiag.invasion("captureOrder objective POINT pos={} team={}", o.pos(), team);
        }
    }

    @Nullable
    private static Objective objectiveFor(ServerLevel level, String team, BlockPos origin) {
        List<CapturePointBlockEntity> points = new ArrayList<>();
        List<TeamBaseBlockEntity> enemyBases = new ArrayList<>();
        for (CapturableBlockEntity zone : InvasionSpawn.findLoadedCapturables(level)) {
            if (zone instanceof CapturePointBlockEntity point) {
                points.add(point);
            } else if (zone instanceof TeamBaseBlockEntity base
                    && base.isPlayerOwned()
                    && !team.isEmpty()
                    && !team.equals(base.getAssignedTeam())) {
                enemyBases.add(base);
            }
        }

        // Vicinity: closest unheld capture_point to this team's own base first.
        points.sort(Comparator.comparingDouble(p -> p.getBlockPos().distSqr(origin)));
        for (CapturePointBlockEntity point : points) {
            if (!team.equals(CaptureSupport.holdingTeam(point))) return new Objective(point.getBlockPos(), false);
        }
        for (TeamBaseBlockEntity base : enemyBases) {
            if (!team.equals(CaptureSupport.holdingTeam(base))) return new Objective(base.getBlockPos(), true);
        }
        return null;
    }

    /**
     * Contest-only infantry: they cannot take a point, so they go where holding one matters.
     * In order — an own point under threat (contested, or an enemy has progress on it), nearest
     * first; else the point the team is pushing for, so they stand on the ground being fought
     * over and freeze the enemy there; else the nearest own point; else home.
     */
    private static void pickDefenseObjective(ServerLevel level, AbstractUnit unit, ICaptureOrder order) {
        String team = unit.getPersistentData().getString(InvasionTags.TEAM);
        BlockPos here = unit.blockPosition();
        BlockPos threatened = null;
        BlockPos ownNearest = null;
        for (CapturableBlockEntity zone : InvasionSpawn.findLoadedCapturables(level)) {
            if (!(zone instanceof CapturePointBlockEntity point)) continue;
            if (!team.equals(CaptureSupport.holdingTeam(point))) continue;
            BlockPos pos = point.getBlockPos();
            boolean underThreat = point.isContested()
                    || (point.getProgress() > 0f && !team.equals(point.getAdvancingTeam()));
            if (underThreat && (threatened == null || pos.distSqr(here) < threatened.distSqr(here))) {
                threatened = pos;
            }
            if (ownNearest == null || pos.distSqr(here) < ownNearest.distSqr(here)) ownNearest = pos;
        }
        BlockPos home = homeBase(level, team);
        BlockPos target = threatened;
        if (target == null) target = frontObjective(level, team, home != null ? home : here);
        if (target == null) target = ownNearest;
        if (target == null) target = home;
        if (target == null) {
            order.sewv$setCaptureDone();
            return;
        }
        if (!target.equals(order.sewv$getCaptureTarget())) {
            order.sewv$setCapturePoint(target);
            SewvDiag.invasion("defendOrder unit={} team={} pos={} threatened={}",
                    unit.getId(), team, target, threatened != null);
        }
    }

    @Nullable
    private static BlockPos homeBase(ServerLevel level, String team) {
        if (team.isEmpty()) return null;
        for (CapturableBlockEntity zone : InvasionSpawn.findLoadedCapturables(level)) {
            if (zone instanceof TeamBaseBlockEntity base && team.equals(base.getAssignedTeam())) {
                return base.getBlockPos();
            }
        }
        return null;
    }

    @Nullable
    private static CapturableBlockEntity zoneAt(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) return null;
        var be = level.getBlockEntity(pos);
        return be instanceof CapturableBlockEntity capturable ? capturable : null;
    }
}
