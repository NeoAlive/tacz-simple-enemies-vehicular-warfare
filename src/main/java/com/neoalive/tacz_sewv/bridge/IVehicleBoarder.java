package com.neoalive.tacz_sewv.bridge;

public interface IVehicleBoarder {
    void tacz_sewv$setMountTargetId(int id);
    int tacz_sewv$getMountTargetId();
    void tacz_sewv$setBoarding(boolean boarding);
    boolean tacz_sewv$isBoarding();

    /**
     * "Board, but never take the wheel." SuperbWarfare's driver is simply the FIRST passenger, so
     * a unit under this flag holds off boarding an empty hull and only mounts once someone else is
     * already aboard — leaving it in a non-driver seat. Transient with the rest of the pending
     * board order (it targets an entity by network id, so none of it survives a reload).
     */
    void tacz_sewv$setPassengerOnly(boolean passengerOnly);
    boolean tacz_sewv$isPassengerOnly();

    /**
     * Whether a passenger-only order may proceed past the wait-beside-the-hull stage. Set true by
     * the owning player's "board my vehicle" keypress ({@code PacketClearBoarding}) once they are
     * seated in the target hull themselves, so the squad piles in only after the player has had the
     * chance to pick their own seat. Reset to false whenever a new board order is issued.
     */
    void tacz_sewv$setBoardCleared(boolean cleared);
    boolean tacz_sewv$isBoardCleared();
}
