package com.neoalive.tacz_sewv.client.xaero;

import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import xaero.map.WorldMap;
import xaero.map.gui.GuiMap;

/**
 * Soft compat with <b>Xaero's World Map</b>: hangs {@link VehicleMarkerElements} on the map's
 * element renderer so PMC vehicles draw as icons.
 *
 * <p>No mixin is needed for this half — {@code WorldMap.mapElementRenderHandler} is a public static
 * field and {@code add} is public, the same door Xaero's own waypoint and player-tracker renderers
 * go through. What it cannot be is a setup-time call: Xaero builds that handler in its own
 * {@code SIDED_SETUP} deferred work, and nothing orders that against ours. Registering on the first
 * map screen instead needs no ordering guarantee at all — by then the handler certainly exists.
 *
 * <p>This class and {@code MixinGuiMap} are the only two places Xaero types are named, and both are
 * reached only when the mod is present: this one behind {@code ModList.isLoaded} in
 * {@code ClientModEvents}, the mixin behind its own non-required config.
 */
public final class XaeroMapCompat {

    public static final String MODID = "xaeroworldmap";

    private static boolean registered;

    private XaeroMapCompat() {}

    public static void register() {
        MinecraftForge.EVENT_BUS.register(XaeroMapCompat.class);
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (registered || !(event.getScreen() instanceof GuiMap)) return;
        if (WorldMap.mapElementRenderHandler == null) return; // Xaero failed to load; leave it alone
        WorldMap.mapElementRenderHandler.add(VehicleMarkerElements.INSTANCE);
        registered = true;
    }
}
