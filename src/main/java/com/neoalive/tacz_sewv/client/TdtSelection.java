package com.neoalive.tacz_sewv.client;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
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
import com.neoalive.tacz_sewv.map.VehicleMarker;

/**
 * Client-side unit selection for the Tactical Data Terminal ribbon. Scan matches
 * {@link BoardKeybind}'s owned-PMC cylinder; {@link #resolve} prefers an explicit ribbon
 * selection, then SEM's pick-mode snapshot, then every owned unit in range.
 */
public final class TdtSelection {

    public static final double SCAN_RADIUS = 512.0;

    public record Entry(int id, VehicleMarker.Kind kind) {}

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

    /** Refresh the nearby owned-PMC list. Prunes selection to ids still present. */
    public static void scan() {
        scanned = scanEntries(SCAN_RADIUS);
        Set<Integer> alive = new HashSet<>();
        for (Entry e : scanned) {
            alive.add(e.id());
        }
        SELECTED.retainAll(alive);
        syncGlow();
    }

    /**
     * Unit ids an order should hit: ribbon selection if any, else SEM pick snapshot if any,
     * else every owned PMC in the scan cylinder.
     */
    public static List<Integer> resolve(double radius) {
        if (!SELECTED.isEmpty()) {
            return List.copyOf(SELECTED);
        }
        Set<Integer> snap = CommanderOverlayRenderer.selectedUnitsSnapshot;
        if (snap != null && !snap.isEmpty()) {
            return new ArrayList<>(snap);
        }
        List<Integer> ids = new ArrayList<>();
        for (Entry e : scanEntries(radius)) {
            ids.add(e.id());
        }
        return ids;
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

    public static Map<VehicleMarker.Kind, List<Entry>> byKind() {
        Map<VehicleMarker.Kind, List<Entry>> map = new EnumMap<>(VehicleMarker.Kind.class);
        for (Entry e : scanned) {
            map.computeIfAbsent(e.kind(), k -> new ArrayList<>()).add(e);
        }
        return map;
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
            out.add(new Entry(pmc.getId(), kindOf(pmc)));
        }
        out.sort((a, b) -> Integer.compare(a.id(), b.id()));
        return List.copyOf(out);
    }

    private static VehicleMarker.Kind infantryKind(PmcUnitEntity unit) {
        if (VehicleTargeting.isMedic(unit) || SupportRole.of(unit) == SupportRole.MEDIC) {
            return VehicleMarker.Kind.INFANTRY_MEDIC;
        }
        if (VehicleTargeting.isEngineer(unit) || SupportRole.of(unit) == SupportRole.ENGINEER
                || SupportRole.of(unit) == SupportRole.COMBAT_ENGINEER) {
            return VehicleMarker.Kind.INFANTRY_ENGINEER;
        }
        return VehicleMarker.Kind.INFANTRY;
    }

    /** Client-safe mirror of OwnedVehicleTracker.computeKind — no NBT cache write. */
    private static VehicleMarker.Kind hullKind(VehicleEntity hull) {
        if (hull instanceof DroneEntity) return VehicleMarker.Kind.ROTARY_WING;
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
