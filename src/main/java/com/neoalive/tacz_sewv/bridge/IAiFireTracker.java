package com.neoalive.tacz_sewv.bridge;

/**
 * Mixed onto SBW's {@code VehicleEntity} (by {@code MixinVehicleFireCooldown}) to expose
 * when an AI crew last actually FIRED this hull.
 *
 * <p>This is the signal that tells a parked crew apart from a stuck one. The drive goal
 * can already see that it is holding a live target and sitting still — but sitting still
 * is exactly what the standoff doctrine asks for, so position alone can never
 * distinguish "holding the ring and killing things" from "holding the ring and
 * accomplishing nothing". Whether shots are coming out is the only thing that does, and
 * the fire path already knows: {@code MixinVehicleFireCooldown} stamps every AI shot to
 * enforce its own cooldown. This publishes that existing stamp rather than tracking it
 * twice.
 *
 * <p>Deliberately reports the OUTCOME (a shot happened) rather than any particular cause
 * of not-shooting. A crew can be silenced by turret elevation limits, terrain, smoke, a
 * weapon that never reloads, or something not yet diagnosed; a watchdog built on the
 * outcome catches all of them, including the ones we haven't found.
 *
 * <p>Per-HULL, not per-seat: a gunner happily hosing infantry masks a deadlocked cannon
 * on the same vehicle. That is the intended trade — the hull is doing something, and
 * repositioning it to un-stick one weapon would interrupt the other.
 */
public interface IAiFireTracker {

    /** No AI shot has ever been fired from this hull. */
    long NEVER = Long.MIN_VALUE;

    /**
     * Game time of the last shot an {@code AbstractUnit} crew fired from this hull, or
     * {@link #NEVER}.
     *
     * <p>Callers MUST test for {@link #NEVER} before subtracting it from the current
     * tick: {@code now - Long.MIN_VALUE} overflows to a negative number, which reads as
     * "fired in the future" and would silence a watchdog permanently on precisely the
     * crew that has never managed a single shot — the case worth catching.
     */
    long tacz_sewv$getLastAiShotTick();
}
