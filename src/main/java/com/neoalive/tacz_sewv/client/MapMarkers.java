package com.neoalive.tacz_sewv.client;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import com.neoalive.tacz_sewv.invasion.SweepOverlayState;
import com.neoalive.tacz_sewv.map.BattleFieldMarker;
import com.neoalive.tacz_sewv.map.FobMarker;
import com.neoalive.tacz_sewv.map.VehicleMarker;

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
 * <p>Selection is keyed on {@link VehicleMarker#vehicleId()} so an empty assigned hull (driver id
 * 0) stays selected and reachable while its crew routes back to board.
 *
 * <p>Must be {@link #clear() cleared} on client logout: the store is static for the JVM session, and
 * entity network ids are reused in the next world, so leaving stale markers paints ghosts from the
 * previous save until the next sync lands (and that sync can be delayed — see
 * {@code OwnedVehicleTracker}'s {@code nextSend} reset).
 */
public final class MapMarkers {

    /** Sentinel driver id on markers with no crew — not valid for SEM order packets. */
    public static final int NO_DRIVER = 0;

    private static List<VehicleMarker> markers = List.of();
    private static final Int2ObjectOpenHashMap<VehicleMarker> BY_VEHICLE_ID = new Int2ObjectOpenHashMap<>();
    private static final Int2ObjectOpenHashMap<VehicleMarker> BY_DRIVER_ID = new Int2ObjectOpenHashMap<>();
    private static List<BattleFieldMarker> battleFields = List.of();
    @Nullable
    private static SweepOverlayState sweepOverlay;
    @Nullable
    private static FobMarker fobMarker;
    /** Selected hull network ids — survives driver dismount and sync refreshes. */
    private static final Set<Integer> SELECTED = new HashSet<>();

    private MapMarkers() {}

    public static void accept(List<VehicleMarker> incoming, List<BattleFieldMarker> fields) {
        accept(incoming, fields, null);
    }

    public static void accept(List<VehicleMarker> incoming, List<BattleFieldMarker> fields,
                              @Nullable SweepOverlayState sweep) {
        accept(incoming, fields, sweep, null);
    }

    public static void accept(List<VehicleMarker> incoming, List<BattleFieldMarker> fields,
                              @Nullable SweepOverlayState sweep, @Nullable FobMarker fob) {
        markers = List.copyOf(incoming);
        BY_VEHICLE_ID.clear();
        BY_DRIVER_ID.clear();
        for (VehicleMarker marker : markers) {
            BY_VEHICLE_ID.put(marker.vehicleId(), marker);
            if (marker.driverId() != NO_DRIVER) {
                BY_DRIVER_ID.put(marker.driverId(), marker);
            }
        }
        battleFields = List.copyOf(fields);
        sweepOverlay = sweep;
        fobMarker = fob;
        SELECTED.removeIf(vehicleId -> !BY_VEHICLE_ID.containsKey(vehicleId));
    }

    /** Drop everything — call on disconnect so a new world never inherits the last one's picture. */
    public static void clear() {
        markers = List.of();
        BY_VEHICLE_ID.clear();
        BY_DRIVER_ID.clear();
        battleFields = List.of();
        sweepOverlay = null;
        fobMarker = null;
        SELECTED.clear();
    }

    @Nullable
    public static FobMarker fobMarker() {
        return fobMarker;
    }

    public static List<VehicleMarker> markers() {
        return markers;
    }

    @Nullable
    public static VehicleMarker markerForVehicle(int vehicleId) {
        return BY_VEHICLE_ID.get(vehicleId);
    }

    /** Same marker, looked up by the commanding unit's own id rather than the hull's. */
    @Nullable
    public static VehicleMarker markerForDriver(int driverId) {
        return BY_DRIVER_ID.get(driverId);
    }

    /** Debug BattleField overlays synced with the vehicle markers — empty when none populated. */
    public static List<BattleFieldMarker> battleFields() {
        return battleFields;
    }

    /** Active Sweep &amp; Advance rect overlay for this player, or null when none. */
    @Nullable
    public static SweepOverlayState sweepOverlay() {
        return sweepOverlay;
    }

    public static boolean isSelected(VehicleMarker marker) {
        return SELECTED.contains(marker.vehicleId());
    }

    /**
     * Selection is for units you can order (OWN) and for a single HOSTILE designate (Attack this
     * unit). FRIENDLY stays inert. Answers whether the click did anything, which is what lets the
     * caller decide whether to swallow it.
     */
    public static boolean toggleSelected(VehicleMarker marker) {
        VehicleMarker.Allegiance a = marker.allegiance();
        if (a != VehicleMarker.Allegiance.OWN && a != VehicleMarker.Allegiance.HOSTILE) return false;
        if (!SELECTED.remove(marker.vehicleId())) SELECTED.add(marker.vehicleId());
        return true;
    }

    /**
     * Adds an OWN marker to the selection (a no-op for one already in, or one you cannot command).
     * Used by the map's box-select, which ADDS to the current set rather than toggling. Answers
     * whether it is now selected and ownable, so a caller can count what a box actually caught.
     * Hostiles are never box-added — designate them with a click.
     */
    public static boolean addSelected(VehicleMarker marker) {
        if (marker.allegiance() != VehicleMarker.Allegiance.OWN) return false;
        SELECTED.add(marker.vehicleId());
        return true;
    }

    /** Selected hull ids — stable across crew changes and empty-pad markers. */
    public static Set<Integer> selectedVehicleIds() {
        return Set.copyOf(SELECTED);
    }

    /**
     * The drivers to order, as a snapshot — only OWN hulls with a live crew driver. Empty assigned
     * hulls and any selected HOSTILE designate stay on the map but are skipped here.
     */
    public static Set<Integer> selected() {
        Set<Integer> drivers = new HashSet<>();
        for (int vehicleId : SELECTED) {
            VehicleMarker marker = BY_VEHICLE_ID.get(vehicleId);
            if (marker == null || marker.allegiance() != VehicleMarker.Allegiance.OWN) continue;
            if (marker.driverId() != NO_DRIVER) drivers.add(marker.driverId());
        }
        return Set.copyOf(drivers);
    }

    /**
     * SEM entity id of the designated HOSTILE when exactly one is selected (and has a live driver);
     * {@code -1} otherwise. Used only for map Attack-this-unit.
     */
    public static int selectedHostileTargetId() {
        int id = -1;
        int count = 0;
        for (int vehicleId : SELECTED) {
            VehicleMarker marker = BY_VEHICLE_ID.get(vehicleId);
            if (marker == null || marker.allegiance() != VehicleMarker.Allegiance.HOSTILE) continue;
            if (marker.driverId() == NO_DRIVER) continue;
            count++;
            id = marker.driverId();
            if (count > 1) return -1;
        }
        return count == 1 ? id : -1;
    }

    public static void clearSelection() {
        SELECTED.clear();
    }
}
