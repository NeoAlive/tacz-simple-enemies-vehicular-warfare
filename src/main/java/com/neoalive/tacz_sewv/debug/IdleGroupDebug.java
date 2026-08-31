package com.neoalive.tacz_sewv.debug;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.entity.ai.command.Assignment;
import com.neoalive.tacz_sewv.entity.ai.command.CrewAssignment;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.entity.ai.support.IdleGroupSupport;
import com.neoalive.tacz_sewv.spawn.SupportSpawner;
import com.neoalive.tacz_sewv.spawn.TankSpawner;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;
import com.neoalive.tacz_sewv.util.VehicleDrops;

/**
 * Op-only {@code /sewv debug idle*} helpers for exercising hybrid IDLE_HOLD / IDLE_TRAVEL
 * without waiting on hold timers or manual clustering.
 */
public final class IdleGroupDebug {

    private static final double LOOK_RANGE = 64.0;

    private IdleGroupDebug() {}

    /** Driver + hull the command targets (crosshair, else nearest crewed ground hull). */
    public record DriverHull(AbstractUnit unit, VehicleEntity hull) {}

    public static int status(CommandSourceStack source, double radius) {
        ServerLevel level = source.getLevel();
        BlockPos near = anchor(source);
        List<DriverHull> drivers = driversInRadius(level, near, radius);
        if (drivers.isEmpty()) {
            source.sendFailure(Component.translatable("command.tacz_sewv.debug.idle.none"));
            return 0;
        }
        drivers.sort(Comparator.comparingInt(d -> d.unit.getId()));
        for (DriverHull dh : drivers) {
            IdleGroupSupport.Snapshot snap = IdleGroupSupport.scan(dh.unit, dh.hull);
            source.sendSuccess(() -> Component.literal(IdleGroupSupport.describeStatus(dh.unit, dh.hull, snap)),
                    false);
        }
        return 1;
    }

    public static int forceHold(CommandSourceStack source) {
        DriverHull dh = resolveDriver(source);
        if (dh == null) {
            source.sendFailure(Component.translatable("command.tacz_sewv.debug.idle.none"));
            return 0;
        }
        IdleGroupSupport.Snapshot snap = IdleGroupSupport.scan(dh.unit, dh.hull);
        applyHoldGroup(snap);
        source.sendSuccess(() -> Component.translatable(
                "command.tacz_sewv.debug.idle.hold_ok", snap.size), true);
        return 1;
    }

    public static int forceTravel(CommandSourceStack source, @Nullable Float bearingDeg) {
        DriverHull dh = resolveDriver(source);
        if (dh == null) {
            source.sendFailure(Component.translatable("command.tacz_sewv.debug.idle.none"));
            return 0;
        }
        IdleGroupSupport.Snapshot snap = IdleGroupSupport.scan(dh.unit, dh.hull);
        if (bearingDeg != null) {
            IdleGroupSupport.debugSetBearing(snap.members.get(0).hull,
                    (float) Math.toRadians(bearingDeg));
        }
        applyTravelGroup(snap);
        source.sendSuccess(() -> Component.translatable(
                "command.tacz_sewv.debug.idle.travel_ok", snap.size), true);
        return 1;
    }

    /** Sets hold-until to now on every member so IDLE_TRAVEL becomes feasible immediately. */
    public static int expireHold(CommandSourceStack source) {
        DriverHull dh = resolveDriver(source);
        if (dh == null) {
            source.sendFailure(Component.translatable("command.tacz_sewv.debug.idle.none"));
            return 0;
        }
        IdleGroupSupport.Snapshot snap = IdleGroupSupport.scan(dh.unit, dh.hull);
        int n = 0;
        for (IdleGroupSupport.Member m : snap.members) {
            IdleGroupSupport.debugExpireHold(m.hull);
            n++;
        }
        int count = n;
        source.sendSuccess(() -> Component.translatable(
                "command.tacz_sewv.debug.idle.expire_ok", count), true);
        return 1;
    }

    public static int clear(CommandSourceStack source, double radius) {
        ServerLevel level = source.getLevel();
        BlockPos near = anchor(source);
        List<DriverHull> drivers = driversInRadius(level, near, radius);
        if (drivers.isEmpty()) {
            source.sendFailure(Component.translatable("command.tacz_sewv.debug.idle.none"));
            return 0;
        }
        for (DriverHull dh : drivers) {
            IdleGroupSupport.clear(dh.hull);
            IdleGroupSupport.setDebugDrive(dh.hull, false);
            CrewAssignment.clear(dh.unit.getId());
        }
        int n = drivers.size();
        source.sendSuccess(() -> Component.translatable(
                "command.tacz_sewv.debug.idle.clear_ok", n), true);
        return 1;
    }

    /**
     * Spawns {@code count} crewed ground hulls in a tight line, then forces IDLE_HOLD on the cluster.
     */
    public static int spawnCluster(CommandSourceStack source, int count, TankFaction faction) {
        if (count < 1 || count > 12) {
            source.sendFailure(Component.translatable("command.tacz_sewv.debug.idle.cluster_range"));
            return 0;
        }
        ServerLevel level = source.getLevel();
        BlockPos anchor = TankSpawner.adjustHeight(level, anchor(source));
        UUID owner = faction == TankFaction.PMC && source.getEntity() instanceof ServerPlayer player
                ? player.getUUID() : null;
        int spacing = 14;
        List<DriverHull> spawned = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int offset = (i - count / 2) * spacing;
            BlockPos pos = anchor.offset(offset, 0, 0);
            VehicleEntity hull = SupportSpawner.withoutCompanions(
                    () -> TankSpawner.spawnTankWithCrew(level, pos, faction, owner));
            if (hull == null) continue;
            VehicleDrops.markCrewAndHull(hull);
            if (hull.getFirstPassenger() instanceof AbstractUnit unit) {
                unit.setTarget(null);
                spawned.add(new DriverHull(unit, hull));
            }
        }
        if (spawned.isEmpty()) {
            source.sendFailure(Component.translatable("command.tacz_sewv.debug.idle.cluster_fail"));
            return 0;
        }
        spawned.sort(Comparator.comparingInt(d -> d.unit.getId()));
        IdleGroupSupport.Snapshot snap = IdleGroupSupport.scan(spawned.get(0).unit, spawned.get(0).hull);
        applyHoldGroup(snap);
        int n = spawned.size();
        source.sendSuccess(() -> Component.translatable(
                "command.tacz_sewv.debug.idle.cluster_ok", n, faction.name()), true);
        return 1;
    }

    private static void applyHoldGroup(IdleGroupSupport.Snapshot snap) {
        double cx = snap.centerX;
        double cz = snap.centerZ;
        for (IdleGroupSupport.Member m : snap.members) {
            IdleGroupSupport.enterHold(m.unit, m.hull, snap);
            IdleGroupSupport.setDebugDrive(m.hull, true);
            CrewAssignment.publish(new Assignment(
                    m.unitId, Assignment.Role.IDLE_HOLD, null, null, cx, cz));
        }
    }

    private static void applyTravelGroup(IdleGroupSupport.Snapshot snap) {
        double cx = snap.centerX;
        double cz = snap.centerZ;
        for (IdleGroupSupport.Member m : snap.members) {
            IdleGroupSupport.enterTravel(m.unit, m.hull, snap);
            IdleGroupSupport.setDebugDrive(m.hull, true);
            CrewAssignment.publish(new Assignment(
                    m.unitId, Assignment.Role.IDLE_TRAVEL, null, null, cx, cz));
        }
    }

    @Nullable
    private static DriverHull resolveDriver(CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer player) {
            DriverHull looked = findLookedGroundDriver(player);
            if (looked != null) return looked;
        }
        List<DriverHull> nearby = driversInRadius(source.getLevel(), anchor(source), LOOK_RANGE);
        if (nearby.isEmpty()) return null;
        BlockPos near = anchor(source);
        DriverHull best = null;
        double bestDist = Double.MAX_VALUE;
        for (DriverHull dh : nearby) {
            double d = dh.hull.distanceToSqr(near.getX() + 0.5, near.getY(), near.getZ() + 0.5);
            if (d < bestDist) {
                bestDist = d;
                best = dh;
            }
        }
        return best;
    }

    @Nullable
    private static DriverHull findLookedGroundDriver(ServerPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(LOOK_RANGE));
        AABB sweep = player.getBoundingBox().expandTowards(end.subtract(eye)).inflate(1.0);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(
                player, eye, end, sweep, IdleGroupDebug::isDebugGroundHull, LOOK_RANGE * LOOK_RANGE);
        if (hit != null && hit.getEntity() instanceof VehicleEntity hull) {
            return driverOf(hull);
        }
        return null;
    }

    private static List<DriverHull> driversInRadius(ServerLevel level, BlockPos near, double radius) {
        List<DriverHull> out = new ArrayList<>();
        AABB box = new AABB(near).inflate(radius);
        for (VehicleEntity hull : level.getEntitiesOfClass(VehicleEntity.class, box, Entity::isAlive)) {
            if (!isDebugGroundHull(hull)) continue;
            DriverHull dh = driverOf(hull);
            if (dh != null) out.add(dh);
        }
        return out;
    }

    private static boolean isDebugGroundHull(Entity entity) {
        if (!(entity instanceof VehicleEntity hull) || !hull.isAlive() || hull.isWreck()) return false;
        return HullFacts.engineType(hull) == com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType.WHEEL
                || HullFacts.engineType(hull) == com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType.TRACK;
    }

    @Nullable
    private static DriverHull driverOf(VehicleEntity hull) {
        if (!(hull.getFirstPassenger() instanceof AbstractUnit unit) || !unit.isAlive()) return null;
        return new DriverHull(unit, hull);
    }

    private static BlockPos anchor(CommandSourceStack source) {
        return source.getEntity() != null
                ? source.getEntity().blockPosition()
                : BlockPos.containing(source.getPosition());
    }
}
