package com.neoalive.tacz_sewv.client.editor;

import java.util.List;
import java.util.Map;

import com.neoalive.tacz_sewv.client.editor.PoolPresetApply.Mode;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;
import com.neoalive.tacz_sewv.util.MiscPackPresets;
import com.neoalive.tacz_sewv.util.MiscPackPresets.Tables;
import com.neoalive.tacz_sewv.util.VehiclePackPresets.Pack;
import com.neoalive.tacz_sewv.util.WorldVehicleClasses.CueKind;

/**
 * Applies a misc pack preset to the editor's local cue/armor snapshot. Does not touch SavedData
 * until Save.
 */
public final class MiscPresetApply {

    private MiscPresetApply() {}

    /**
     * Mutates {@code cues} and {@code armor} in place. No-op when mode is {@link Mode#NONE}.
     * Empty curated lists are skipped so Replace does not wipe kinds/factions the pack does not
     * define.
     */
    public static void apply(Mode mode, Pack pack,
                             Map<CueKind, List<String>> cues,
                             Map<TankFaction, List<String>> armor) {
        if (mode == Mode.NONE || cues == null || armor == null) return;
        Tables tables = MiscPackPresets.tables(pack);
        for (CueKind kind : CueKind.values()) {
            List<String> curated = tables.cues().get(kind);
            if (curated.isEmpty()) continue;
            merge(mode, cues.get(kind), curated);
        }
        for (TankFaction faction : TankFaction.values()) {
            List<String> curated = tables.armor().get(faction);
            if (curated.isEmpty()) continue;
            merge(mode, armor.get(faction), curated);
        }
    }

    private static void merge(Mode mode, List<String> dest, List<String> curated) {
        if (mode == Mode.REPLACE) {
            dest.clear();
            dest.addAll(curated);
        } else {
            for (String id : curated) {
                if (!dest.contains(id)) dest.add(id);
            }
        }
    }
}
