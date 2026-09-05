package com.neoalive.tacz_sewv.entity.ai.support;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleWeapons;

/**
 * Helicopter rappel geometry and descent helpers (Stages 3–6).
 *
 * <p>Wire render (client) and passenger lerp (server) share {@link #localFaceX} /
 * {@link #ropeTopWorld} / {@link #groundY} so the rope and the trooper use the same column.
 * Descent is teleport-lerp — position written each tick, no gravity/velocity/fall damage.
 * Sequencing (settle → descend → teardown) lives on {@code DriveHelicopterGoal}.
 */
public final class RappelSupport {

    /** Blocks of Y dropped per driver AI tick while sliding the rope. */
    public static final double DESCENT_STEP = 0.85;
    /** Ticks to hold a stable hover before the first trooper steps onto a rope. */
    public static final int SETTLE_TICKS = 30;

    private RappelSupport() {}

    /** Block-aligned local |X| of a side face — same {@code Math.round(bbWidth/2)} the wire uses. */
    public static double localFaceX(VehicleEntity hull) {
        return Math.round(hull.getBbWidth() * 0.5);
    }

    /** Local Y of the rope attachment (mid hitbox) — wire top / descent start. */
    public static double localAttachY(VehicleEntity hull) {
        return hull.getBbHeight() * 0.5;
    }

    /**
     * World-space top of a rappel rope on local X+ ({@code plusX}) or X−.
     * Yaw-only (hover is level); matches {@code VehicleRenderer.vehicleAxis} at pitch/roll ≈ 0.
     */
    public static Vec3 ropeTopWorld(VehicleEntity hull, boolean plusX) {
        double face = localFaceX(hull);
        double lx = plusX ? face : -face;
        double yawRad = Math.toRadians(-hull.getYRot());
        double cos = Math.cos(yawRad);
        double sin = Math.sin(yawRad);
        return new Vec3(
                hull.getX() + lx * cos,
                hull.getY() + localAttachY(hull),
                hull.getZ() + lx * sin);
    }

    /** Standing Y on the column under {@code (x,z)} — same heightmap the heli cruise uses. */
    public static double groundY(Level level, double x, double z) {
        return level.getHeight(Heightmap.Types.WORLD_SURFACE, (int) Math.floor(x), (int) Math.floor(z));
    }

    /**
     * Weaponless cargo only: passenger whose seat has no vehicle weapons.
     * Pilot (seat 0) and armed gunners fail {@link VehicleWeapons#controlsVehicleWeapon}.
     * Used by AI/TDT rappel and paratroop — not gated by {@link SewvConfig#PLAYER_CREW_RAPPEL_ALL_SEATS}.
     */
    public static boolean isRappelEligible(VehicleEntity hull, Entity passenger) {
        if (!(passenger instanceof AbstractUnit unit) || passenger instanceof Player) return false;
        if (passenger.getVehicle() != hull) return false;
        return !VehicleWeapons.controlsVehicleWeapon(unit);
    }

    /** True if any currently mounted passenger would step onto a rope. */
    public static boolean hasEligiblePassenger(VehicleEntity hull) {
        for (Entity passenger : hull.getPassengers()) {
            if (isRappelEligible(hull, passenger)) return true;
        }
        return false;
    }

    /**
     * Player-driver crew-rappel eligibility. When {@link SewvConfig#PLAYER_CREW_RAPPEL_ALL_SEATS}
     * is off, same as {@link #isRappelEligible}; when on, any non-player {@link AbstractUnit}.
     */
    public static boolean isCrewRappelEligible(VehicleEntity hull, Entity passenger) {
        if (!(passenger instanceof AbstractUnit unit) || passenger instanceof Player) return false;
        if (passenger.getVehicle() != hull) return false;
        if (SewvConfig.PLAYER_CREW_RAPPEL_ALL_SEATS.get()) return true;
        return !VehicleWeapons.controlsVehicleWeapon(unit);
    }

    /** True if any mounted passenger would drop under the player-driver crew-rappel keybind. */
    public static boolean hasCrewRappelEligible(VehicleEntity hull) {
        for (Entity passenger : hull.getPassengers()) {
            if (isCrewRappelEligible(hull, passenger)) return true;
        }
        return false;
    }

    /**
     * One tick of a committed rope slide: pin XZ, drop Y by {@link #DESCENT_STEP}, snap to ground.
     *
     * @return {@code true} if still descending; {@code false} when landed or lost
     */
    public static boolean tickDescent(LivingEntity entity, double anchorX, double anchorZ) {
        if (!entity.isAlive()) return false;
        if (entity.isPassenger()) {
            entity.stopRiding(); // IFV dismount endpoint — frees rifle / AtWeaponGoal
        }
        entity.setDeltaMovement(Vec3.ZERO);
        entity.fallDistance = 0.0F;

        double ground = groundY(entity.level(), anchorX, anchorZ);
        double nextY = entity.getY() - DESCENT_STEP;
        if (nextY <= ground) {
            entity.setPos(anchorX, ground, anchorZ);
            entity.setDeltaMovement(Vec3.ZERO);
            entity.fallDistance = 0.0F;
            entity.setOnGround(true);
            return false;
        }
        entity.setPos(anchorX, nextY, anchorZ);
        return true;
    }

    /** Unit overload — same slide as {@link #tickDescent(LivingEntity, double, double)}. */
    public static boolean tickDescent(AbstractUnit unit, double anchorX, double anchorZ) {
        return tickDescent((LivingEntity) unit, anchorX, anchorZ);
    }

    /** Headless geometry checks — run from {@code selfCheckHeli}. */
    public static void selfCheck() {
        assert DESCENT_STEP > 0.0 && DESCENT_STEP < 20.0;
        assert SETTLE_TICKS >= 20 && SETTLE_TICKS <= 40;
        // round(bb/2) for typical hull widths
        assert Math.round(2.0 * 0.5) == 1L;
        assert Math.round(3.0 * 0.5) == 2L;
        assert Math.round(4.0 * 0.5) == 2L;
    }
}
