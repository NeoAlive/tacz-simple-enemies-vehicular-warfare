package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.entity.vehicle.DroneEntity;
import com.neoalive.tacz_sewv.entity.unit.RuEngineerEntity;
import com.neoalive.tacz_sewv.entity.unit.UsEngineerEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.AnimationState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Synched lock flag + dive-arm / ownership helpers for engineer kamikaze drones.
 * Lock freezes the engineer (see {@link DroneControlLockGoal}); dive-arm gates
 * {@code kamikazeExplosion} so wander terrain bumps do not detonate.
 */
public final class DroneControl {

    public static final String OWNER_TAG = "sewv_drone_owner";
    public static final String ENGINEER_DRONE_ID = "sewv_drone_id";
    public static final String DIVE_ARMED_TAG = "sewv_dive_armed";
    public static final String SPAWN_GRACE_UNTIL = "sewv_drone_spawn_grace";
    public static final String STASH_PRESENT = "sewv_drone_hand_stash";
    public static final String STASH_MAIN = "sewv_drone_stash_main";
    public static final String STASH_OFF = "sewv_drone_stash_off";

    /** Nearby-threat AABB rescan while locked (game ticks). */
    public static final int LOCK_THREAT_RESCAN_TICKS = 10;
    public static final double LOCK_THREAT_RADIUS = 12.0;
    /** lastHurtByMob considered "recent" for per-tick unlock. */
    public static final int HURT_MEMORY_TICKS = 40;
    /** Ticks after spawn where entity-crash is ignored (clear the engineer). */
    public static final int SPAWN_GRACE_TICKS = 40;

    private DroneControl() {}

    public static boolean isLocked(Entity entity) {
        if (entity instanceof RuEngineerEntity ru) {
            return ru.getEntityData().get(RuEngineerEntity.DRONE_CONTROL_LOCKED);
        }
        if (entity instanceof UsEngineerEntity us) {
            return us.getEntityData().get(UsEngineerEntity.DRONE_CONTROL_LOCKED);
        }
        return false;
    }

    public static void setLocked(AbstractUnit unit, boolean locked) {
        if (unit instanceof RuEngineerEntity ru) {
            ru.getEntityData().set(RuEngineerEntity.DRONE_CONTROL_LOCKED, locked);
        } else if (unit instanceof UsEngineerEntity us) {
            us.getEntityData().set(UsEngineerEntity.DRONE_CONTROL_LOCKED, locked);
        }
    }

    public static boolean isEngineer(LivingEntity entity) {
        return entity instanceof RuEngineerEntity || entity instanceof UsEngineerEntity;
    }

    /** Dedicated sit {@link AnimationState}, or null when the entity is not an engineer. */
    @Nullable
    public static AnimationState sitAnimationState(Entity entity) {
        if (entity instanceof RuEngineerEntity ru) return ru.droneSitAnimationState;
        if (entity instanceof UsEngineerEntity us) return us.droneSitAnimationState;
        return null;
    }

    public static void rememberDrone(AbstractUnit owner, DroneEntity drone) {
        owner.getPersistentData().putUUID(ENGINEER_DRONE_ID, drone.getUUID());
    }

    public static void clearDroneClaim(AbstractUnit owner) {
        owner.getPersistentData().remove(ENGINEER_DRONE_ID);
    }

    @Nullable
    public static UUID readDroneClaim(AbstractUnit owner) {
        CompoundTag tag = owner.getPersistentData();
        return tag.hasUUID(ENGINEER_DRONE_ID) ? tag.getUUID(ENGINEER_DRONE_ID) : null;
    }

    public static boolean hasDroneClaim(AbstractUnit owner) {
        return owner.getPersistentData().hasUUID(ENGINEER_DRONE_ID);
    }

    public static void setDiveArmed(DroneEntity drone, boolean armed) {
        if (armed) {
            drone.getPersistentData().putBoolean(DIVE_ARMED_TAG, true);
        } else {
            drone.getPersistentData().remove(DIVE_ARMED_TAG);
        }
    }

    public static boolean isDiveArmed(DroneEntity drone) {
        return drone.getPersistentData().getBoolean(DIVE_ARMED_TAG);
    }

    public static boolean isAiOwned(DroneEntity drone) {
        return drone.getPersistentData().hasUUID(OWNER_TAG);
    }

    @Nullable
    public static UUID readOwnerId(DroneEntity drone) {
        CompoundTag tag = drone.getPersistentData();
        return tag.hasUUID(OWNER_TAG) ? tag.getUUID(OWNER_TAG) : null;
    }

    public static boolean inSpawnGrace(DroneEntity drone) {
        long until = drone.getPersistentData().getLong(SPAWN_GRACE_UNTIL);
        return until > 0L && drone.level().getGameTime() < until;
    }

    public static void zeroInputs(DroneEntity drone) {
        drone.setForwardInputDown(false);
        drone.setBackInputDown(false);
        drone.setLeftInputDown(false);
        drone.setRightInputDown(false);
        drone.setUpInputDown(false);
        drone.setDownInputDown(false);
    }
}
