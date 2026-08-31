package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.EnumSet;

import com.atsuishio.superbwarfare.data.vehicle.subdata.SeatInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.bridge.IHelicopterPilot;
import com.neoalive.tacz_sewv.compat.FcpMortarCompat;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleWeapons;
import com.neoalive.tacz_sewv.entity.ai.support.HeliArmament;

/**
 * Gives a NON-DRIVER weapon seat the same ammo-type doctrine and guided-munition fire-assist
 * that {@link DriveVehicleGoal}/{@link DriveHelicopterGoal} already give the driver's own
 * weapon. Both drive goals gate on {@code vehicle.getFirstPassenger() == unit} — that seat is
 * also steering the hull, and a second driver fighting over the controls would be worse than
 * the gap — so on a hull where the turret is a separate seat from the driver
 * ({@code superbwarfare:fcp}'s BMPs put a weaponless driver in seat 0 and the turret in seat 1;
 * {@code bmp_2} arms all six rear seats with firing-port MGs) that gunner falls through both
 * gates entirely. SuperbWarfare's own native per-seat loop still aims and fires it, just with
 * none of this mod's ammo switching or widened fire-assist cone — exactly the problem this mod
 * exists to fix for the driver's own weapon.
 *
 * <p>Claims NO flags: the native loop already owns aiming and pulling the trigger for this
 * seat, so this only ever intervenes on weapon selection — {@link VehicleWeapons#selectWeaponForTarget}
 * / {@link HeliArmament#pickGroundWeapon} and {@link VehicleWeapons#tryAiFireAssist} are
 * seat-agnostic.
 *
 * <p>Eligibility is the inverse of rappel cargo ({@link RappelSupport#isRappelEligible}): any
 * passenger that {@link VehicleWeapons#controlsVehicleWeapon} (armed seat) who is not the
 * driver. Helicopters are included; weapon work is suspended while the pilot is in a
 * LANDING/LANDED command so a gunner salvo cannot perturb {@link DriveHelicopterGoal}'s
 * capture approach (the original regression that kept helis excluded).
 */
public class TurretGunnerGoal extends Goal {

    private static final int WEAPON_SWITCH_COOLDOWN_TICKS = 5;

    private final AbstractUnit unit;
    private final HullFacts hull = new HullFacts();
    private VehicleEntity vehicle;
    private int seatIndex = -1;

    private int weaponSwitchCooldown;
    // Cached the same way DriveVehicleGoal caches it: getWeaponIndex() can't answer "which ROLE
    // is selected", only the physical slot, so re-deriving it every tick would mean re-running
    // the whole slot classification just to learn what selection already knew.
    private int selectedRole = VehicleWeapons.UNCLASSIFIED;

    public TurretGunnerGoal(AbstractUnit unit) {
        this.unit = unit;
        this.setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        if (!(this.unit.getVehicle() instanceof VehicleEntity v)) return false;
        // Driver / pure passengers: rappel cargo fails controlsVehicleWeapon; driver is first passenger.
        if (v.getFirstPassenger() == this.unit) return false;
        if (!VehicleWeapons.controlsVehicleWeapon(this.unit)) return false;

        this.hull.attach(v);
        if (this.hull.isHelicopter() && heliLanding(v)) return false;

        int seat = v.getSeatIndex(this.unit);
        if (seat < 0) return false;
        SeatInfo info = v.getSeat(seat);
        if (info == null || info.weapons().isEmpty()) return false;

        this.vehicle = v;
        this.seatIndex = seat;
        return this.unit.getTarget() != null;
    }

    @Override
    public boolean canContinueToUse() {
        return this.vehicle != null
                && this.unit.getVehicle() == this.vehicle
                && !this.vehicle.isWreck()
                && this.unit.getTarget() != null
                && !(this.hull.isHelicopter() && heliLanding(this.vehicle));
    }

    @Override
    public void stop() {
        this.vehicle = null;
        this.seatIndex = -1;
        this.weaponSwitchCooldown = 0;
        this.selectedRole = VehicleWeapons.UNCLASSIFIED;
    }

    @Override
    public void tick() {
        LivingEntity target = this.unit.getTarget();
        if (target == null) return;
        if (this.hull.isHelicopter() && heliLanding(this.vehicle)) return;

        if (this.hull.isHelicopter()) {
            heliGunnerTick(target);
            return;
        }

        if (this.weaponSwitchCooldown > 0) {
            this.weaponSwitchCooldown--;
        } else {
            this.selectedRole = VehicleWeapons.selectWeaponForTarget(
                    this.vehicle, this.seatIndex, target, this.unit).role;
            this.weaponSwitchCooldown = WEAPON_SWITCH_COOLDOWN_TICKS;
        }

        // Same reasoning as DriveVehicleGoal.fireAssistIfSpecial: a lofted guided-munition
        // solution can't pass SBW's native 4° straight-line gate at range, so this fires it
        // directly within the wider configured cone. Cannon/MG keep firing through the native gate.
        // FCP mortar vehicles: ManVehicleMortarGoal owns the trigger — a flat cone shot from a
        // tube that has not finished laying spends shells into the dirt.
        if (this.selectedRole == VehicleWeapons.WEAPON_SPECIAL
                && !FcpMortarCompat.isMortarHull(this.vehicle)) {
            VehicleWeapons.tryAiFireAssist(this.vehicle, this.unit, target,
                    SewvConfig.AI_FIRE_ASSIST_CONE_DEG.get());
        }
    }

    /** Heli gunner seats use pilot armament doctrine; always fire-assist (hull-fixed pods). */
    private void heliGunnerTick(LivingEntity target) {
        if (this.weaponSwitchCooldown > 0) {
            this.weaponSwitchCooldown--;
        } else {
            int slot = HeliArmament.pickGroundWeapon(this.vehicle, this.seatIndex, target);
            if (slot >= 0) {
                this.vehicle.setWeaponIndex(this.seatIndex, slot);
            }
            this.weaponSwitchCooldown = WEAPON_SWITCH_COOLDOWN_TICKS;
        }
        VehicleWeapons.tryAiFireAssist(this.vehicle, this.unit, target,
                SewvConfig.AI_FIRE_ASSIST_CONE_DEG.get());
    }

    private static boolean heliLanding(VehicleEntity v) {
        if (!(v.getFirstPassenger() instanceof IHelicopterPilot pilot)) return false;
        int cmd = pilot.sewv$getHeliCommand();
        return cmd == IHelicopterPilot.HELI_CMD_LANDING || cmd == IHelicopterPilot.HELI_CMD_LANDED;
    }
}
