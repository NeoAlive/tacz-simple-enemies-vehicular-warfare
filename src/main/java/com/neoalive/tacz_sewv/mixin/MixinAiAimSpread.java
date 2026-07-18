package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.data.gun.ShootParameters;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.config.SewvConfig;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Gives an AI vehicle crew a human's aim. Untreated it has none to give: SBW's turret
 * auto-aim runs {@code RangeTool.calculateFiringSolution} every tick with the target's exact
 * bounding-box centre, its exact velocity as lead, and the projectile's exact ballistics — a
 * closed-form perfect solution, re-solved continuously. A crewed tank therefore lands first
 * rounds at any range the crew can acquire at, which is what makes them unfun to fight.
 *
 * <p><b>The dispersion goes on the PROJECTILE, not on the turret's aim.</b> Biasing the aim
 * vector is the obvious move and it is a trap, twice over:
 * <ul>
 *   <li>{@code turretAutoAimFromVector} SLEWS toward its argument at the turret's turn rate
 *       rather than snapping to it, so it low-passes the error. Fresh noise each tick averages
 *       out to a turret still pointing dead-on; only a bias held for many ticks would survive,
 *       which means per-vehicle state and a re-roll timer.</li>
 *   <li>SBW's AI fire gate ({@code VehicleEntity.tick}) is a hard-coded 4 degrees between the
 *       barrel and the true target line, and the ballistic loft ALREADY eats part of that
 *       budget at range — this is the same 4 degrees that stops an untreated AI TOW firing past
 *       ~25 blocks (see MixinTowTurretAim). Aim error is subtracted from what is left, so
 *       "less accurate" would silently become "holds fire at range", and worst on exactly the
 *       long shots it was meant to spoil.</li>
 * </ul>
 * Dispersing the round instead leaves the barrel pointed true: the gate still passes, rate of
 * fire is untouched, the turret still tracks convincingly, and the shot simply goes wide.
 *
 * <p>Hooked at {@code GunItem.shootBullet}'s single read of {@code ShootParameters.spread} —
 * SBW's own dispersion channel, applied per projectile (so grapeshot's 36 pellets each scatter
 * independently) and in the same units as the datapack's {@code Spread}. It is the one place
 * every vehicle weapon's dispersion funnels through, and it carries the shooter, which is what
 * makes a stateless redirect possible at all. Note the ShootParameters construction itself sits
 * in a Kotlin lambda ({@code vehicleShoot$lambda$20}) whose synthetic name would not survive an
 * SBW recompile; this field read is stable.
 *
 * <p>Deliberately NOT affected:
 * <ul>
 *   <li><b>Players</b> — they aim by hand, and this is a difficulty knob for the AI.</li>
 *   <li><b>Mortar crews</b> — a MortarEntity has no seats, so its crew stands beside the tube
 *       and fails the "riding a vehicle" test for free. That is the intended exemption:
 *       MortarSupport.solveAim does its own ballistics and ManMortarGoal paces its own fire,
 *       so dispersion here would nerf a parallel flow that was tuned separately.</li>
 *   <li><b>The TOW</b> — nominally affected, actually not: a WireGuideMissileEntity re-steers
 *       onto the launcher's barrel line every tick, so it rides out any launch dispersion.
 *       Correct by accident, and correct in principle — a wire-guided missile IS accurate.</li>
 * </ul>
 */
@Mixin(targets = "com.atsuishio.superbwarfare.item.gun.GunItem")
public abstract class MixinAiAimSpread {

    @Unique
    private static final String TACZ_SEWV$ACCURATE = "accurate";
    @Unique
    private static final String TACZ_SEWV$SCALED = "scaled";

    @Redirect(
            method = "shootBullet(Lcom/atsuishio/superbwarfare/data/gun/ShootParameters;)Z",
            at = @At(
                    value = "FIELD",
                    target = "Lcom/atsuishio/superbwarfare/data/gun/ShootParameters;spread:D",
                    opcode = Opcodes.GETFIELD),
            remap = false)
    private double tacz_sewv$disperseAiShot(ShootParameters parameters) {
        double spread = parameters.spread;

        if (!(parameters.shooter instanceof AbstractUnit unit)) return spread;
        // Riding a hull is the whole test for "this is a vehicle crew": a mortar crew stands
        // beside its tube and drops out here, and so does any unit shooting something else.
        if (!(unit.getVehicle() instanceof VehicleEntity vehicle)) return spread;

        String mode = SewvConfig.AI_AIM_ACCURACY.get();
        if (TACZ_SEWV$ACCURATE.equals(mode)) return spread;

        double added = SewvConfig.AI_AIM_SPREAD_DEG.get();
        if (TACZ_SEWV$SCALED.equals(mode)) {
            // Occupied seats, not total seats: a hull's seat count is fixed, so dividing by it
            // would make the setting a constant. Crew losses are what this mode is about.
            // Never zero — the shooter is itself a passenger, so this is at least 1.
            added /= Math.max(1, vehicle.getPassengers().size());
        }
        return spread + added;
    }
}
