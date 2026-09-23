package com.neoalive.tacz_sewv.client.territory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.neoalive.tacz_sewv.network.PacketTerritoryState;

/**
 * Client-side copy of the last {@link PacketTerritoryState}. Xaero-free on purpose: the quick wheel, the TDT and
 * the FOB screens read {@link #inTerritory} without ever touching the map mod. Replaced wholesale on every
 * push and cleared on logout — never a source of truth, the server re-validates every command.
 */
public final class TerritoryClient {

    private static volatile boolean modeOn;
    private static volatile List<PacketTerritoryState.Row> roster = List.of();
    private static volatile Set<Integer> inTerritory = Set.of();
    private static volatile long[] front = new long[0];
    private static volatile int[] coverage = new int[0];
    private static volatile long[] manualLine = new long[0];

    private TerritoryClient() {}

    public static void accept(PacketTerritoryState state) {
        Set<Integer> ids = new HashSet<>();
        for (PacketTerritoryState.Row r : state.roster()) {
            if (r.posted()) ids.add(r.id());
        }
        modeOn = state.modeOn();
        roster = state.roster();
        inTerritory = ids;
        front = state.front();
        coverage = state.coverage();
        manualLine = state.manualLine();
    }

    public static void clear() {
        modeOn = false;
        roster = List.of();
        inTerritory = Set.of();
        front = new long[0];
        coverage = new int[0];
        manualLine = new long[0];
    }

    public static boolean modeOn() { return modeOn; }
    public static List<PacketTerritoryState.Row> roster() { return roster; }
    public static long[] front() { return front; }
    public static int[] coverage() { return coverage; }

    /** The stored manual line (drag order), or empty. Display + the "clear" affordance only. */
    public static long[] manualLine() { return manualLine; }

    /** True for a unit currently posted by Territory Mode: hidden from the quick wheel, the TDT and the FOB list. */
    public static boolean inTerritory(int entityId) {
        return inTerritory.contains(entityId);
    }
}
