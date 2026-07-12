package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.data.vehicle.subdata.SeatInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;

/**
 * Shared weapon doctrine for AI crews, ground ({@link DriveVehicleGoal}) and
 * flight ({@link DriveHelicopterGoal}): classify the target, then pick the seat's
 * weapon slot for it. Slot conventions follow SBW's data files — 0 = cannon,
 * 1 = MG, 2 = special (TOW / heavy anti-vehicle ordnance). The switch cooldown
 * stays in each goal (per-crew state); this utility is stateless.
 */
public final class VehicleWeapons {

    public static final int WEAPON_CANNON = 0;
    public static final int WEAPON_MG = 1;
    public static final int WEAPON_SPECIAL = 2; // TOW / heavy anti-vehicle ordnance
    private static final int WEAPON_COUNT = 3;

    public enum TargetCategory { VEHICLE, MONSTER, PMC_UNIT }

    private VehicleWeapons() {}

    /**
     * True when {@code mob} is crewing an SBW vehicle in a role that fires a VEHICLE
     * weapon — the driver (seat 0, who works the hull's main armament) or any gunner
     * whose seat has weapons assigned. A pure passenger seat (no weapons) returns
     * false, so those units are excluded and keep their own behaviour.
     *
     * <p>Used to suppress a crew member's hand-held gun goal: without this a mounted
     * unit with a rifle in hand and a target in range fires the rifle instead of (or
     * alongside) the vehicle's guns.
     */
    public static boolean controlsVehicleWeapon(Mob mob) {
        if (!(mob.getVehicle() instanceof VehicleEntity vehicle)) return false;
        if (vehicle.getFirstPassenger() == mob) return true; // driver — always busy driving
        SeatInfo seat = vehicle.getSeat(mob);
        return seat != null && !seat.weapons().isEmpty();     // gunner; passenger → false
    }

    public static TargetCategory classifyTarget(LivingEntity target) {
        // A VehicleEntity isn't a LivingEntity, so it can never be the target itself
        // the AI targets the crew riding inside; armor makes the MG useless against them.
        if (target.getVehicle() instanceof VehicleEntity) return TargetCategory.VEHICLE;
        if (target instanceof RUunitEntity || target instanceof USunitEntity) return TargetCategory.PMC_UNIT;
        return TargetCategory.MONSTER; // vanilla hostiles + fallback default
    }

    // Weighted pick: each category excludes the weapon(s) that can't/shouldn't
    // engage it, then prefers one of what's left based on range.
    public static void selectWeaponForTarget(VehicleEntity vehicle, int seatIndex,
                                             TargetCategory category, boolean tooFar) {
        if (seatIndex < 0) return;
        double[] weight = new double[WEAPON_COUNT];

        switch (category) {
            case VEHICLE:
                weight[WEAPON_MG] = Double.NEGATIVE_INFINITY; // small arms can't hurt armor
                weight[tooFar ? WEAPON_SPECIAL : WEAPON_CANNON] = 1.0;
                break;
            case MONSTER:
            case PMC_UNIT: // same doctrine as monsters, don't burn heavy ordnance on infantry
                weight[WEAPON_SPECIAL] = Double.NEGATIVE_INFINITY;
                weight[tooFar ? WEAPON_CANNON : WEAPON_MG] = 1.0;
                break;
        }

        // Not every vehicle has a 3rd weapon slot, setWeaponIndex() doesn't
        // bounds-check, so an invalid index silently leaves the seat unarmed.
        SeatInfo seat = vehicle.getSeat(seatIndex);
        if (seat == null || seat.weapons().size() <= WEAPON_SPECIAL) {
            weight[WEAPON_SPECIAL] = Double.NEGATIVE_INFINITY;
        }

        vehicle.setWeaponIndex(seatIndex, argmax(weight));
    }

    private static int argmax(double[] weight) {
        int best = 0;
        for (int i = 1; i < weight.length; i++) {
            if (weight[i] > weight[best]) best = i;
        }
        return best;
    }
}
