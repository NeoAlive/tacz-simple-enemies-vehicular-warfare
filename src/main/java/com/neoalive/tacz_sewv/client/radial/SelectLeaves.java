package com.neoalive.tacz_sewv.client.radial;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.neoalive.tacz_sewv.client.TdtSelection;
import com.neoalive.tacz_sewv.command.quick.QuickCommandRegistry;
import com.neoalive.tacz_sewv.map.VehicleMarker;

/**
 * The wheel's Select category: the old TDT ribbon's group buttons, one leaf per platoon and per
 * vehicle kind among the units {@link TdtSelection#scan} found. Built when the wheel opens. A leaf
 * toggles its whole group (all on if none is, else all off) and the wheel stays open, so several
 * groups can be picked before backing out to give the order. Single units are Live Select's job.
 */
final class SelectLeaves {

    private SelectLeaves() {}

    /** The category, or null when there is nothing to select (the wedge is then omitted). */
    @Nullable
    static WedgeEntry.CategoryEntry category() {
        List<WedgeEntry> leaves = new ArrayList<>();
        for (Map.Entry<Integer, List<TdtSelection.Entry>> e : TdtSelection.byPlatoon().entrySet()) {
            List<TdtSelection.Entry> units = e.getValue();
            if (units.isEmpty()) continue;
            boolean commander = units.stream().anyMatch(TdtSelection.Entry::isCommander);
            leaves.add(new WedgeEntry.PipelineEntry("Platoon x" + TdtSelection.distinctCount(units),
                    commander ? "★" : "⚑",
                    QuickCommandRegistry.SELECT_PLATOON_PREFIX + e.getKey(), e.getKey() & 0xFFFFFF));
        }
        for (Map.Entry<VehicleMarker.Kind, List<TdtSelection.Entry>> e : TdtSelection.byKind().entrySet()) {
            List<TdtSelection.Entry> units = e.getValue();
            if (units.isEmpty()) continue;
            leaves.add(new WedgeEntry.PipelineEntry(kindLabel(e.getKey()) + " x" + TdtSelection.distinctCount(units),
                    kindGlyph(e.getKey()), QuickCommandRegistry.SELECT_KIND_PREFIX + e.getKey().name(),
                    QuickCommandRegistry.SELECT_ACCENT));
        }
        if (leaves.isEmpty()) return null;
        return new WedgeEntry.CategoryEntry("Select", "◉", QuickCommandRegistry.SELECT_ACCENT, leaves);
    }

    /** Toggle the group a Select leaf stands for. */
    static void toggle(String pipelineId) {
        List<TdtSelection.Entry> group = group(pipelineId);
        if (!group.isEmpty()) TdtSelection.toggleGroup(group);
    }

    /** Whether any unit of the leaf's group is selected (drawn as a checked wedge). */
    static boolean checked(String pipelineId) {
        return TdtSelection.anySelected(group(pipelineId));
    }

    private static List<TdtSelection.Entry> group(String pipelineId) {
        try {
            if (pipelineId.startsWith(QuickCommandRegistry.SELECT_PLATOON_PREFIX)) {
                int rgb = Integer.parseInt(pipelineId.substring(QuickCommandRegistry.SELECT_PLATOON_PREFIX.length()));
                return TdtSelection.byPlatoon().getOrDefault(rgb, List.of());
            }
            if (pipelineId.startsWith(QuickCommandRegistry.SELECT_KIND_PREFIX)) {
                VehicleMarker.Kind kind = VehicleMarker.Kind.valueOf(
                        pipelineId.substring(QuickCommandRegistry.SELECT_KIND_PREFIX.length()));
                return TdtSelection.byKind().getOrDefault(kind, List.of());
            }
        } catch (IllegalArgumentException ignored) {
            // Not a Select id — nothing to toggle.
        }
        return List.of();
    }

    private static String kindLabel(VehicleMarker.Kind kind) {
        return switch (kind) {
            case ARMOR -> "Armor";
            case MECHANIZED -> "Mechanized";
            case EMPLACEMENT -> "Emplacement";
            case ROTARY_WING -> "Helicopter";
            case FIXED_WING -> "Aircraft";
            case SURFACE_COMBATANT -> "Boat";
            case MISSILE_SYSTEM -> "Missile";
            case ANTI_AIR -> "Anti-Air";
            case ARTILLERY -> "Artillery";
            case DRONE -> "Drone";
            case INFANTRY -> "Infantry";
            case INFANTRY_MEDIC -> "Medic";
            case INFANTRY_ENGINEER -> "Engineer";
            case INFANTRY_COMBAT_ENGINEER -> "Combat Eng.";
            case INFANTRY_COMMANDER -> "Commander";
            default -> "Other";
        };
    }

    private static String kindGlyph(VehicleMarker.Kind kind) {
        return switch (kind) {
            case ARMOR, MECHANIZED, ANTI_AIR -> "▬";
            case EMPLACEMENT, MISSILE_SYSTEM, ARTILLERY -> "✹";
            case ROTARY_WING, FIXED_WING, DRONE -> "✈";
            case SURFACE_COMBATANT -> "⚓";
            case INFANTRY_MEDIC -> "✚";
            case INFANTRY_ENGINEER, INFANTRY_COMBAT_ENGINEER -> "⚒";
            case INFANTRY_COMMANDER -> "★";
            default -> "☺";
        };
    }
}
