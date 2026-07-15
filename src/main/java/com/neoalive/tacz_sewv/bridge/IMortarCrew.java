package com.neoalive.tacz_sewv.bridge;

/**
 * A unit's claim on a Superb Warfare mortar, read by
 * {@link com.neoalive.tacz_sewv.entity.ai.ManMortarGoal}. Set server-side by
 * {@link com.neoalive.tacz_sewv.network.PacketManMortar}; not synced to the client
 * (the goal runs server-side, same as the boarding flag on {@link IVehicleBoarder}).
 *
 * <p>The claim doubles as the mortar's occupancy record. A MortarEntity has no owner
 * or seats of its own, so there is nothing on the mortar to mark — instead
 * {@link com.neoalive.tacz_sewv.entity.ai.MortarSupport#isMortarClaimed} answers
 * "is this mortar taken?" by looking for a unit pointing at it. That keeps the claim
 * self-healing: a crew that dies or unloads stops pointing at its mortar and thereby
 * releases it, with no registry to keep in sync.
 *
 * <p>Stored in transient mixin fields rather than persistent NBT (unlike
 * {@link IHelicopterPilot}) because the claim is an entity network id, which is not
 * stable across sessions — persisting it would resolve to an unrelated entity on
 * reload. A pending mortar order is simply dropped when the world reloads.
 */
public interface IMortarCrew {

    /** No mortar claimed. */
    int NO_MORTAR = -1;

    void sewv$setMortarTargetId(int id);

    int sewv$getMortarTargetId();
}
