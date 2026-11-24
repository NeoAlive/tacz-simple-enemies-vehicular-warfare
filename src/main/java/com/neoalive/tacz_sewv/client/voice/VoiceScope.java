package com.neoalive.tacz_sewv.client.voice;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.neoalive.tacz_sewv.client.TdtSelection;
import com.neoalive.tacz_sewv.map.VehicleMarker;

/**
 * A spoken scope becomes the selection, the same thing the wheel's Select category does, and the order is
 * then given through {@code WheelScope} like any other. So "tanks hold" leaves the tanks selected, exactly
 * as picking Tanks then Hold on the wheel would, and a following "move there" goes to them.
 */
public final class VoiceScope {

    public static final String ALL = "ALL";
    public static final String SELECTED = "SELECTED";
    public static final String KIND = "KIND:";
    public static final String PLATOON = "PLATOON:";

    private VoiceScope() {}

    /** Select the scope's units. False when the scope names a group with nobody in it. */
    public static boolean apply(@Nullable String scope) {
        if (scope == null || scope.equals(SELECTED)) return true;
        TdtSelection.scan();
        if (scope.equals(ALL)) {
            TdtSelection.selectAll();
            return TdtSelection.selectedCount() > 0;
        }
        Set<Integer> ids = new HashSet<>();
        if (scope.startsWith(KIND)) {
            Set<VehicleMarker.Kind> kinds = new HashSet<>();
            for (String k : scope.substring(KIND.length()).split(",")) {
                try {
                    kinds.add(VehicleMarker.Kind.valueOf(k));
                } catch (IllegalArgumentException ignored) {
                    // A pack table naming a kind this build lacks: that kind matches nobody.
                }
            }
            for (TdtSelection.Entry e : TdtSelection.scanned()) if (kinds.contains(e.kind())) ids.add(e.id());
        } else if (scope.startsWith(PLATOON)) {
            String colour = scope.substring(PLATOON.length());
            for (Map.Entry<Integer, List<TdtSelection.Entry>> e : TdtSelection.byPlatoon().entrySet()) {
                if (!PlatoonColours.nameOf(e.getKey()).equals(colour)) continue;
                for (TdtSelection.Entry u : e.getValue()) ids.add(u.id());
            }
        }
        if (ids.isEmpty()) return false;
        TdtSelection.deselectAll();
        for (int id : ids) TdtSelection.select(id);
        return true;
    }
}
