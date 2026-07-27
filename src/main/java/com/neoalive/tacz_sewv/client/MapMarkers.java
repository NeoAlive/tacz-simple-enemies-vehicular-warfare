package com.neoalive.tacz_sewv.client;

import com.neoalive.tacz_sewv.util.BattleFieldMarker;
import com.neoalive.tacz_sewv.util.VehicleMarker;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The client's picture of its own PMC vehicles, as last sent by the server, plus which of them the
 * player has selected on the map.
 *
 * <p>Deliberately free of any Xaero type: the packet handler writes here, and it must be safe to
 * classload on an install with no map mod. Everything Xaero-shaped lives in {@code client.xaero}.
 *
 * <p>The list is replaced wholesale by each packet rather than merged — a marker that stops being
 * sent (hull destroyed, crew killed, chunk unloaded) has to disappear, and a full replacement is
 * the only version of that with no per-marker expiry bookkeeping. The server is the source of
 * truth: an empty packet clears the map. There is deliberately <b>no client-side wall-clock
 * expiry</b> — with {@code mapLive} a hitch or dropped packet used to blank the whole picture
 * mid-open (markers flashed once on accept, then vanished).
 *
 * <p>Must be {@link #clear() cleared} on client logout: the store is static for the JVM session, and
 * entity network ids are reused in the next world, so leaving stale markers paints ghosts from the
 * previous save until the next sync lands (and that sync can be delayed — see
 * {@code OwnedVehicleTracker}'s {@code nextSend} reset).
 */
public final class MapMarkers {

    private static List<VehicleMarker> markers = List.of();
    private static List<BattleFieldMarker> battleFields = List.of();
    private static final Set<Integer> SELECTED = new HashSet<>();

    private MapMarkers() {}

    public static void accept(List<VehicleMarker> incoming, List<BattleFieldMarker> fields) {
        markers = List.copyOf(incoming);
        battleFields = List.copyOf(fields);
        // A selected hull that is gone is not selectable any more, and leaving it in would keep
        // sending orders into the void.
        SELECTED.removeIf(driverId -> markers.stream().noneMatch(m -> m.driverId() == driverId));
    }

    /** Drop everything — call on disconnect so a new world never inherits the last one's picture. */
    public static void clear() {
        markers = List.of();
        battleFields = List.of();
        SELECTED.clear();
    }

    public static List<VehicleMarker> markers() {
        return markers;
    }

    /** Debug BattleField overlays synced with the vehicle markers — empty when none populated. */
    public static List<BattleFieldMarker> battleFields() {
        return battleFields;
    }

    public static boolean isSelected(VehicleMarker marker) {
        return SELECTED.contains(marker.driverId());
    }

    /**
     * Selection is for units you can actually order, so an enemy or an allied NPC hull is inert to
     * a click. Answers whether the click did anything, which is what lets the caller decide whether
     * to swallow it.
     */
    public static boolean toggleSelected(VehicleMarker marker) {
        if (marker.allegiance() != VehicleMarker.Allegiance.OWN) return false;
        if (!SELECTED.remove(marker.driverId())) SELECTED.add(marker.driverId());
        return true;
    }

    /**
     * Adds an OWN marker to the selection (a no-op for one already in, or one you cannot command).
     * Used by the map's box-select, which ADDS to the current set rather than toggling. Answers
     * whether it is now selected and ownable, so a caller can count what a box actually caught.
     */
    public static boolean addSelected(VehicleMarker marker) {
        if (marker.allegiance() != VehicleMarker.Allegiance.OWN) return false;
        SELECTED.add(marker.driverId());
        return true;
    }

    /** The drivers to order, as a snapshot — the caller sends one order packet per id. */
    public static Set<Integer> selected() {
        return Set.copyOf(SELECTED);
    }

    public static void clearSelection() {
        SELECTED.clear();
    }
}
