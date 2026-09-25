package com.neoalive.tacz_sewv.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.debug.PathingPerf;

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

    /** Pods move slowly and a keep-out is 48 blocks; a 1 s old position list is indistinguishable. */
    private static final int POD_REFRESH_TICKS = 20;

    /** {@link #AVOID_IDS} resolved to types once; compared by identity, not by registry key string. */
    @Nullable private static Set<EntityType<?>> avoidTypes;

    private static final class PodList {
        long refreshedAt = Long.MIN_VALUE;
        List<LivingEntity> pods = List.of();
    }

    /** Per level; weak so an unloaded dimension's list goes with it. Server thread only. */
    private static final Map<Level, PodList> PODS = new WeakHashMap<>();

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
        LivingEntity best = null;
        double bestD = Double.POSITIVE_INFINITY;
        for (LivingEntity living : pods(level)) {
            if (!living.isAlive()) continue;
            double d = living.distanceToSqr(from);
            if (d < bestD && d <= r2) {
                bestD = d;
                best = living;
            }
        }
        return best;
    }

    /**
     * The level's pods, refreshed on a game-time deadline instead of one box query per hull per destination
     * update (two per call before). One pass over the level's entities with an identity test per entity.
     */
    private static List<LivingEntity> pods(Level level) {
        if (!(level instanceof ServerLevel server)) return List.of();
        long now = server.getGameTime();
        PodList list = PODS.computeIfAbsent(level, l -> new PodList());
        if (list.refreshedAt != Long.MIN_VALUE && now >= list.refreshedAt
                && now - list.refreshedAt < POD_REFRESH_TICKS) {
            return list.pods;
        }
        long t0 = System.nanoTime();
        Set<EntityType<?>> types = avoidTypes();
        List<LivingEntity> found = new ArrayList<>();
        if (!types.isEmpty()) {
            for (LivingEntity e : server.getEntities(EntityTypeTest.forClass(LivingEntity.class),
                    e -> types.contains(e.getType()))) {
                found.add(e);
            }
        }
        list.pods = found;
        list.refreshedAt = now;
        PathingPerf.podQueryNanos += System.nanoTime() - t0;
        PathingPerf.podRefreshes++;
        return found;
    }

    private static Set<EntityType<?>> avoidTypes() {
        Set<EntityType<?>> types = avoidTypes;
        if (types != null) return types;
        Set<EntityType<?>> resolved = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (String id : AVOID_IDS) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl != null && ForgeRegistries.ENTITY_TYPES.containsKey(rl)) {
                resolved.add(ForgeRegistries.ENTITY_TYPES.getValue(rl));
            }
        }
        avoidTypes = resolved;
        return resolved;
    }
}
