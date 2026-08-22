package com.neoalive.tacz_sewv.bridge;

/**
 * A PMC's standing "go capture a medic" order, worked by {@code PmcCaptureMedicGoal}.
 *
 * <p>Transient, by the same rule as {@link IEscort}: it is a player-issued dispatch (TDT "Capture
 * Medic" button, see {@code PacketCaptureMedic}), not autonomous doctrine — PMC behaviour is
 * player-commanded, unlike RU/US, so nothing here may engage without this flag first being set.
 * Cleared automatically once the goal completes (subdues and converts/fails to convert a medic) or
 * is interrupted by a real combat target; a reload simply drops the order like any other.
 */
public interface ICaptureMedic {

    void tacz_sewv$setCaptureMedicOrdered(boolean ordered);

    boolean tacz_sewv$isCaptureMedicOrdered();
}
