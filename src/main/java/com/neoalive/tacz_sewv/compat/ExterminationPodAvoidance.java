package com.neoalive.tacz_sewv.compat;

import java.util.List;
import java.util.Set;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import com.neoalive.tacz_sewv.config.SewvConfig;

/**
 * When {@code /gamerule sewvInvasionOverrides} is on (Extermination present), AI vehicles keep
 * clear of Extermination combat pods instead of driving under them.
 */
public final class ExterminationPodAvoidance {

    private static final Set<String> AVOID_IDS = Set.of(
            "extermination:tripod",
            "extermination:uberpod",
            "extermination:emperorpod",
            "extermination:tripod_harvester");

    private ExterminationPodAvoidance() {}

    public static boolean active(Level level) {
        return ExterminationCompat.invasionOverrides(level);
    }

    /** Ground / ship destinations — push off any keep-out sphere the hull or dest sits in. */
    public static BlockPos adjust(VehicleEntity vehicle, BlockPos dest) {
        if (vehicle == null || dest == null) return dest;
        Level level = vehicle.level();
        if (!active(level)) return dest;

        double radius = SewvConfig.INVASION_POD_AVOID_RADIUS.get();
        Vec3 probe = Vec3.atCenterOf(dest);
        LivingEntity pod = nearestPod(level, vehicle.position(), radius);
        if (pod == null) {
            pod = nearestPod(level, probe, radius);
            if (pod == null) return dest;
        }

        Vec3 rim = rimPoint(vehicle, pod, radius);
        return BlockPos.containing(rim.x, dest.getY(), rim.z);
    }

    /**
     * Helicopter / free steer — same keep-out, preserving the caller's desired altitude via
     * returning only horizontal XZ (caller keeps Y).
     */
    public static Vec3 adjustHorizontal(VehicleEntity vehicle, double steerX, double steerZ) {
        if (vehicle == null) return new Vec3(steerX, 0.0, steerZ);
        Level level = vehicle.level();
        if (!active(level)) return new Vec3(steerX, 0.0, steerZ);

        double radius = SewvConfig.INVASION_POD_AVOID_RADIUS.get();
        Vec3 dest = new Vec3(steerX, vehicle.getY(), steerZ);
        LivingEntity pod = nearestPod(level, vehicle.position(), radius);
        if (pod == null) {
            pod = nearestPod(level, dest, radius);
            if (pod == null) return new Vec3(steerX, 0.0, steerZ);
        }

        Vec3 rim = rimPoint(vehicle, pod, radius);
        return new Vec3(rim.x, 0.0, rim.z);
    }

    /** The point {@code radius} blocks from the pod, directly away from the hull. */
    private static Vec3 rimPoint(VehicleEntity vehicle, LivingEntity pod, double radius) {
        Vec3 away = vehicle.position().subtract(pod.position());
        if (away.horizontalDistanceSqr() < 1.0e-4) {
            away = new Vec3(1.0, 0.0, 0.0);
        } else {
            away = new Vec3(away.x, 0.0, away.z).normalize();
        }
        return pod.position().add(away.scale(radius));
    }

    @Nullable
    private static LivingEntity nearestPod(Level level, Vec3 from, double radius) {
        if (!ExterminationCompat.available()) return null;
        double r2 = radius * radius;
        AABB box = new AABB(from, from).inflate(radius);
        List<Entity> found = level.getEntities((Entity) null, box, ExterminationPodAvoidance::isAvoidPod);
        LivingEntity best = null;
        double bestD = Double.POSITIVE_INFINITY;
        for (Entity e : found) {
            if (!(e instanceof LivingEntity living) || !living.isAlive()) continue;
            double d = living.distanceToSqr(from);
            if (d < bestD && d <= r2) {
                bestD = d;
                best = living;
            }
        }
        return best;
    }

    private static boolean isAvoidPod(Entity entity) {
        ResourceLocation key = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        return key != null && AVOID_IDS.contains(key.toString());
    }
}
