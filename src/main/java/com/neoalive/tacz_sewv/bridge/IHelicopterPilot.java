package com.neoalive.tacz_sewv.bridge;

import net.minecraft.core.BlockPos;

/**
 * Player-issued flight command carried on a PmcUnitEntity, read by
 * {@link com.neoalive.tacz_sewv.entity.ai.DriveHelicopterGoal}. Set server-side by
 * {@link com.neoalive.tacz_sewv.network.PacketHelicopterCommand}; not synced to the
 * client (the goal runs server-side, same as the boarding flag on
 * {@link IVehicleBoarder}).
 *
 * <p>TAKEOFF is a one-shot sequence that clears itself (to NONE) once the
 * helicopter reaches cruise altitude. LANDING transitions to LANDED on touchdown,
 * and LANDED is sticky: the helicopter stays shut down on the ground — ignoring
 * move/follow orders — until a new TAKEOFF or LANDING command replaces it. NONE is
 * the resting airborne state where the goal follows the SEM order queue as usual.
 */
public interface IHelicopterPilot {
    int HELI_CMD_NONE = 0;
    int HELI_CMD_TAKEOFF = 1;
    int HELI_CMD_LANDING = 2;
    int HELI_CMD_LANDED = 3;

    void sewv$setHeliCommand(int command);
    int sewv$getHeliCommand();

    void sewv$setHeliLandPos(BlockPos pos);
    BlockPos sewv$getHeliLandPos();
}
