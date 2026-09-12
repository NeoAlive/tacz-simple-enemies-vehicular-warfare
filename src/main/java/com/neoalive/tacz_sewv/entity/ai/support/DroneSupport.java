package com.neoalive.tacz_sewv.entity.ai.support;

import java.util.ArrayList;
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
 * Flight/lock logic lives in {@link com.neoalive.tacz_sewv.entity.ai.goal.DroneOperatorGoal}.
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
            // Live entity under the claim UUID but wrong type / dead — drop stale claim and fall through.
            if (entity != null && (!(entity instanceof DroneEntity) || !entity.isAlive())) {
                DroneControl.clearDroneClaim(owner);
            }
            // entity == null: unloaded OR already removed. Fall through to soft cache / AABB; if
            // nothing resolves, keep the claim so hasUnloadedClaim still blocks a second spawn.
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
        if (ids.isEmpty()) {
            OWNED_DRONE_IDS.remove(ownerId);
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
        drone.setCurrentItem(shell.copyWithCount(1));
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

    /** Drop the soft network-id cache for one engineer (leave / logout). */
    public static void forgetOwner(UUID ownerId) {
        OWNED_DRONE_IDS.remove(ownerId);
    }

    /** Server-stop eviction of every engineer soft cache row. */
    public static void clearOwnedCache() {
        OWNED_DRONE_IDS.clear();
    }

    /**
     * Engineer died: unlock without parking, crash-dive every owned hull, drop the claim so a
     * respawn/replacement is not blocked by a dead UUID treated as "unloaded".
     */
    public static void onOperatorKilled(AbstractUnit owner) {
        if (!DroneControl.isEngineer(owner)) return;
        if (!(owner.level() instanceof ServerLevel level)) return;

        List<DroneEntity> owned = findOwnedDrones(level, owner);
        DroneControl.setLocked(owner, false);
        for (DroneEntity drone : owned) {
            DroneControl.crashDive(drone);
        }
        DroneControl.clearDroneClaim(owner);
        forgetOwner(owner.getUUID());
    }

    /**
     * AI drone permanently removed (killed/discarded). Clears the engineer's claim when it still
     * points at this hull — {@link #findOwnedDrones} otherwise treats a missing UUID as unload
     * and blocks redeploy forever.
     */
    public static void onAiDroneRemoved(DroneEntity drone, Entity.RemovalReason reason) {
        if (!DroneControl.isAiOwned(drone)) return;
        if (reason != Entity.RemovalReason.KILLED && reason != Entity.RemovalReason.DISCARDED) return;
        if (!(drone.level() instanceof ServerLevel level)) return;

        UUID ownerId = readOwner(drone);
        if (ownerId == null) return;
        forgetOwner(ownerId);

        Entity owner = level.getEntity(ownerId);
        if (!(owner instanceof AbstractUnit unit)) return;
        UUID claimed = DroneControl.readDroneClaim(unit);
        if (claimed != null && claimed.equals(drone.getUUID())) {
            DroneControl.clearDroneClaim(unit);
        }
        if (DroneControl.isLocked(unit) && findOwnedDrones(level, unit).isEmpty()) {
            DroneControl.setLocked(unit, false);
        }
    }

    @Nullable
    public static UUID readOwner(DroneEntity drone) {
        CompoundTag tag = drone.getPersistentData();
        return tag.hasUUID(DroneControl.OWNER_TAG) ? tag.getUUID(DroneControl.OWNER_TAG) : null;
    }

    /**
     * Nearest hostile SEM unit for a kamikaze dive. {@link AbstractUnit}-only (RU/US/PMC) — no
     * monster LivingEntity fill. Prefers riders of real hulls over on-foot troops (armor doctrine).
     * Cheap enough for many drones: one class filter, sticky caller cadence, flat-ish AABB.
     */
    @Nullable
    public static LivingEntity findDiveTarget(DroneEntity drone, AbstractUnit owner) {
        double radius = SewvConfig.DRONE_TARGET_RADIUS.get();
        double halfH = Math.max(SewvConfig.DRONE_SCAN_ALTITUDE.get() + 24.0, 48.0);
        int surface = drone.level().getHeight(Heightmap.Types.WORLD_SURFACE, drone.getBlockX(), drone.getBlockZ());
        double slack = Math.max(0.0, drone.getY() - surface);
        AABB bounds = new AABB(
                drone.getX() - radius, drone.getY() - halfH - slack, drone.getZ() - radius,
                drone.getX() + radius, drone.getY() + halfH, drone.getZ() + radius);

        LivingEntity bestArmor = null;
        LivingEntity bestSoft = null;
        double bestArmorDist = Double.MAX_VALUE;
        double bestSoftDist = Double.MAX_VALUE;

        for (AbstractUnit candidate : drone.level().getEntitiesOfClass(AbstractUnit.class, bounds,
                u -> isValidDiveTarget(owner, u))) {
            double d = candidate.distanceToSqr(drone);
            if (candidate.getVehicle() instanceof VehicleEntity hull
                    && hull.isAlive() && !hull.isWreck() && hasRealEngine(hull)) {
                if (d < bestArmorDist) {
                    bestArmorDist = d;
                    bestArmor = candidate;
                }
            } else if (!candidate.isPassenger()) {
                if (d < bestSoftDist) {
                    bestSoftDist = d;
                    bestSoft = candidate;
                }
            }
        }
        return bestArmor != null ? bestArmor : bestSoft;
    }

    /** Hostile SEM unit the drone may dive on (no monsters, no friendlies, not the operator). */
    public static boolean isValidDiveTarget(AbstractUnit owner, LivingEntity target) {
        if (target == null || !target.isAlive() || !target.isAttackable()) return false;
        if (!(target instanceof AbstractUnit)) return false;
        if (target == owner || target.getUUID().equals(owner.getUUID())) return false;
        return !VehicleTargeting.isNonHostile(owner, target);
    }

    /**
     * AI kamikaze warhead. SBW's {@code kamikazeExplosion} returns immediately when
     * {@code CONTROLLER} is not a player ("undefined" for AI), so mortar_shell damage/radius
     * never ran — only the hull's empty destroy puff.
     */
    public static void detonateWarhead(DroneEntity drone) {
        if (drone.level().isClientSide()) return;
        String itemId = DroneEntity.getItemId(drone.getCurrentItem());
        if (itemId == null || itemId.isEmpty()) itemId = MORTAR_SHELL_ID;
        DroneAttachmentData data = CustomData.DRONE_ATTACHMENT.get(itemId);
        if (data == null) data = CustomData.DRONE_ATTACHMENT.get(MORTAR_SHELL_ID);
        if (data == null || data.explosionDamage <= 0.0f || data.explosionRadius <= 0.0f) return;

        Entity attacker = crewOf(drone);
        if (attacker == null) {
            UUID ownerId = readOwner(drone);
            if (ownerId != null && drone.level() instanceof ServerLevel level) {
                Entity entity = level.getEntity(ownerId);
                if (entity != null) attacker = entity;
            }
        }
        if (attacker == null) attacker = drone;

        drone.createCustomExplosion()
                .source(drone)
                .attacker(attacker)
                .damage(data.explosionDamage)
                .radius(data.explosionRadius)
                .explode();
    }

    /**
     * Nearest hostile-crewed vehicle with a real {@link EngineType} (not {@code EMPTY}).
     * @deprecated use {@link #findDiveTarget} — kept for any external callers.
     */
    @Nullable
    @Deprecated
    public static VehicleEntity findHostileVehicle(DroneEntity drone, AbstractUnit owner) {
        LivingEntity dive = findDiveTarget(drone, owner);
        if (dive != null && dive.getVehicle() instanceof VehicleEntity hull) return hull;
        return null;
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
     * {@link com.neoalive.tacz_sewv.entity.ai.goal.DriveVehicleGoal} delegate; kamikaze AI no longer broadcasts.
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
