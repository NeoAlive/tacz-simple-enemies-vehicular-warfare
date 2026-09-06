package com.neoalive.tacz_sewv.debug;

import java.util.List;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.entity.ai.utility.TacticalPosture;

/**
 * Op-only {@code /sewv debug seekCover} — arms SEEK_COVER on the looked-at / nearest ground crew
 * and forces a retreat toward cover (or a rear displace in open ground).
 */
public final class SeekCoverDebug {

    private static final double LOOK_RANGE = 64.0;
    /** How long the manual arm lasts (game ticks) — covers SEEK_COVER_DURATION + margin. */
    private static final long ARM_DURATION = 400L;

    private SeekCoverDebug() {}

    public static int force(CommandSourceStack source) {
        DriverHull dh = resolveDriver(source);
        if (dh == null) {
            source.sendFailure(Component.translatable("command.tacz_sewv.debug.seekCover.none"));
            return 0;
        }
        long now = dh.unit.level().getGameTime();
        long until = now + ARM_DURATION;
        TacticalPosture.debugArmSeekCover(dh.unit.getId(), until);
        // Nudge smoke-fresh memory so natural gates also look hot in logs if conf is low.
        // (Manual bypasses those gates; this only helps TEMP eval lines read coherently.)
        SewvDiag.seekCoverTemp(
                "CMD seekCover unit={}#{} hull={}#{} until={} pos={},{},{}",
                dh.unit.getClass().getSimpleName(), dh.unit.getId(),
                dh.hull.getName().getString(), dh.hull.getId(),
                until,
                String.format("%.1f", dh.hull.getX()),
                String.format("%.1f", dh.hull.getY()),
                String.format("%.1f", dh.hull.getZ()));
        source.sendSuccess(() -> Component.translatable(
                "command.tacz_sewv.debug.seekCover.ok",
                dh.unit.getClass().getSimpleName(),
                dh.unit.getId(),
                dh.hull.getName().getString(),
                dh.hull.getId()), true);
        return 1;
    }

    private record DriverHull(AbstractUnit unit, VehicleEntity hull) {}

    @Nullable
    private static DriverHull resolveDriver(CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer player) {
            DriverHull looked = findLookedGroundDriver(player);
            if (looked != null) return looked;
        }
        List<VehicleEntity> nearby = source.getLevel().getEntitiesOfClass(
                VehicleEntity.class, new AABB(anchor(source)).inflate(LOOK_RANGE), Entity::isAlive);
        DriverHull best = null;
        double bestDist = Double.MAX_VALUE;
        BlockPos near = anchor(source);
        for (VehicleEntity hull : nearby) {
            if (!isGroundHull(hull)) continue;
            DriverHull dh = driverOf(hull);
            if (dh == null) continue;
            double d = hull.distanceToSqr(near.getX() + 0.5, near.getY(), near.getZ() + 0.5);
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
                player, eye, end, sweep, SeekCoverDebug::isGroundHull, LOOK_RANGE * LOOK_RANGE);
        if (hit != null && hit.getEntity() instanceof VehicleEntity hull) {
            return driverOf(hull);
        }
        return null;
    }

    private static boolean isGroundHull(Entity entity) {
        if (!(entity instanceof VehicleEntity hull) || !hull.isAlive() || hull.isWreck()) return false;
        var type = HullFacts.engineType(hull);
        return type == com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType.WHEEL
                || type == com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType.TRACK;
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
