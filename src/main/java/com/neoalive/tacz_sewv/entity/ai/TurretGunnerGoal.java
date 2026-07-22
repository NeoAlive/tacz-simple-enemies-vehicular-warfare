package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.data.vehicle.subdata.SeatInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.config.SewvConfig;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import java.util.EnumSet;

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
 * and {@link VehicleWeapons#tryAiFireAssist} are already seat-agnostic (they act on whichever
 * {@code unit}/{@code seatIndex} is passed in, not hardcoded to the driver).
 *
 * <p><b>GROUND/SHIP HULLS ONLY — helicopters are deliberately excluded.</b> This was first
 * written to also cover helicopter gunner seats (e.g. {@code superbwarfare:mi_28}'s seat 1,
 * armed with Cannon/PassengerMissile/SeekMissile, distinct from the pilot's own weapons in seat
 * 0), on the theory that {@link VehicleWeapons} is seat-agnostic so one goal class could cover
 * both. In practice, a gunner firing mid-flight destabilized {@link DriveHelicopterGoal}'s
 * landing approach — a helicopter ordered to land would circle the pad instead of touching down.
 * {@code mi_28} is a DEFAULT pool entry ({@code ruVehiclePool}), so this was reachable out of the
 * box. Root-caused no further than "the gunner seat is the only new thing that fires mid-flight
 * here" — do not re-enable for helicopters without first understanding that interaction (likely
 * firing recoil/backblast perturbing the vehicle's velocity, which {@code doLanding}'s capture
 * phase isn't robust against).
 */
public class TurretGunnerGoal extends Goal {

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
        if (v.getFirstPassenger() == this.unit) return false; // driver's own goal handles that seat

        this.hull.attach(v);
        if (this.hull.isHelicopter()) return false; // see class doc — landing interaction regression

        int seat = v.getSeatIndex(this.unit);
        if (seat < 0) return false;
        SeatInfo info = v.getSeat(seat);
        if (info == null || info.weapons().isEmpty()) return false; // nothing to select here

        this.vehicle = v;
        this.seatIndex = seat;
        return this.unit.getTarget() != null;
    }

    @Override
    public boolean canContinueToUse() {
        return this.vehicle != null
                && this.unit.getVehicle() == this.vehicle
                && !this.vehicle.isWreck()
                && this.unit.getTarget() != null;
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

        if (this.weaponSwitchCooldown > 0) {
            this.weaponSwitchCooldown--;
        } else {
            this.selectedRole = VehicleWeapons.selectWeaponForTarget(this.vehicle, this.seatIndex, target);
            this.weaponSwitchCooldown = SewvConfig.WEAPON_SWITCH_COOLDOWN_TICKS.get();
        }

        // Same reasoning as DriveVehicleGoal.fireAssistIfSpecial: a lofted guided-munition
        // solution can't pass SBW's native 4° straight-line gate at range, so this fires it
        // directly within the wider configured cone. Cannon/MG keep firing through the native gate.
        if (this.selectedRole == VehicleWeapons.WEAPON_SPECIAL) {
            VehicleWeapons.tryAiFireAssist(this.vehicle, this.unit, target,
                    SewvConfig.AI_FIRE_ASSIST_CONE_DEG.get());
        }
    }
}
