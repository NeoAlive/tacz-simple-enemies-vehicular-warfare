package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.entity.vehicle.DroneEntity;
import com.neoalive.tacz_sewv.config.SewvConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Deployment, ownership and target-relay mechanics for RU/US engineer recon drones
 * (SuperbWarfare's {@link DroneEntity}). Flying an individual drone is
 * {@link DroneOperatorGoal}'s job; this class is the stateless plumbing it calls into.
 *
 * <p>A drone is spawned with no attachment (never fired, never armed) and no player
 * CONTROLLER — SBW's own control path (a held Monitor item's mouse/keyboard NBT) is simply
 * never fed, so nothing in {@code DroneEntity} moves it on its own. {@link DroneOperatorGoal}
 * drives its generic {@code VehicleEntity} input flags directly instead, the same surface
 * {@link DriveHelicopterGoal} already puppets for helicopters — only yaw/pitch update is
 * gated behind that (absent) controller, so the goal sets those directly too.
 *
 * <p>Ownership is a persistent-NBT tag on the DRONE itself (its deploying engineer's UUID),
 * not state kept on the engineer — the same self-healing shape {@code IMortarCrew} uses for
 * a mortar claim: nothing has to notice the engineer died or the drone despawned, a re-count
 * from the world just naturally excludes it, and the cap survives the engineer's own goal
 * instance being rebuilt on a chunk reload (a network id would not).
 */
final class DroneSupport {

    private static final String OWNER_TAG = "sewv_drone_owner";
    private static final ResourceLocation DRONE_ID = new ResourceLocation("superbwarfare", "drone");

    private DroneSupport() {}

    /**
     * Every currently-alive drone tagged as belonging to {@code owner}, found by scanning near
     * it. Reuses the broadcast radius as the search box rather than adding a config entry just
     * for this — a drone escorts within a few blocks of its owner, so it's generous on purpose.
     */
    static List<DroneEntity> findOwnedDrones(ServerLevel level, AbstractUnit owner) {
        double radius = SewvConfig.DRONE_BROADCAST_RADIUS.get();
        AABB box = AABB.ofSize(owner.position(), radius * 2, radius * 2, radius * 2);
        UUID ownerId = owner.getUUID();
        return level.getEntitiesOfClass(DroneEntity.class, box,
                d -> d.isAlive() && ownerId.equals(readOwner(d)));
    }

    /** Spawns one unarmed, AI-flown drone beside {@code owner} and tags it as belonging to them. */
    static DroneEntity spawnDrone(ServerLevel level, AbstractUnit owner) {
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(DRONE_ID);
        if (type == null) return null;
        Entity entity = type.create(level);
        if (!(entity instanceof DroneEntity drone)) return null;

        drone.setPos(owner.getX(), owner.getY() + 1.0, owner.getZ());
        drone.setYRot(owner.getYRot());
        drone.getPersistentData().putUUID(OWNER_TAG, owner.getUUID());
        level.addFreshEntity(drone);
        return drone;
    }

    private static UUID readOwner(DroneEntity drone) {
        CompoundTag tag = drone.getPersistentData();
        return tag.hasUUID(OWNER_TAG) ? tag.getUUID(OWNER_TAG) : null;
    }

    /**
     * Nearest hostile within range of the drone, honouring the same proactive-acquisition
     * doctrine (faction + SEM's per-faction friendly flag) as every other scan goal in this mod.
     *
     * <p>Deliberately NO line-of-sight check. A drone spotting something only makes a
     * receiving unit/vehicle AWARE of it (a target to point at); it does not fire on its own
     * say-so. Ground vehicles already gate actually SHOOTING on their own LOS at the moment
     * they pull the trigger ({@code MixinVehicleFireCooldown}'s line-of-fire check, independent
     * of how the target was acquired), so a raycast here would only pay for a filter the fire
     * path already enforces downstream — and it would be wrong for the drone's own aerial
     * vantage anyway, which sees over most of what would block it.
     */
    static LivingEntity findVisibleEnemy(DroneEntity drone, AbstractUnit owner, double radius) {
        AABB box = AABB.ofSize(drone.position(), radius * 2, radius * 2, radius * 2);
        double radiusSq = radius * radius;
        List<LivingEntity> candidates = drone.level().getEntitiesOfClass(LivingEntity.class, box, e ->
                e.isAlive() && e.isAttackable() && !VehicleTargeting.isNonHostile(owner, e)
                        && e.distanceToSqr(drone) <= radiusSq);
        candidates.sort(Comparator.comparingDouble(drone::distanceToSqr));
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    /**
     * Hands {@code target} directly to every same-faction unit within range that has none of
     * its own — RU/US have no order queue to route it through (unlike the PMC radio relay), so
     * this is a straight {@code setTarget} fan-out. Never overwrites an existing lock, so a
     * broadcast can't yank a unit off whatever it is already fighting.
     */
    static void broadcastTarget(ServerLevel level, AbstractUnit owner, LivingEntity target, Vec3 from, double radius) {
        AABB box = AABB.ofSize(from, radius * 2, radius * 2, radius * 2);
        double radiusSq = radius * radius;
        for (AbstractUnit candidate : level.getEntitiesOfClass(AbstractUnit.class, box, u ->
                u.isAlive() && u != owner && u.getTarget() == null
                        && VehicleTargeting.isSameFaction(owner, u)
                        && u.position().distanceToSqr(from) <= radiusSq)) {
            candidate.setTarget(target);
        }
    }
}
