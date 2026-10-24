package com.neoalive.tacz_sewv.client.radial;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.client.TdtSelection;
import com.neoalive.tacz_sewv.client.territory.TerritoryClient;
import com.neoalive.tacz_sewv.command.quick.QuickCommandRegistry;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.invasion.PmcOwnerSupport;

/**
 * Which units a wheel order goes to — the one rule every order shares.
 *
 * <p><b>The selection overrides proximity.</b> With anything selected (A / Live Select / the Select
 * category), an order goes to exactly the selection, at any range, the way the TDT ribbon did, and
 * the server waives its range re-check for it ({@code PacketQuickCommand.explicit}). With nothing
 * selected it goes to the owned units near the player, the wheel's original behaviour.
 *
 * <p>SEM's pick snapshot ({@code CommanderOverlayRenderer.selectedUnitsSnapshot}) is deliberately
 * <b>not</b> a selection here. The wheel writes it itself to hand Move To / Attack That to SEM's
 * pick, and SEM clears it only when a pick resolves, so a cancelled pick would otherwise leave a
 * stale snapshot silently redirecting every later order.
 */
public final class WheelScope {

    private WheelScope() {}

    public static boolean explicit() {
        return !TdtSelection.selected().isEmpty();
    }

    /** {@link #units(boolean, double, Predicate)} with the leaf's own on-foot rule and radius. */
    public static List<Integer> units(String pipelineId, @Nullable Predicate<PmcUnitEntity> filter) {
        return units(QuickCommandRegistry.requiresOnFoot(pipelineId), nearbyRadius(pipelineId), filter);
    }

    /**
     * @param filter a client-side test that needs the entity, or null for none. A selected unit the
     *        client does not track (selected on the map, out of view range) cannot be tested, so it
     *        is passed on only when there is nothing to test: the server re-checks ownership and
     *        resolves it if it is loaded there.
     */
    public static List<Integer> units(boolean onFootOnly, double nearbyRadius,
                                      @Nullable Predicate<PmcUnitEntity> filter) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) return List.of();
        List<Integer> out = new ArrayList<>();
        if (explicit()) {
            boolean blind = filter == null && !onFootOnly;
            for (int id : TdtSelection.selected()) {
                if (TerritoryClient.inTerritory(id)) continue;
                Entity e = mc.level.getEntity(id);
                if (e == null) {
                    if (blind) out.add(id);
                    continue;
                }
                if (e instanceof PmcUnitEntity pmc && accepts(player, pmc, onFootOnly, filter)) out.add(id);
            }
            return out;
        }
        AABB box = player.getBoundingBox().inflate(nearbyRadius);
        for (PmcUnitEntity pmc : mc.level.getEntitiesOfClass(PmcUnitEntity.class, box,
                u -> !TerritoryClient.inTerritory(u.getId()) && accepts(player, u, onFootOnly, filter))) {
            out.add(pmc.getId());
        }
        return out;
    }

    private static boolean accepts(Player player, PmcUnitEntity pmc, boolean onFootOnly,
                                   @Nullable Predicate<PmcUnitEntity> filter) {
        return pmc.isAlive()
                && PmcOwnerSupport.isOwner(player, pmc)
                && (!onFootOnly || pmc.getVehicle() == null)
                && (filter == null || filter.test(pmc));
    }

    /** How far "nearby" reaches for a leaf; Evacuation keeps its own board radius. */
    public static double nearbyRadius(String pipelineId) {
        return QuickCommandRegistry.ID_QUICK_EVAC.equals(pipelineId)
                ? SewvConfig.QUICK_EVAC_BOARD_RADIUS.get()
                : SewvConfig.QUICK_LAND_RADIUS.get();
    }

    /** The radius for orders that are not wheel pipelines (escort, bail out, ...). */
    public static double nearbyRadius() {
        return SewvConfig.QUICK_LAND_RADIUS.get();
    }

    /** The empty-result hint: "nobody matched" with a selection, "nobody near" without. */
    public static String emptyKey(String matchedNobodyKey) {
        return explicit() ? matchedNobodyKey : "message.tacz_sewv.quick_command.need_units";
    }
}
