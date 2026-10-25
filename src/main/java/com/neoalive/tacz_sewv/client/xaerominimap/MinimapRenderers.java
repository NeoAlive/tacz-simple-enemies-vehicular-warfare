package com.neoalive.tacz_sewv.client.xaerominimap;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import xaero.hud.minimap.element.render.MinimapElementRenderLocation;

import com.neoalive.tacz_sewv.bridge.IMinimapProjectionView;
import com.neoalive.tacz_sewv.client.MapMarkers;
import com.neoalive.tacz_sewv.client.invasion.InvasionHudClient;
import com.neoalive.tacz_sewv.client.map.MapOverlayPainter;
import com.neoalive.tacz_sewv.client.map.MarkerMotion;
import com.neoalive.tacz_sewv.client.map.MinimapMath;
import com.neoalive.tacz_sewv.client.map.RotatedMapProjection;
import com.neoalive.tacz_sewv.client.map.SiteMarkerPainter;
import com.neoalive.tacz_sewv.client.map.VehicleMarkerPainter;
import com.neoalive.tacz_sewv.client.map.ZoneMarkerPainter;
import com.neoalive.tacz_sewv.client.xaerominimap.MinimapMarkerRenderer.Ctx;
import com.neoalive.tacz_sewv.client.xaerominimap.MinimapMarkerRenderer.Spec;
import com.neoalive.tacz_sewv.config.ClientConfig;
import com.neoalive.tacz_sewv.map.FobMarker;
import com.neoalive.tacz_sewv.map.HelipadMarker;
import com.neoalive.tacz_sewv.map.InvasionZoneMarker;
import com.neoalive.tacz_sewv.map.VehicleMarker;

/**
 * Everything this mod draws on the World Map, as minimap renderers. Orders mirror the World Map's
 * (overlays under sites under zones under units) so the two stack the same way.
 */
final class MinimapRenderers {

    private static final MinimapElementRenderLocation IN = MinimapElementRenderLocation.IN_MINIMAP;

    private MinimapRenderers() {}

    /** Passive overlays: one sentinel element at the map centre whose paint draws every line. */
    static final MinimapMarkerRenderer<Anchor> OVERLAY = new MinimapMarkerRenderer<>(
            new Spec<Anchor>(IN, 240, 0) {
                @Override
                Iterable<Anchor> elements() {
                    return List.of(Anchor.INSTANCE);
                }

                @Override
                ResourceKey<Level> dimension(Anchor element) {
                    return null; // never hidden: the overlays filter by dimension themselves
                }

                @Override
                double x(Anchor element, Ctx ctx) {
                    return ctx.renderPos.x;
                }

                @Override
                double y(Anchor element, Ctx ctx) {
                    return ctx.renderPos.y;
                }

                @Override
                double z(Anchor element, Ctx ctx) {
                    return ctx.renderPos.z;
                }

                @Override
                boolean paint(GuiGraphics guiGraphics, Anchor element, boolean outOfBounds, Ctx ctx) {
                    IMinimapProjectionView view = XaeroMinimapCompat.projectionView();
                    if (view == null || ctx.mapDimension == null) return false;
                    RotatedMapProjection project = new RotatedMapProjection(view.sewv$ps(), view.sewv$pc(),
                            view.sewv$zoom(), ctx.renderPos.x, ctx.renderPos.z, view.sewv$halfView());
                    MapOverlayPainter.drawAll(guiGraphics, project, ctx.mapDimension, true);
                    return true;
                }

                @Override
                String name(Anchor element) {
                    return "Overlays";
                }

                @Override
                boolean enabled() {
                    return ClientConfig.MINIMAP_SHOW_OVERLAYS.get();
                }
            });

    static final MinimapMarkerRenderer<HelipadMarker> HELIPADS = new MinimapMarkerRenderer<>(
            new Spec<HelipadMarker>(IN, 250, SiteMarkerPainter.RENDER_BOX) {
                @Override
                Iterable<HelipadMarker> elements() {
                    return MapMarkers.helipads();
                }

                @Override
                ResourceKey<Level> dimension(HelipadMarker element) {
                    return element.dimension();
                }

                @Override
                double x(HelipadMarker element, Ctx ctx) {
                    return element.x();
                }

                @Override
                double y(HelipadMarker element, Ctx ctx) {
                    return element.y();
                }

                @Override
                double z(HelipadMarker element, Ctx ctx) {
                    return element.z();
                }

                @Override
                boolean paint(GuiGraphics guiGraphics, HelipadMarker element, boolean outOfBounds, Ctx ctx) {
                    if (outOfBounds) return false;
                    SiteMarkerPainter.paintHelipad(guiGraphics, false, ctx.style);
                    return true;
                }

                @Override
                String name(HelipadMarker element) {
                    return "Helipad";
                }

                @Override
                void prepare() {
                    SiteMarkerPainter.prepareTextures();
                }
            });

    static final MinimapMarkerRenderer<FobMarker> FOB = new MinimapMarkerRenderer<>(
            new Spec<FobMarker>(IN, 251, SiteMarkerPainter.RENDER_BOX) {
                @Override
                Iterable<FobMarker> elements() {
                    FobMarker marker = MapMarkers.fobMarker();
                    return marker == null ? List.of() : List.of(marker);
                }

                @Override
                ResourceKey<Level> dimension(FobMarker element) {
                    return element.dimension();
                }

                @Override
                double x(FobMarker element, Ctx ctx) {
                    return element.x();
                }

                @Override
                double y(FobMarker element, Ctx ctx) {
                    return element.y();
                }

                @Override
                double z(FobMarker element, Ctx ctx) {
                    return element.z();
                }

                @Override
                boolean paint(GuiGraphics guiGraphics, FobMarker element, boolean outOfBounds, Ctx ctx) {
                    if (outOfBounds) return false;
                    SiteMarkerPainter.paintFob(guiGraphics, element, false, ctx.style);
                    return true;
                }

                @Override
                String name(FobMarker element) {
                    return "FOB";
                }

                @Override
                void prepare() {
                    SiteMarkerPainter.prepareTextures();
                }
            });

    static final MinimapMarkerRenderer<InvasionZoneMarker> ZONES = new MinimapMarkerRenderer<>(
            new Spec<InvasionZoneMarker>(IN, 295, ZoneMarkerPainter.RENDER_BOX) {
                @Override
                Iterable<InvasionZoneMarker> elements() {
                    return ZoneMarkerPainter.markers();
                }

                /** Zones are only ever for the level the player is in, as on the World Map. */
                @Override
                ResourceKey<Level> dimension(InvasionZoneMarker element) {
                    Minecraft mc = Minecraft.getInstance();
                    return mc.level == null ? null : mc.level.dimension();
                }

                @Override
                double x(InvasionZoneMarker element, Ctx ctx) {
                    return element.x();
                }

                @Override
                double y(InvasionZoneMarker element, Ctx ctx) {
                    return element.y();
                }

                @Override
                double z(InvasionZoneMarker element, Ctx ctx) {
                    return element.z();
                }

                @Override
                boolean paint(GuiGraphics guiGraphics, InvasionZoneMarker element, boolean outOfBounds, Ctx ctx) {
                    if (outOfBounds) return false;
                    ZoneMarkerPainter.paint(guiGraphics, element, false, ctx.style);
                    return true;
                }

                @Override
                String name(InvasionZoneMarker element) {
                    return element.isBase() ? "Team Base" : "Capture Point";
                }

                @Override
                boolean enabled() {
                    return InvasionHudClient.isActive();
                }
            });

    static final MinimapMarkerRenderer<VehicleMarker> UNITS = new MinimapMarkerRenderer<>(
            new Spec<VehicleMarker>(IN, 300, VehicleMarkerPainter.RENDER_BOX) {
                @Override
                Iterable<VehicleMarker> elements() {
                    return MapMarkers.markers();
                }

                @Override
                ResourceKey<Level> dimension(VehicleMarker element) {
                    return element.dimension();
                }

                @Override
                double x(VehicleMarker element, Ctx ctx) {
                    return MarkerMotion.x(element, ctx.partialTicks);
                }

                @Override
                double y(VehicleMarker element, Ctx ctx) {
                    return MarkerMotion.y(element, ctx.partialTicks);
                }

                @Override
                double z(VehicleMarker element, Ctx ctx) {
                    return MarkerMotion.z(element, ctx.partialTicks);
                }

                @Override
                boolean paint(GuiGraphics guiGraphics, VehicleMarker element, boolean outOfBounds, Ctx ctx) {
                    if (outOfBounds) return false;
                    VehicleMarkerPainter.paint(guiGraphics, element, MarkerMotion.yaw(element, ctx.partialTicks),
                            false, ctx.style, ctx.bearingOffsetDeg);
                    return true;
                }

                @Override
                String name(VehicleMarker element) {
                    return element.kind().name();
                }

                @Override
                void prepare() {
                    VehicleMarkerPainter.prepareTextures();
                }
            });

    /**
     * Your own units past the rim, drawn over the minimap where Xaero pins out-of-range elements.
     * Paints only when {@code outOfBounds}, so a unit on the map is never drawn twice.
     */
    static final MinimapMarkerRenderer<VehicleMarker> EDGE_PINS = new MinimapMarkerRenderer<>(
            new Spec<VehicleMarker>(MinimapElementRenderLocation.OVER_MINIMAP, 300, 8) {
                @Override
                Iterable<VehicleMarker> elements() {
                    List<VehicleMarker> own = new ArrayList<>();
                    for (VehicleMarker marker : MapMarkers.markers()) {
                        if (marker.allegiance() == VehicleMarker.Allegiance.OWN) own.add(marker);
                    }
                    return own;
                }

                @Override
                ResourceKey<Level> dimension(VehicleMarker element) {
                    return element.dimension();
                }

                @Override
                double x(VehicleMarker element, Ctx ctx) {
                    return MarkerMotion.x(element, ctx.partialTicks);
                }

                @Override
                double y(VehicleMarker element, Ctx ctx) {
                    return MarkerMotion.y(element, ctx.partialTicks);
                }

                @Override
                double z(VehicleMarker element, Ctx ctx) {
                    return MarkerMotion.z(element, ctx.partialTicks);
                }

                @Override
                boolean paint(GuiGraphics guiGraphics, VehicleMarker element, boolean outOfBounds, Ctx ctx) {
                    if (!outOfBounds) return false;
                    IMinimapProjectionView view = XaeroMinimapCompat.projectionView();
                    if (view == null) return false;
                    // The pin already sits on the rim; it points along the same bearing Xaero
                    // pinned it on, which is the rotated world offset (zoom does not change a bearing).
                    double dx = x(element, ctx) - ctx.renderPos.x;
                    double dz = z(element, ctx) - ctx.renderPos.z;
                    double sx = MinimapMath.projectX(view.sewv$ps(), view.sewv$pc(), 1.0, dx, dz);
                    double sy = MinimapMath.projectY(view.sewv$ps(), view.sewv$pc(), 1.0, dx, dz);
                    VehicleMarkerPainter.paintEdgePip(guiGraphics, element, sx, sy, ctx.style.scale());
                    return true;
                }

                @Override
                String name(VehicleMarker element) {
                    return element.kind().name();
                }

                @Override
                boolean enabled() {
                    return ClientConfig.MINIMAP_EDGE_PINS.get();
                }
            });

    /** The overlay renderer's single element: it only exists to be placed at the map centre. */
    enum Anchor { INSTANCE }
}
