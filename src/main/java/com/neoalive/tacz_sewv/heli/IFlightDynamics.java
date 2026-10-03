package com.neoalive.tacz_sewv.heli;

/**
 * Mixed onto SBW's {@code VehicleEntity} by {@code MixinVehicleFlightDynamics}. A hull "carries
 * flight dynamics" while it holds a {@link HeliRuntime} (server) or while the engine mixin is
 * cancelling SBW's engine for an AI-crewed helicopter (client).
 *
 * <p>The flight mode is written by the engine mixin on every {@code helicopterEngine} call, i.e.
 * every tick, inside {@code travel()} and therefore before the gravity and terrain-compat reads
 * later in the same {@code baseTick}:
 * <ul>
 * <li>{@link #NONE}: SBW flies the hull (player pilot, no runtime, not a helicopter).</li>
 * <li>{@link #PARKED}: our runtime holds the hull at rest; SBW's gravity and landing-gear
 *     alignment apply as normal.</li>
 * <li>{@link #FLYING}: our integrator owns the motion; SBW's gravity and terrain-compat are
 *     switched off for this hull.</li>
 * </ul>
 */
public interface IFlightDynamics {

    int NONE = 0, PARKED = 1, FLYING = 2;

    HeliRuntime sewv$heliRuntime();

    void sewv$setHeliRuntime(HeliRuntime runtime);

    int sewv$flightMode();

    void sewv$setFlightMode(int mode);

    /** Client: the last roll the server synced (degrees), NaN before the first packet. */
    float sewv$clientRoll();

    void sewv$setClientRoll(float roll);
}
