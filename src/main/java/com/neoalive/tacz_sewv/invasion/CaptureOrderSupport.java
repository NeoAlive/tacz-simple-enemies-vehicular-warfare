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

/**
 * Invasion CAPTURE_POINT pipeline for event-spawned crews: AI fleets ({@link InvasionTags#AI})
 * and PMC units tagged {@link InvasionTags#SPAWN} (player-base PMC crew / PMC-faction AI).
 * Destination ahead of idle/wander/SEM chase; combat holds course via {@link #holdsCourseThroughContact}.
 */
public final class CaptureOrderSupport {

    private CaptureOrderSupport() {}

    /** Ground / fixed emplacements only for v1 — same conservatism as Sweep. */
    public static boolean isEligibleHull(VehicleEntity vehicle) {
        try {
            EngineType type = vehicle.computed().getEngineType();
            return type == EngineType.WHEEL || type == EngineType.TRACK || type == EngineType.FIXED;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * AI fleet of any faction, or ownerless invasion-spawned PMC. Player-commandable PMC
     * ({@link PmcOwnerSupport#isPlayerCommandable}) stay off the capture pipeline so orders work.
     */
    public static boolean isCaptureCrew(AbstractUnit unit) {
        if (!unit.getPersistentData().getBoolean(InvasionTags.SPAWN)) return false;
        if (PmcOwnerSupport.isPlayerCommandable(unit)) return false;
        if (unit.getPersistentData().getBoolean(InvasionTags.AI)) return true;
        return unit instanceof PmcUnitEntity;
    }

    public static boolean holdsCourseThroughContact(AbstractUnit unit) {
        return unit instanceof ICaptureOrder order
                && order.sewv$hasCaptureOrder()
                && isCaptureCrew(unit);
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
        List<CapturableBlockEntity> zones = InvasionSpawn.findLoadedCapturables(level);

        List<CapturePointBlockEntity> points = new ArrayList<>();
        List<TeamBaseBlockEntity> enemyBases = new ArrayList<>();
        BlockPos homePos = null;
        for (CapturableBlockEntity zone : zones) {
            if (zone instanceof CapturePointBlockEntity point) {
                points.add(point);
            } else if (zone instanceof TeamBaseBlockEntity base) {
                if (!team.isEmpty() && team.equals(base.getAssignedTeam())) {
                    homePos = base.getBlockPos();
                } else if (base.isPlayerOwned()
                        && !team.isEmpty()
                        && !team.equals(base.getAssignedTeam())) {
                    enemyBases.add(base);
                }
            }
        }

        // Vicinity: closest unheld capture_point to this team's own base first.
        BlockPos origin = homePos != null ? homePos : unit.blockPosition();
        points.sort(Comparator.comparingDouble(p -> p.getBlockPos().distSqr(origin)));

        for (CapturePointBlockEntity point : points) {
            if (team.equals(CaptureSupport.holdingTeam(point))) continue;
            order.sewv$setCapturePoint(point.getBlockPos());
            SewvDiag.invasion("captureOrder objective POINT pos={} dist2={} team={}",
                    point.getBlockPos(), point.getBlockPos().distSqr(origin), team);
            return;
        }

        for (TeamBaseBlockEntity base : enemyBases) {
            if (team.equals(CaptureSupport.holdingTeam(base))) continue;
            order.sewv$setCaptureBase(base.getBlockPos());
            SewvDiag.invasion("captureOrder objective BASE pos={} assigned={} team={}",
                    base.getBlockPos(), base.getAssignedTeam(), team);
            return;
        }

        order.sewv$setCaptureDone();
        SewvDiag.invasion("captureOrder done team={} unit={} holding={}",
                team, unit.getId(), order.sewv$getCaptureTarget());
    }

    @Nullable
    private static CapturableBlockEntity zoneAt(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) return null;
        var be = level.getBlockEntity(pos);
        return be instanceof CapturableBlockEntity capturable ? capturable : null;
    }
}
