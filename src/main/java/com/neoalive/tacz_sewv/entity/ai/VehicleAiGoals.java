package com.neoalive.tacz_sewv.entity.ai;

import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

/**
 * Shared goal wiring for any unit that can crew a weapon it doesn't own — vehicles,
 * mortars and TOWs alike — for PmcUnitEntity (player-commandable) as well as
 * RUunitEntity/USunitEntity (hostile, no order queue).
 *
 * <p>BoardVehicleGoal lives here rather than with the PMC-only goals because it only ever
 * EXECUTES a standing order — it has no opinion on where that order came from. A PMC gets one
 * from the player over the network; an RU/US unit gets the identical one from
 * SeekAbandonedVehicleGoal, which is the only piece that is faction-gated.
 */
public final class VehicleAiGoals {

    private VehicleAiGoals() {}

    public static void addDriveGoals(AbstractUnit unit) {
        // Priority 0: bailing out of a hull that's about to die outranks everything
        // it could otherwise be doing from inside that hull.
        unit.goalSelector.addGoal(0, new BailOutVehicleGoal(unit));
        // One drive goal per locomotion type — ground, flight, water. All three are registered
        // on every crew; each gates on the mounted vehicle's engine type, so exactly one
        // activates for whatever hull the unit ends up in.
        unit.goalSelector.addGoal(1, new DriveVehicleGoal(unit));
        unit.goalSelector.addGoal(1, new DriveHelicopterGoal(unit));
        unit.goalSelector.addGoal(1, new DriveShipGoal(unit));
        // Both drive goals above only ever act for the FIRST passenger (the driver/steering
        // seat); this covers everyone else in a weapon-bearing seat — a separate turret seat,
        // firing ports — with the same ammo doctrine and fire-assist, claiming no flags since
        // SBW's native per-seat loop already aims and fires that seat. GROUND/SHIP HULLS ONLY —
        // see the class doc on TurretGunnerGoal: a helicopter gunner firing mid-flight was found
        // to destabilize DriveHelicopterGoal's landing approach, so helicopters are excluded
        // there until that interaction is understood.
        unit.goalSelector.addGoal(1, new TurretGunnerGoal(unit));
        unit.goalSelector.addGoal(1, new VehicleMinRangeGoal(unit));
        // Gates on the mounted hull being a TOW, the same way the two drive goals gate on
        // engine type. It belongs here rather than with the PMC-only goals because loading
        // a launcher needs nothing but the crew's own inventory — no network bridge — so
        // any crew that carries missiles can work one.
        unit.goalSelector.addGoal(1, new ManTowGoal(unit));
        // MOVE+LOOK at priority 1: a crew on a mortar outranks SEM's cover/approach/order
        // goals, which would walk it off the tube, and its rifle goal, which would have it
        // lean out and shoot instead of working the tube. Gates on holding a claim, so it
        // costs a null check on every unit that has none.
        unit.goalSelector.addGoal(1, new ManMortarGoal(unit));
        // Walks the unit to a hull it has been ordered onto and seats it. Gates on holding a
        // pending order, so it costs a boolean read on every unit that has none.
        unit.goalSelector.addGoal(1, new BoardVehicleGoal(unit));
        // Writes that order for an RU/US unit that spots an abandoned hull. Claims no flags and
        // never actually runs — it hands the job to the goal above. RU/US-gated internally.
        unit.goalSelector.addGoal(1, new SeekAbandonedVehicleGoal(unit));
        // Works a SuperbWarfare launcher for a unit on foot (an IFV dismount issued one, or a
        // PMC a player equipped). Gates on an SBW gun being in the main hand, which is one
        // instanceof for everyone else.
        unit.goalSelector.addGoal(1, new AtWeaponGoal(unit));
        // Priority 2: the turret sweep and radio chatter of a crew with nothing to fight. Claims no
        // flags and only runs while the unit holds no target, so it yields to everything above by
        // simply not being applicable. The idle DRIVING half is not here — it is a destination
        // fallback in VehicleTargeting, handled by the drive goal like any other.
        unit.goalSelector.addGoal(2, new IdleCrewGoal(unit));
        // Priority 2: while mounted this owns the TARGET flag over SEM's short
        // vanilla scans (also priority 2+, but strictly less reach), while SEM's
        // HurtByTargetGoal at priority 1 still preempts for retaliation.
        unit.targetSelector.addGoal(2, new VehicleTargetScanGoal(unit));
        // Priority 1, and it has to be: SEM's ladder puts a catch-all Monster scan at the
        // SAME priority 2 as its troop scans, and vanilla only lets a strictly higher
        // priority steal a held flag — so nothing at 2 can take a crew off a zombie. See
        // the class doc. Still loses to a priority-0 radio fire mission.
        unit.targetSelector.addGoal(1, new CrewTargetPriorityGoal(unit));
    }
}
