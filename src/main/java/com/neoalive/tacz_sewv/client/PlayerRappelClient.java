package com.neoalive.tacz_sewv.client;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.client.player.LocalPlayer;

import com.neoalive.tacz_sewv.network.PacketPlayerSelfRappelLock;

/**
 * Client state for player-driven rappel: rope wires on a hull, and local input lock while
 * approaching (hover) or sliding a rope. Separate from {@link HeliRunPhaseClient}.
 */
public final class PlayerRappelClient {

    private static final IntOpenHashSet WIRE_HULLS = new IntOpenHashSet();
    private static byte lockMode = PacketPlayerSelfRappelLock.MODE_OFF;
    private static int hoverHullId = -1;

    private PlayerRappelClient() {}

    public static void setWires(int hullId, boolean active) {
        if (active) {
            WIRE_HULLS.add(hullId);
        } else {
            WIRE_HULLS.remove(hullId);
        }
    }

    public static boolean hasWires(int hullId) {
        return WIRE_HULLS.contains(hullId);
    }

    public static void setLock(byte mode, int hullId) {
        lockMode = mode;
        hoverHullId = mode == PacketPlayerSelfRappelLock.MODE_HOVER ? hullId : -1;
        if (mode == PacketPlayerSelfRappelLock.MODE_OFF) {
            hoverHullId = -1;
        }
    }

    public static byte lockMode() {
        return lockMode;
    }

    public static boolean isMovementLocked() {
        return lockMode == PacketPlayerSelfRappelLock.MODE_HOVER
                || lockMode == PacketPlayerSelfRappelLock.MODE_ROPE;
    }

    /**
     * Drop a stale lock the server never cleared (e.g. remount / creative fly after a desync).
     *
     * @return true if the lock was cleared
     */
    public static boolean clearIfStale(LocalPlayer player) {
        if (lockMode == PacketPlayerSelfRappelLock.MODE_OFF) return false;

        if (lockMode == PacketPlayerSelfRappelLock.MODE_HOVER) {
            if (player.getAbilities().flying
                    || player.getVehicle() == null
                    || player.getVehicle().getId() != hoverHullId) {
                setLock(PacketPlayerSelfRappelLock.MODE_OFF, -1);
                return true;
            }
            return false;
        }

        // MODE_ROPE: remounted or creative-flying means we are no longer on a rope.
        if (player.isPassenger() || player.getAbilities().flying) {
            setLock(PacketPlayerSelfRappelLock.MODE_OFF, -1);
            return true;
        }
        return false;
    }

    public static void clearAll() {
        WIRE_HULLS.clear();
        lockMode = PacketPlayerSelfRappelLock.MODE_OFF;
        hoverHullId = -1;
    }
}
