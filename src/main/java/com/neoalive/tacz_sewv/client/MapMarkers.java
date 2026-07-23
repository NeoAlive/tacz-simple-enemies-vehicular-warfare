package com.neoalive.tacz_sewv.client;

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
 * the only version of that with no per-marker expiry bookkeeping. {@link #markers()} additionally
 * returns nothing at all once the last packet is older than {@link #STALE_TICKS}, so a server that
 * goes quiet (mod absent, feature turned off mid-session) leaves a blank map rather than a frozen
 * one showing hulls that may have moved miles.
 */
public final class MapMarkers {

    /**
     * How long a list stays believable, in wall-clock milliseconds: five times the default sync
     * interval, so a dropped packet does not make the map blink.
     *
     * <p>Wall clock rather than game time on purpose — game time rewinds when another world is
     * loaded in the same session, which would leave the previous world's markers looking fresh
     * forever.
     */
    private static final long STALE_MILLIS = 5_000L;

    private static List<VehicleMarker> markers = List.of();
    private static final Set<Integer> SELECTED = new HashSet<>();
    private static long lastUpdate = Long.MIN_VALUE;

    private MapMarkers() {}

    public static void accept(List<VehicleMarker> incoming) {
        markers = List.copyOf(incoming);
        lastUpdate = System.currentTimeMillis();
        // A selected hull that is gone is not selectable any more, and leaving it in would keep
        // sending orders into the void.
        SELECTED.removeIf(driverId -> markers.stream().noneMatch(m -> m.driverId() == driverId));
    }

    public static List<VehicleMarker> markers() {
        return System.currentTimeMillis() - lastUpdate > STALE_MILLIS ? List.of() : markers;
    }

    public static boolean isSelected(VehicleMarker marker) {
        return SELECTED.contains(marker.driverId());
    }

    public static void toggleSelected(VehicleMarker marker) {
        if (!SELECTED.remove(marker.driverId())) SELECTED.add(marker.driverId());
    }

    /** The drivers to order, as a snapshot — the caller sends one order packet per id. */
    public static Set<Integer> selected() {
        return Set.copyOf(SELECTED);
    }

    public static void clearSelection() {
        SELECTED.clear();
    }
}
