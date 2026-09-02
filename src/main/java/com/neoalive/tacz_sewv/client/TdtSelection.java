package com.neoalive.tacz_sewv.client;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType;
import com.atsuishio.superbwarfare.entity.vehicle.DroneEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.nekoyuni.SimpleEnemyMod.client.gui.overlay.CommanderOverlayRenderer;
import net.nekoyuni.SimpleEnemyMod.client.system.ClientGlowManager;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.support.SupportRole;
import com.neoalive.tacz_sewv.entity.unit.PmcCommanderEntity;
import com.neoalive.tacz_sewv.map.VehicleMarker;

/**
 * Client-side unit selection for the Tactical Data Terminal ribbon. Scan matches
 * {@link BoardKeybind}'s owned-PMC cylinder; {@link #resolve} prefers an explicit ribbon
 * selection, then SEM's pick-mode snapshot, then every owned unit in range.
 */
public final class TdtSelection {

    public static final double SCAN_RADIUS = 512.0;

    /**
     * {@code vehicleId} is -1 when dismounted — what {@link #distinctCount} dedupes a crew by.
     * {@code name} is the unit's rolled identity ({@link com.neoalive.tacz_sewv.crew.NpcIdentity}),
     * read off its synced custom name rather than persistent data — persistent NBT never leaves
     * the server, but a custom name is ordinary synced entity data. Empty if unassigned (name
     * assignment disabled, or — for the ambient/ownerless case, which can't reach this scan at all
     * since every entry here already passed {@code isOwnedBy(player)} — never applicable here).
     */
    public record Entry(int id, VehicleMarker.Kind kind, boolean isCommander, int platoonColorRgb,
                        int vehicleId, String name) {}

    private static final LinkedHashSet<Integer> SELECTED = new LinkedHashSet<>();
    private static List<Entry> scanned = List.of();

    private TdtSelection() {}

    public static Set<Integer> selected() {
        return SELECTED;
    }

    public static List<Entry> scanned() {
        return scanned;
    }

    public static int selectedCount() {
        return SELECTED.size();
    }

    public static boolean isSelected(int id) {
        return SELECTED.contains(id);
    }

    /** Refresh the nearby owned-PMC list. Prunes only selection the client can prove is gone. */
    public static void scan() {
        pruneSelection();
        scanned = scanEntries(SCAN_RADIUS);
        syncGlow();
    }

    /**
     * Drops a selected id only when the client can <b>see</b> it is no longer an owned live PMC.
     * An id the client has no entity for is out of tracking range, not gone — this used to be a
     * {@code retainAll} against the scan, so a selection quietly emptied itself the moment a unit
     * drove past the tracking distance and every bulk order (Route Vehicles to FOB especially)
     * became a no-op with nothing on screen to explain it.
     */
    private static void pruneSelection() {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) return;
        SELECTED.removeIf(id -> {
            Entity e = mc.level.getEntity(id);
            if (e == null) return false;
            return !(e instanceof PmcUnitEntity pmc) || !pmc.isAlive() || !pmc.isOwnedBy(player);
        });
    }

    /**
     * Unit ids an order should hit: ribbon selection if any, else SEM pick snapshot if any.
     * Empty when neither is set — never fall through to every owned unit in range (that caused
     * accidental mass orders from the TDT).
     */
    public static List<Integer> resolve(double radius) {
        if (!SELECTED.isEmpty()) {
            return List.copyOf(SELECTED);
        }
        Set<Integer> snap = CommanderOverlayRenderer.selectedUnitsSnapshot;
        if (snap != null && !snap.isEmpty()) {
            return new ArrayList<>(snap);
        }
        return List.of();
    }

    /**
     * On-foot owned PMC ids from ribbon / commander snapshot. Refreshes {@link #scan()} first so
     * mount state comes from the last nearby scan rather than requiring every id to be loaded on
     * the client (map funnel menu builds before distant entities are tracked).
     */
    public static List<Integer> resolveOnFoot(double radius) {
        scan();
        List<Integer> chosen = resolve(radius);
        if (chosen.isEmpty()) return List.of();

        Set<Integer> want = new HashSet<>(chosen);
        List<Integer> onFoot = new ArrayList<>();
        for (Entry e : scanned) {
            if (want.contains(e.id()) && e.vehicleId() < 0) {
                onFoot.add(e.id());
            }
        }
        if (onFoot.size() == want.size()) return onFoot;

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) return onFoot;

        for (int id : want) {
            if (onFoot.contains(id)) continue;
            if (!(mc.level.getEntity(id) instanceof PmcUnitEntity pmc)) continue;
            if (!pmc.isOwnedBy(player) || pmc.getVehicle() != null) continue;
            onFoot.add(id);
        }
        return onFoot;
    }

    public static void toggle(int id) {
        if (!SELECTED.add(id)) {
            SELECTED.remove(id);
        }
        syncGlow();
    }

    public static void select(int id) {
        SELECTED.add(id);
        syncGlow();
    }

    public static void deselect(int id) {
        SELECTED.remove(id);
        syncGlow();
    }

    public static void selectAll() {
        SELECTED.clear();
        for (Entry e : scanned) {
            SELECTED.add(e.id());
        }
        syncGlow();
    }

    public static void deselectAll() {
        SELECTED.clear();
        syncGlow();
    }

    /** SEM Select All: select everything scanned, or clear if already all selected. */
    public static void toggleSelectAll() {
        if (!scanned.isEmpty() && SELECTED.size() >= scanned.size()) {
            deselectAll();
        } else {
            selectAll();
        }
    }

    public static boolean allSelected() {
        return !scanned.isEmpty() && SELECTED.size() >= scanned.size();
    }

    public static void selectKind(VehicleMarker.Kind kind) {
        for (Entry e : scanned) {
            if (e.kind() == kind) {
                SELECTED.add(e.id());
            }
        }
        syncGlow();
    }

    /** Non-platoon entries only — a platoon member shows under {@link #byPlatoon} instead. */
    public static Map<VehicleMarker.Kind, List<Entry>> byKind() {
        Map<VehicleMarker.Kind, List<Entry>> map = new EnumMap<>(VehicleMarker.Kind.class);
        for (Entry e : scanned) {
            if (e.platoonColorRgb() != 0) continue;
            map.computeIfAbsent(e.kind(), k -> new ArrayList<>()).add(e);
        }
        return map;
    }

    /** Platoon entries, keyed by the platoon's colour (the only platoon identity synced to the client). */
    public static Map<Integer, List<Entry>> byPlatoon() {
        Map<Integer, List<Entry>> map = new LinkedHashMap<>();
        for (Entry e : scanned) {
            if (e.platoonColorRgb() == 0) continue;
            map.computeIfAbsent(e.platoonColorRgb(), c -> new ArrayList<>()).add(e);
        }
        return map;
    }

    /** How many distinct vehicles/dismounted units a bucket represents — a shared crew counts once. */
    public static int distinctCount(List<Entry> entries) {
        Set<Integer> keys = new HashSet<>();
        for (Entry e : entries) {
            keys.add(e.vehicleId() >= 0 ? e.vehicleId() : e.id());
        }
        return keys.size();
    }

    /** Generic armor/infantry icon for a platoon bucket — every member shares one broad family. */
    public static VehicleMarker.Kind representativeKind(List<Entry> entries) {
        if (!entries.isEmpty() && entries.get(0).kind().isInfantry()) {
            return VehicleMarker.Kind.INFANTRY;
        }
        return VehicleMarker.Kind.ARMOR;
    }

    public static void syncGlow() {
        ClientGlowManager.clear();
        for (int id : SELECTED) {
            ClientGlowManager.addEntity(id);
        }
    }

    public static void clearGlow() {
        ClientGlowManager.clear();
    }

    /** Copy selection into SEM's pick-mode snapshot (Move To / Attack that). */
    public static void writeSnapshot() {
        CommanderOverlayRenderer.selectedUnitsSnapshot = new LinkedHashSet<>(SELECTED);
    }

    public static VehicleMarker.Kind kindOf(PmcUnitEntity pmc) {
        Entity vehicle = pmc.getVehicle();
        if (vehicle instanceof VehicleEntity hull) {
            return hullKind(hull);
        }
        return infantryKind(pmc);
    }

    private static List<Entry> scanEntries(double radius) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) return List.of();

        List<Entry> out = new ArrayList<>();
        AABB box = player.getBoundingBox().inflate(radius, radius + 512.0, radius);
        double rSq = radius * radius;
        for (PmcUnitEntity pmc : mc.level.getEntitiesOfClass(PmcUnitEntity.class, box)) {
            if (!pmc.isOwnedBy(player)) continue;
            double dx = pmc.getX() - player.getX();
            double dz = pmc.getZ() - player.getZ();
            if (dx * dx + dz * dz > rSq) continue;
            VehicleMarker marker = MapMarkers.markerForDriver(pmc.getId());
            int vehicleId = -1;
            if (pmc.getVehicle() instanceof VehicleEntity hull) {
                vehicleId = hull.getId();
                // MapMarkers carries one marker per hull, keyed by its driver's id — a non-driver
                // seat (gunner etc.) has none of its own, so fall back to the hull's actual driver.
                if (marker == null && hull.getFirstPassenger() != null) {
                    marker = MapMarkers.markerForDriver(hull.getFirstPassenger().getId());
                }
            }
            int platoonColor = marker != null ? marker.platoonColorRgb() : 0;
            String name = pmc.getCustomName() != null ? pmc.getCustomName().getString() : "";
            out.add(new Entry(pmc.getId(), kindOf(pmc), pmc instanceof PmcCommanderEntity, platoonColor,
                    vehicleId, name));
        }
        // A unit that is already selected stays listed however far away it is. The scan reaches
        // only as far as the client tracks entities, while the map — where the selection is usually
        // made — reaches the whole explored world, so without this the ribbon shows an empty
        // selection for units the player can plainly see marked on the map.
        Set<Integer> present = new HashSet<>();
        for (Entry e : out) {
            present.add(e.id());
        }
        for (int id : SELECTED) {
            if (!present.add(id)) continue;
            out.add(offRangeEntry(id));
        }
        out.sort((a, b) -> Integer.compare(a.id(), b.id()));
        return List.copyOf(out);
    }

    /**
     * Ribbon entry for a selected unit the client has no entity for. {@link MapMarkers} still
     * carries it (the server syncs every own hull regardless of distance), so kind and platoon
     * survive; an infantry marker is its own "vehicle", hence the dismounted {@code -1}.
     */
    private static Entry offRangeEntry(int id) {
        VehicleMarker marker = MapMarkers.markerForDriver(id);
        if (marker == null) {
            return new Entry(id, VehicleMarker.Kind.INFANTRY, false, 0, -1, "");
        }
        int vehicleId = marker.kind().isInfantry() ? -1 : marker.vehicleId();
        return new Entry(id, marker.kind(), marker.isCommanderUnit(), marker.platoonColorRgb(), vehicleId, "");
    }

    private static VehicleMarker.Kind infantryKind(PmcUnitEntity unit) {
        if (unit instanceof PmcCommanderEntity) {
            return VehicleMarker.Kind.INFANTRY_COMMANDER;
        }
        SupportRole role = SupportRole.of(unit);
        if (VehicleTargeting.isMedic(unit) || role == SupportRole.MEDIC) {
            return VehicleMarker.Kind.INFANTRY_MEDIC;
        }
        if (role == SupportRole.COMBAT_ENGINEER) {
            return VehicleMarker.Kind.INFANTRY_COMBAT_ENGINEER;
        }
        if (VehicleTargeting.isEngineer(unit) || role == SupportRole.ENGINEER) {
            return VehicleMarker.Kind.INFANTRY_ENGINEER;
        }
        return VehicleMarker.Kind.INFANTRY;
    }

    /** Client-safe mirror of OwnedVehicleTracker.computeKind — no NBT cache write. */
    private static VehicleMarker.Kind hullKind(VehicleEntity hull) {
        if (hull instanceof DroneEntity) return VehicleMarker.Kind.DRONE;
        EngineType engine = null;
        try {
            engine = hull.computed().getEngineType();
        } catch (Throwable ignored) {
            // computed() can fail on incomplete client data; fall through to armour.
        }
        if (engine == EngineType.SHIP) return VehicleMarker.Kind.SURFACE_COMBATANT;
        if (engine == EngineType.AIRCRAFT) return VehicleMarker.Kind.FIXED_WING;
        if (engine == EngineType.HELICOPTER) return VehicleMarker.Kind.ROTARY_WING;
        if (HullFacts.isArtilleryHull(hull)) return VehicleMarker.Kind.ARTILLERY;
        if (engine == EngineType.FIXED) return VehicleMarker.Kind.EMPLACEMENT;
        if (HullFacts.isMissileSystemHull(hull)) return VehicleMarker.Kind.MISSILE_SYSTEM;
        if (HullFacts.isAntiAirHull(hull)) return VehicleMarker.Kind.ANTI_AIR;
        return HullFacts.isIfvHull(hull) ? VehicleMarker.Kind.MECHANIZED : VehicleMarker.Kind.ARMOR;
    }
}
