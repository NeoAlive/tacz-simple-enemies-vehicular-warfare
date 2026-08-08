package com.neoalive.tacz_sewv.entity.ai.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.data.CustomData;
import com.atsuishio.superbwarfare.data.drone_attachment.DroneAttachmentData;
import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType;
import com.atsuishio.superbwarfare.entity.vehicle.DroneEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.init.ModItems;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;

/**
 * Deployment, ownership and targeting plumbing for RU/US engineer kamikaze drones.
 * Flight/lock logic lives in {@link DroneOperatorGoal}.
 */
public final class DroneSupport {

    private static final ResourceLocation DRONE_ID = new ResourceLocation("superbwarfare", "drone");
    private static final String MORTAR_SHELL_ID = "superbwarfare:mortar_shell";
    /** Fast, reload-local lookup; persistent owner NBT + engineer claim UUID are sources of truth. */
    private static final Map<UUID, List<Integer>> OWNED_DRONE_IDS = new HashMap<>();

    private DroneSupport() {}

    /**
     * The engineer flying this drone, or null if nobody is. Search radius matches
     * {@link SewvConfig#DRONE_BROADCAST_RADIUS} (map markers / soft cache).
     */
    @Nullable
    public static AbstractUnit crewOf(DroneEntity drone) {
        UUID ownerId = readOwner(drone);
        if (ownerId == null) return null;
        double radius = SewvConfig.DRONE_BROADCAST_RADIUS.get();
        for (AbstractUnit unit : drone.level().getEntitiesOfClass(
                AbstractUnit.class, drone.getBoundingBox().inflate(radius))) {
            if (unit.isAlive() && ownerId.equals(unit.getUUID())) return unit;
        }
        return null;
    }

    /**
     * Live owned drones for {@code owner}. Prefers the engineer's stored drone UUID (level-wide),
     * then the soft network-id cache, then a nearby AABB scan. If a claim UUID exists but the
     * entity is unloaded, returns empty <em>without</em> clearing the claim — caller must not spawn.
     */
    public static List<DroneEntity> findOwnedDrones(ServerLevel level, AbstractUnit owner) {
        UUID ownerId = owner.getUUID();
        UUID claimed = DroneControl.readDroneClaim(owner);
        if (claimed != null) {
            Entity entity = level.getEntity(claimed);
            if (entity instanceof DroneEntity drone && drone.isAlive() && ownerId.equals(readOwner(drone))) {
                rememberNetworkId(ownerId, drone);
                return List.of(drone);
            }
            // Claimed but not loaded (or dead elsewhere) — do not AABB-spawn a second hull.
            if (entity == null) {
                return Collections.emptyList();
            }
            // Entity exists but is wrong/dead — drop stale claim.
            if (!(entity instanceof DroneEntity) || !entity.isAlive()) {
                DroneControl.clearDroneClaim(owner);
            }
        }

        List<Integer> ids = OWNED_DRONE_IDS.computeIfAbsent(ownerId, ignored -> new ArrayList<>());
        List<DroneEntity> resolved = new ArrayList<>(ids.size());
        boolean mismatch = ids.isEmpty();
        for (int id : ids) {
            Entity entity = level.getEntity(id);
            if (entity instanceof DroneEntity drone && drone.isAlive() && ownerId.equals(readOwner(drone))) {
                resolved.add(drone);
            } else {
                mismatch = true;
            }
        }
        if (!mismatch && !resolved.isEmpty()) {
            if (resolved.size() == 1) {
                DroneControl.rememberDrone(owner, resolved.get(0));
            }
            return resolved;
        }

        double radius = Math.max(SewvConfig.DRONE_BROADCAST_RADIUS.get(), SewvConfig.DRONE_LEASH_RADIUS.get());
        AABB box = AABB.ofSize(owner.position(), radius * 2, radius * 2, radius * 2);
        List<DroneEntity> scanned = level.getEntitiesOfClass(DroneEntity.class, box,
                d -> d.isAlive() && ownerId.equals(readOwner(d)));
        ids.clear();
        for (DroneEntity drone : scanned) {
            ids.add(drone.getId());
        }
        if (scanned.size() == 1) {
            DroneControl.rememberDrone(owner, scanned.get(0));
        }
        return scanned;
    }

    /** True when the engineer still claims a drone UUID that is simply not in the loaded world. */
    public static boolean hasUnloadedClaim(ServerLevel level, AbstractUnit owner) {
        UUID claimed = DroneControl.readDroneClaim(owner);
        if (claimed == null) return false;
        Entity entity = level.getEntity(claimed);
        return entity == null;
    }

    /** Spawns one mortar_shell-armed, AI-flown drone above {@code owner} (not overlapping them). */
    public static DroneEntity spawnDrone(ServerLevel level, AbstractUnit owner) {
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(DRONE_ID);
        if (type == null) return null;
        Entity entity = type.create(level);
        if (!(entity instanceof DroneEntity drone)) return null;

        // Y+1 spawned inside the engineer; SBW hitEntityCrash + mortar_shell instantly kills both.
        double alt = Math.max(8.0, SewvConfig.DRONE_SCAN_ALTITUDE.get());
        drone.setPos(owner.getX(), owner.getY() + alt, owner.getZ());
        drone.setYRot(owner.getYRot());
        drone.getPersistentData().putUUID(DroneControl.OWNER_TAG, owner.getUUID());
        // Ignore entity crashes briefly while it clears the spawn column.
        drone.getPersistentData().putLong(DroneControl.SPAWN_GRACE_UNTIL,
                level.getGameTime() + DroneControl.SPAWN_GRACE_TICKS);
        armMortarShell(drone);
        level.addFreshEntity(drone);
        rememberNetworkId(owner.getUUID(), drone);
        DroneControl.rememberDrone(owner, drone);
        return drone;
    }

    /** Mirrors SBW player interact mount for {@code superbwarfare:mortar_shell}. */
    public static void armMortarShell(DroneEntity drone) {
        DroneAttachmentData data = CustomData.DRONE_ATTACHMENT.get(MORTAR_SHELL_ID);
        if (data == null) return;

        ItemStack shell = new ItemStack(ModItems.MORTAR_SHELL.get());
        drone.currentItem = shell.copyWithCount(1);
        drone.getEntityData().set(DroneEntity.DISPLAY_ENTITY, data.displayEntity());
        drone.setAmmo(1);
        drone.getEntityData().set(DroneEntity.IS_KAMIKAZE, data.isKamikaze);
        drone.getEntityData().set(DroneEntity.MAX_AMMO, data.count());

        float[] scale = data.scale();
        float[] offset = data.offset();
        float[] rotation = data.rotation();
        drone.getEntityData().set(DroneEntity.DISPLAY_DATA, List.of(
                scale[0], scale[1], scale[2],
                offset[0], offset[1], offset[2],
                rotation[0], rotation[1], rotation[2],
                data.xLength, data.zLength,
                (float) data.tickCount
        ));
    }

    private static void rememberNetworkId(UUID ownerId, DroneEntity drone) {
        List<Integer> ids = OWNED_DRONE_IDS.computeIfAbsent(ownerId, ignored -> new ArrayList<>());
        int netId = drone.getId();
        if (!ids.contains(netId)) ids.add(netId);
    }

    @Nullable
    public static UUID readOwner(DroneEntity drone) {
        CompoundTag tag = drone.getPersistentData();
        return tag.hasUUID(DroneControl.OWNER_TAG) ? tag.getUUID(DroneControl.OWNER_TAG) : null;
    }

    /**
     * Nearest hostile-crewed vehicle with a real {@link EngineType} (not {@code EMPTY}).
     * Dedicated aerial AABB — does not reuse {@link HullLocalScan}'s LivingEntity fill.
     * Vertical reach includes AGL slack so a drone at cruise altitude still sees ground hulls.
     */
    @Nullable
    public static VehicleEntity findHostileVehicle(DroneEntity drone, AbstractUnit owner) {
        double radius = SewvConfig.VEHICLE_TARGET_SCAN_RADIUS.get();
        double halfH = SewvConfig.VEHICLE_TARGET_SCAN_HEIGHT.get() / 2.0;
        int surface = drone.level().getHeight(Heightmap.Types.WORLD_SURFACE, drone.getBlockX(), drone.getBlockZ());
        double slack = Math.max(0.0, drone.getY() - surface);
        AABB bounds = new AABB(
                drone.getX() - radius, drone.getY() - halfH - slack, drone.getZ() - radius,
                drone.getX() + radius, drone.getY() + halfH, drone.getZ() + radius);

        VehicleEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (VehicleEntity hull : drone.level().getEntitiesOfClass(VehicleEntity.class, bounds)) {
            if (hull == drone || !hull.isAlive() || hull.isWreck()) continue;
            if (!hasRealEngine(hull)) continue;
            if (!hasHostilePassenger(owner, hull)) continue;
            double d = hull.distanceToSqr(drone);
            if (d < bestDist) {
                bestDist = d;
                best = hull;
            }
        }
        return best;
    }

    /** {@code EngineType != EMPTY} — excludes Type:Drone hulls (default EMPTY) and bare placeholders. */
    private static boolean hasRealEngine(VehicleEntity hull) {
        try {
            return hull.computed().getEngineType() != EngineType.EMPTY;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * True when at least one passenger fails {@link VehicleTargeting#isNonHostile}.
     * Empty / same-faction-only hulls are skipped.
     */
    public static boolean hasHostilePassenger(AbstractUnit owner, VehicleEntity hull) {
        for (Entity passenger : hull.getPassengers()) {
            if (passenger instanceof LivingEntity living
                    && living.isAlive()
                    && !VehicleTargeting.isNonHostile(owner, living)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Hands {@code target} to same-faction units with no target of their own. Kept for
     * {@link DriveVehicleGoal} delegate; kamikaze AI no longer broadcasts.
     */
    public static void broadcastTarget(ServerLevel level, AbstractUnit owner, LivingEntity target, Vec3 from, double radius) {
        AABB box = AABB.ofSize(from, radius * 2, radius * 2, radius * 2);
        double radiusSq = radius * radius;
        for (AbstractUnit candidate : level.getEntitiesOfClass(AbstractUnit.class, box, u ->
                u.isAlive() && u != owner && u.getTarget() == null
                        && VehicleTargeting.isSameFaction(owner, u)
                        && VehicleTargeting.mayAssignTarget(u, target)
                        && u.position().distanceToSqr(from) <= radiusSq)) {
            candidate.setTarget(target);
        }
    }
}
