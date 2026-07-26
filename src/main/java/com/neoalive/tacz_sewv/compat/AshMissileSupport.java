package com.neoalive.tacz_sewv.compat;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.bridge.FireMission;
import com.neoalive.tacz_sewv.bridge.IMortarCrew;
import com.neoalive.tacz_sewv.entity.ai.HullFacts;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Softcompat for ASH coordinate missile systems (Sapsan). Reflects into ashvehicle types so
 * an install without ASH never classloads them.
 *
 * <p>Sapsan has empty {@code Weapons}; fire is {@code togglePod} → wait for pod raise →
 * {@code shootMissileTo(Player, Vec3)}. The Player argument is unused in ASH's implementation,
 * so AI passes {@code null}.
 */
public final class AshMissileSupport {

    public static final String MODID = "ashvehicle";

    /** Pod angle at which ASH treats the launcher as raised enough to fire (~90°). */
    private static final float POD_READY_DEG = 85.0F;

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    @Nullable private static Class<?> sapsanClass;
    @Nullable private static MethodHandle togglePod;
    @Nullable private static MethodHandle getPodToggled;
    @Nullable private static MethodHandle getPodRot;
    @Nullable private static MethodHandle shootMissileTo;
    private static boolean resolved;
    private static boolean available;

    private AshMissileSupport() {}

    public static boolean present() {
        return ModList.get().isLoaded(MODID);
    }

    /** True when this unit is the driver of a MissileSystem hull and ASH is loaded. */
    public static boolean isCrewing(AbstractUnit unit) {
        if (!present()) return false;
        if (!(unit.getVehicle() instanceof VehicleEntity v)) return false;
        if (v.getFirstPassenger() != unit) return false;
        return HullFacts.isMissileSystemHull(v) && isSapsan(v);
    }

    /**
     * Engage when crewing a missile system with a live target or a standing fire mission.
     * Used by {@link com.neoalive.tacz_sewv.entity.ai.DriveVehicleGoal} to yield MOVE.
     */
    public static boolean shouldEngage(AbstractUnit unit) {
        if (!isCrewing(unit)) return false;
        LivingEntity target = unit.getTarget();
        if (target != null && target.isAlive()) return true;
        return fireMissionOf(unit) != null;
    }

    @Nullable
    public static FireMission fireMissionOf(AbstractUnit unit) {
        if (!(unit instanceof IMortarCrew crew)) return null;
        FireMission mission = crew.sewv$getFireMission();
        if (mission == null) return null;
        if (mission.isExpired(unit.level().getGameTime())) {
            crew.sewv$setFireMission(null);
            return null;
        }
        return mission;
    }

    @Nullable
    public static Vec3 aimpoint(AbstractUnit unit) {
        LivingEntity target = unit.getTarget();
        if (target != null && target.isAlive()) {
            return target.position();
        }
        FireMission mission = fireMissionOf(unit);
        return mission == null ? null : Vec3.atCenterOf(mission.pos());
    }

    public static boolean isPodRaised(VehicleEntity hull) {
        resolve();
        if (!available || !isSapsan(hull)) return false;
        try {
            boolean toggled = (boolean) getPodToggled.invoke(hull);
            float rot = (float) getPodRot.invoke(hull);
            return toggled && rot >= POD_READY_DEG;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isPodToggled(VehicleEntity hull) {
        resolve();
        if (!available || !isSapsan(hull)) return false;
        try {
            return (boolean) getPodToggled.invoke(hull);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Raise the pod if it is not already commanded up. */
    public static void arm(VehicleEntity hull) {
        resolve();
        if (!available || !isSapsan(hull)) return;
        try {
            if (!(boolean) getPodToggled.invoke(hull)) {
                togglePod.invoke(hull);
            }
        } catch (Throwable ignored) {}
    }

    /** Lower the pod when standing down. */
    public static void disarm(VehicleEntity hull) {
        resolve();
        if (!available || !isSapsan(hull)) return;
        try {
            if ((boolean) getPodToggled.invoke(hull)) {
                togglePod.invoke(hull);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Fire at {@code targetPos}. No-op if the pod is not raised. Player arg is null — ASH
     * never reads it.
     */
    public static boolean fire(VehicleEntity hull, Vec3 targetPos) {
        resolve();
        if (!available || !isSapsan(hull) || !isPodRaised(hull)) return false;
        try {
            shootMissileTo.invoke(hull, null, targetPos);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Stop all drive inputs so the launcher parks while arming/firing. */
    public static void stopMovement(VehicleEntity hull) {
        hull.setForwardInputDown(false);
        hull.setBackInputDown(false);
        hull.setLeftInputDown(false);
        hull.setRightInputDown(false);
    }

    private static boolean isSapsan(VehicleEntity hull) {
        resolve();
        return available && sapsanClass != null && sapsanClass.isInstance(hull);
    }

    private static void resolve() {
        if (resolved) return;
        resolved = true;
        if (!present()) return;
        try {
            sapsanClass = Class.forName("Aru.Aru.ashvehicle.entity.vehicle.SapsanEntity");
            togglePod = LOOKUP.findVirtual(sapsanClass, "togglePod", MethodType.methodType(void.class));
            getPodToggled = LOOKUP.findVirtual(sapsanClass, "getPodToggled", MethodType.methodType(boolean.class));
            getPodRot = LOOKUP.findVirtual(sapsanClass, "getPodRot", MethodType.methodType(float.class));
            Class<?> player = Class.forName("net.minecraft.world.entity.player.Player");
            shootMissileTo = LOOKUP.findVirtual(sapsanClass, "shootMissileTo",
                    MethodType.methodType(void.class, player, Vec3.class));
            available = true;
        } catch (Throwable t) {
            available = false;
            sapsanClass = null;
            togglePod = null;
            getPodToggled = null;
            getPodRot = null;
            shootMissileTo = null;
        }
    }
}
