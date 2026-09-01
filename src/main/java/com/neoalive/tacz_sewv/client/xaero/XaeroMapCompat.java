package com.neoalive.tacz_sewv.client.xaero;

import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import xaero.map.WorldMap;
import xaero.map.gui.GuiMap;

import com.neoalive.tacz_sewv.client.PreferredPathwaysClient;

/**
 * Soft compat with <b>Xaero's World Map</b>: hangs {@link VehicleMarkerElements},
 * {@link TrenchMarkerElements} and {@link InvasionZoneMarkerElements} on the map's element renderer.
 *
 * <p>No mixin is needed for this half — {@code WorldMap.mapElementRenderHandler} is a public static
 * field and {@code add} is public, the same door Xaero's own waypoint and player-tracker renderers
 * go through. What it cannot be is a setup-time call: Xaero builds that handler in its own
 * {@code SIDED_SETUP} deferred work, and nothing orders that against ours. Registering on the first
 * map screen instead needs no ordering guarantee at all — by then the handler certainly exists.
 *
 * <p>The handler reference itself can be rebuilt when Xaero reloads between worlds. Tracking the
 * instance we hung on (not a boolean) is what lets us re-add after that without stacking duplicates
 * on the same handler — a stuck {@code registered = true} was a way for icons to vanish for the
 * rest of the session after a world switch.
 *
 * <p>This class and {@code MixinGuiMap} are the only two places Xaero types are named, and both are
 * reached only when the mod is present: this one behind {@code ModList.isLoaded} in
 * {@code ClientModEvents}, the mixins behind {@code XaeroMixinPlugin} on
 * {@code tacz_sewv.xaero.mixins.json} (LoadingModList gate — {@code "required": false} alone still
 * warned on a missing target).
 */
public final class XaeroMapCompat {

    /** Forge mod id for Xaero's World Map. Not Minimap ({@code xaerominimap}). */
    public static final String MODID = "xaeroworldmap";

    /** The handler we last called {@code add} on; null means not hung yet. */
    private static Object hungOn;

    private XaeroMapCompat() {}

    public static void register() {
        MinecraftForge.EVENT_BUS.register(XaeroMapCompat.class);
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof GuiMap)) return;
        if (WorldMap.mapElementRenderHandler == null) return; // Xaero failed to load; leave it alone
        if (hungOn == WorldMap.mapElementRenderHandler) return; // already on this handler instance
        WorldMap.mapElementRenderHandler.add(VehicleMarkerElements.INSTANCE);
        WorldMap.mapElementRenderHandler.add(TrenchMarkerElements.INSTANCE);
        WorldMap.mapElementRenderHandler.add(InvasionZoneMarkerElements.INSTANCE);
        WorldMap.mapElementRenderHandler.add(FobMarkerElements.INSTANCE);
        hungOn = WorldMap.mapElementRenderHandler;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        CruisePlot.cancel();
        GuardPlot.cancel();
        PathwayPlot.cancel();
        PreferredPathwaysClient.clear();
        // Force a re-hang if Xaero builds a fresh handler for the next world.
        hungOn = null;
    }
}
