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
}

