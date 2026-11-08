package com.neoalive.tacz_sewv.client.radial;

import java.util.List;

import javax.annotation.Nullable;

import com.neoalive.tacz_sewv.command.quick.QuickCommandRegistry;
import com.neoalive.tacz_sewv.entity.ai.support.RoleKit;

/**
 * The role a Search equipment / Swap to leaf carries, stepped with Ctrl+scroll like the patrol radius.
 * Session statics. Firing the leaf sends the per-kit pipeline id, so the server needs no extra field.
 */
public final class RoleChoice {

    private static int searchIndex = 0;
    private static int swapIndex = 0;

    private RoleChoice() {}

    public static boolean isRoleLeaf(String pipelineId) {
        return QuickCommandRegistry.ID_QUICK_SEARCH_GEAR.equals(pipelineId)
                || QuickCommandRegistry.ID_QUICK_SWAP_ROLE.equals(pipelineId);
    }

    @Nullable
    private static List<RoleKit.Kit> kits(String pipelineId) {
        if (QuickCommandRegistry.ID_QUICK_SEARCH_GEAR.equals(pipelineId)) return QuickCommandRegistry.SEARCH_KITS;
        if (QuickCommandRegistry.ID_QUICK_SWAP_ROLE.equals(pipelineId)) return QuickCommandRegistry.SWAP_KITS;
        return null;
    }

    private static int index(String pipelineId) {
        return QuickCommandRegistry.ID_QUICK_SEARCH_GEAR.equals(pipelineId) ? searchIndex : swapIndex;
    }

    /** Wraps at both ends; returns whether anything moved. */
    public static boolean step(String pipelineId, int dir) {
        List<RoleKit.Kit> kits = kits(pipelineId);
        if (kits == null || kits.size() < 2) return false;
        int next = Math.floorMod(index(pipelineId) + Integer.signum(dir), kits.size());
        if (QuickCommandRegistry.ID_QUICK_SEARCH_GEAR.equals(pipelineId)) {
            searchIndex = next;
        } else {
            swapIndex = next;
        }
        return true;
    }

    public static RoleKit.Kit current(String pipelineId) {
        List<RoleKit.Kit> kits = kits(pipelineId);
        if (kits == null) throw new IllegalArgumentException(pipelineId);
        return kits.get(Math.min(index(pipelineId), kits.size() - 1));
    }

    public static List<RoleKit.Kit> options(String pipelineId) {
        List<RoleKit.Kit> kits = kits(pipelineId);
        return kits == null ? List.of() : kits;
    }

    public static float fraction(String pipelineId) {
        int n = options(pipelineId).size();
        return n <= 1 ? 1.0f : index(pipelineId) / (float) (n - 1);
    }

    /** The id the server registers for the leaf's current role; any other id passes through. */
    public static String concreteId(String pipelineId) {
        if (QuickCommandRegistry.ID_QUICK_SEARCH_GEAR.equals(pipelineId)) {
            return QuickCommandRegistry.searchGearId(current(pipelineId));
        }
        if (QuickCommandRegistry.ID_QUICK_SWAP_ROLE.equals(pipelineId)) {
            return QuickCommandRegistry.swapRoleId(current(pipelineId));
        }
        return pipelineId;
    }
}
