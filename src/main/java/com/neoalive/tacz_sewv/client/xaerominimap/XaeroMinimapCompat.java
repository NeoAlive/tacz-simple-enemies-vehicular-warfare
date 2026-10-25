package com.neoalive.tacz_sewv.client.xaerominimap;

import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;
import xaero.common.HudMod;
import xaero.hud.minimap.Minimap;
import xaero.hud.minimap.element.render.MinimapElementRenderLocation;
import xaero.hud.minimap.element.render.MinimapElementRendererHandler;
import xaero.hud.minimap.element.render.map.MinimapElementMapRendererHandler;

import com.neoalive.tacz_sewv.bridge.IMinimapProjectionView;
import com.neoalive.tacz_sewv.client.MapMarkers;
import com.neoalive.tacz_sewv.client.map.MinimapMath;
import com.neoalive.tacz_sewv.config.ClientConfig;

/**
 * Soft compat with <b>Xaero's Minimap</b> (and Better PvP, which ships the same classes): the same
 * markers and passive overlays the World Map shows, drawn into the minimap.
 *
 * <p>Two handlers, two jobs. Markers and overlays go on the <b>in-minimap</b> handler, which draws
 * into the rotation framebuffer before it is masked to the map's circle or square — clipping comes
 * free, and Xaero places each element rotated while drawing it upright. That handler is a private
 * field of {@code MinimapFBORenderer}, built in {@code loadFrameBuffer} right where Xaero adds its own
 * radar, so {@code MixinMinimapFBORenderer} hands it to {@link #bind} from there: the one moment it is
 * guaranteed to exist, with no lazy-registration race. The edge pins go on the public
 * <b>over-minimap</b> handler, which does not clip and pins out-of-range elements to the rim — the one
 * behaviour we want there. Safe mode (no FBO) never builds the in-minimap handler, so nothing is
 * drawn, exactly like Xaero's radar.
 *
 * <p>This class, its package and the {@code MixinMinimap*} mixins are the only places Xaero Minimap
 * types are named, and they are reached only through those mixins, which
 * {@code XaeroMinimapMixinPlugin} refuses when no minimap mod is loaded — so no event-bus
 * registration is needed at all. The pixels live in {@code client/map}, shared with the World Map.
 */
public final class XaeroMinimapCompat {

    /** The in-minimap handler we last hung on; tracked by instance so a rebuild re-hangs once. */
    @Nullable
    private static MinimapElementMapRendererHandler hungOn;
    /** Same for the over-minimap handler, which lives as long as the {@link Minimap} does. */
    @Nullable
    private static MinimapElementRendererHandler overHungOn;

    private XaeroMinimapCompat() {}

    /** Called from {@code MixinMinimapFBORenderer} once the in-minimap handler exists. */
    public static void bind(MinimapElementMapRendererHandler inMap) {
        if (hungOn != inMap) {
            inMap.add(MinimapRenderers.OVERLAY);
            inMap.add(MinimapRenderers.HELIPADS);
            inMap.add(MinimapRenderers.FOB);
            inMap.add(MinimapRenderers.ZONES);
            inMap.add(MinimapRenderers.UNITS);
            hungOn = inMap;
        }
        Minimap minimap = HudMod.INSTANCE == null ? null : HudMod.INSTANCE.getMinimap();
        if (minimap != null && overHungOn != minimap.getOverMapRendererHandler()) {
            minimap.getOverMapRendererHandler().add(MinimapRenderers.EDGE_PINS);
            overHungOn = minimap.getOverMapRendererHandler();
        }
    }

    /** The live in-minimap transform, or null before the framebuffer exists. */
    @Nullable
    static IMinimapProjectionView projectionView() {
        return (IMinimapProjectionView) hungOn;
    }

    /** The minimap's current rotation as a heading offset; 0 before the framebuffer exists. */
    static float bearingOffsetDeg() {
        IMinimapProjectionView view = projectionView();
        return view == null ? 0.0F : MinimapMath.bearingOffsetDeg(view.sewv$ps(), view.sewv$pc());
    }

    /**
     * Whether Xaero's radar should skip this entity because we already draw it: a marked unit or hull,
     * or anyone riding one (a crew would otherwise sit as radar dots under its own tank's symbol). On
     * the minimap only — the World Map's radar wrapper is left alone.
     */
    public static boolean hidesRadarFor(Entity entity, MinimapElementRenderLocation location) {
        if (location != MinimapElementRenderLocation.IN_MINIMAP
                && location != MinimapElementRenderLocation.OVER_MINIMAP) {
            return false;
        }
        if (!ClientConfig.minimapMarkersEnabled() || !ClientConfig.MINIMAP_HIDE_RADAR_FOR_MARKED.get()) {
            return false;
        }
        return MapMarkers.isMarked(entity.getId()) || MapMarkers.isMarked(entity.getRootVehicle().getId());
    }
}
