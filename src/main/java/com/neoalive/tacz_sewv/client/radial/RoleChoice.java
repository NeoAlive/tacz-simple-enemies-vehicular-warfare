package com.neoalive.tacz_sewv.client.radial;

import java.util.List;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.command.quick.QuickCommandRegistry;
import com.neoalive.tacz_sewv.entity.ai.support.RoleKit;

/**
 * The role a Search equipment / Swap to leaf carries, stepped with Ctrl+scroll like the patrol radius.
 * Picked again on every wheel open from the units the leaf would reach ({@link #autoPick}), so the leaf
 * never sits on a role they already have. Firing sends the per-kit pipeline id, so the server needs no
 * extra field.
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

    /**
     * Each leaf opens on the first kit, in list order, that not every unit it would reach already
     * holds. Hands are synced to the client, so this needs nothing from the server.
     */
    public static void autoPick() {
        searchIndex = firstMissing(QuickCommandRegistry.ID_QUICK_SEARCH_GEAR);
        swapIndex = firstMissing(QuickCommandRegistry.ID_QUICK_SWAP_ROLE);
    }

    private static int firstMissing(String pipelineId) {
        Minecraft mc = Minecraft.getInstance();
        List<RoleKit.Kit> kits = options(pipelineId);
        if (mc.level == null || kits.isEmpty()) return 0;
        List<PmcUnitEntity> units = new java.util.ArrayList<>();
        for (int id : WheelScope.units(pipelineId, null)) {
            if (mc.level.getEntity(id) instanceof PmcUnitEntity pmc) units.add(pmc);
        }
        if (units.isEmpty()) return 0;
        for (int i = 0; i < kits.size(); i++) {
            RoleKit.Kit kit = kits.get(i);
            Predicate<PmcUnitEntity> has = pmc -> RoleKit.holds(pmc, kit);
            if (!units.stream().allMatch(has)) return i;
        }
        return 0;
    }

    /** Wedge label for a role leaf ("Swap To: Medic"), or the entry's own label. */
    public static String label(WedgeEntry entry) {
        if (entry instanceof WedgeEntry.PipelineEntry leaf && FieldChoice.isChoiceLeaf(leaf.pipelineId())) {
            return FieldChoice.label(leaf.pipelineId());
        }
        if (entry instanceof WedgeEntry.PipelineEntry leaf && isRoleLeaf(leaf.pipelineId())) {
            return I18n.get(QuickCommandRegistry.labelKey(leaf.pipelineId()) + ".picked",
                    I18n.get(current(leaf.pipelineId()).labelKey()));
        }
        return I18n.get(entry.labelKey(), entry.labelArgs());
    }

    public static String icon(WedgeEntry entry) {
        if (entry instanceof WedgeEntry.PipelineEntry leaf && FieldChoice.isChoiceLeaf(leaf.pipelineId())) {
            return FieldChoice.icon(leaf.pipelineId(), entry.icon());
        }
        if (entry instanceof WedgeEntry.PipelineEntry leaf && isRoleLeaf(leaf.pipelineId())) {
            return current(leaf.pipelineId()).glyph;
        }
        return entry.icon();
    }

    /** The id the server registers for the leaf's current role; any other id passes through. */
    public static String concreteId(String pipelineId) {
        if (FieldChoice.isChoiceLeaf(pipelineId)) return FieldChoice.concreteId(pipelineId);
        if (QuickCommandRegistry.ID_QUICK_SEARCH_GEAR.equals(pipelineId)) {
            return QuickCommandRegistry.searchGearId(current(pipelineId));
        }
        if (QuickCommandRegistry.ID_QUICK_SWAP_ROLE.equals(pipelineId)) {
            return QuickCommandRegistry.swapRoleId(current(pipelineId));
        }
        return pipelineId;
    }
}
