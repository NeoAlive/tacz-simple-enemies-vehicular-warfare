package com.neoalive.tacz_sewv.client.xaero;

import java.io.InputStream;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.Map;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource.BufferSource;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.Level;
import xaero.map.element.render.ElementReader;
import xaero.map.element.render.ElementRenderInfo;
import xaero.map.element.render.ElementRenderLocation;
import xaero.map.element.render.ElementRenderProvider;
import xaero.map.element.render.ElementRenderer;
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.client.MapMarkers;
import com.neoalive.tacz_sewv.config.ClientConfig;
import com.neoalive.tacz_sewv.crew.CrewFacts;
import com.neoalive.tacz_sewv.map.FactionColors;
import com.neoalive.tacz_sewv.map.VehicleMarker;

/**
 * Crewed vehicles as APP-6 symbols on Xaero's World Map: your own, plus whatever your side has
 * spotted.
 *
 * <p>Written against Xaero's element framework — a renderer, a reader and a provider — rather than
 * by injecting a draw call into the map screen. The framework is what supplies hover detection
 * (which is what makes the icons clickable at all: the hovered element lands in {@code GuiMap.viewed},
 * where {@code MixinGuiMap} picks it up), depth ordering against waypoints and player trackers, and
 * the map's own zoom transform. Extends {@link ElementRenderer}/{@link ElementReader} directly and
 * not Xaero's {@code MapElementRenderer}/{@code MapElementReader} subclasses, whose only addition is
 * a set of deprecated {@code int location} overloads we would have to implement and never call.
 *
 * <p><b>The symbols are black-on-white art tinted by a plain colour multiply.</b> Each texture is
 * black strokes over a white fill on transparency, so {@code setColor} leaves the strokes black
 * (anything × 0 is 0) and turns the fill into the crew's faction colour — no second texture per
 * faction, no shader, and a new symbol is a PNG rather than code. The textures are 256×256 drawn at
 * ~26px, so they are switched to <b>linear filtering</b>; at nearest-neighbour an 8:1 downscale
 * eats the dashed frames that carry half the meaning.
 *
 * <p>Symbols are deliberately drawn <b>upright</b>. APP-6 never rotates a symbol — heading is a
 * separate direction-of-movement marker ({@link Renderer#drawHeading}), drawn as a small triangle
 * pointing along the hull's look direction.
 */
public final class VehicleMarkerElements {

    /** Drawn size of the symbol in map pixels, and the boxes that have to agree with it. */
    private static final int ICON_SIZE = 26;
    /**
     * Source art size. Kept close to the drawn size on purpose: GPU bilinear takes four texels, so
     * far past ~2:1 it steps straight over thin strokes and the dashed frames sparkle. The symbols
     * were authored at 256 and are stored area-averaged down to this, which is a filter that sees
     * every texel — quality the sampler cannot recover at draw time whatever the filter mode.
     */
    private static final int TEXTURE_SIZE = 64;
    private static final int HIT_BOX = 13;
    /** Must clear {@link #HEADING_END} plus outline so Xaero does not clip the tip. */
    private static final int RENDER_BOX = 28;

    /**
     * Look-direction triangle: tip at {@link #HEADING_END} (forward), base at {@link #HEADING_START}
     * (near the icon edge), half-width of the base. End sits proud of the old 19px tip so bearing
     * reads at a glance; outline thickness is measured from APP-6 black strokes (see
     * {@link #headingOutlinePx()}).
     */
    private static final int HEADING_START = 12;
    private static final int HEADING_END = 24;
    private static final int HEADING_HALF_BASE = 4;
    /** Pure black — same channel as APP-6 frame/ring strokes (multiply leaves them at 0). */
    private static final int HEADING_OUTLINE_COLOR = 0xFF000000;

    private static final int BAR_W = 22;
    private static final int BAR_H = 3;
    private static final int BAR_ROW = 6;

    private static final int SELECTION_COLOR = 0xFFFFFFFF;
    private static final int SELECTION_SHADE = 0x60000000;
    /**
     * Fallback heading colour for unknowns when the texture cannot be polled. Prefer
     * {@link #unknownGrey()} — average of non-black greys from the unknown icon.
     */
    private static final int UNKNOWN_COLOR_FALLBACK = 0xFF888888;
    /** Channels at or below this count as black (strokes / dither dots) and are skipped. */
    private static final int UNKNOWN_BLACK_MAX = 40;
    /** Alpha below this is transparent / ignored. */
    private static final int UNKNOWN_ALPHA_MIN = 16;

    private static final Map<VehicleMarker.Kind, ResourceLocation> TEXTURES = new EnumMap<>(VehicleMarker.Kind.class);

    /** Lazy: 0 until first {@link #unknownGrey()} poll. */
    private static int unknownGreyArgb = 0;
    /** Lazy: 0 until first {@link #headingOutlinePx()} measure (−1 = not yet). */
    private static int headingOutlinePx = -1;

    static {
        for (VehicleMarker.Kind kind : VehicleMarker.Kind.values()) {
            TEXTURES.put(kind, ResourceLocation.fromNamespaceAndPath(TaczSewv.MODID, "textures/map/xaeros_icon_" + kind.textureName() + ".png"));
        }
    }

    public static final Renderer INSTANCE = new Renderer();

    private VehicleMarkerElements() {}

    /**
     * Fill colour = the crew's FACTION colour, the same {@code COLOR_RU}/{@code COLOR_US}/
     * {@code COLOR_PMC} the in-world team overlay uses, so the map and the world agree. The allegiance
     * (OWN/FRIENDLY/HOSTILE) no longer drives colour — it only decides whether a marker is selectable
     * (OWN + HOSTILE designate; FRIENDLY is hover-only) —
     * so an ownerless friendly PMC garrison and your own hull are both green, which is correct: both
     * are your side. Public because the order-preview overlay tints its lines the same way.
     */
    public static int factionColor(CrewFacts.Faction faction) {
        return FactionColors.configArgb(faction);
    }

    public static int markerColor(VehicleMarker marker) {
        // Unknown icon is already grey art — white multiply keeps it as authored; heading uses
        // {@link #unknownGrey()} separately so it matches the panel greys without darkening the blit.
        if (marker.kind() == VehicleMarker.Kind.UNKNOWN) return 0xFFFFFFFF;
        return FactionColors.displayArgb(marker.faction(), marker.tintRgb());
    }

    /**
     * Average RGB of opaque non-black greys in {@code xaeros_icon_unknown.png}. Skips the question
     * mark / dither blacks so the look-direction marker matches the panel fill, not a darkened mix.
     */
    public static int unknownGrey() {
        if (unknownGreyArgb != 0) return unknownGreyArgb;
        unknownGreyArgb = pollUnknownGrey();
        return unknownGreyArgb;
    }

    private static int pollUnknownGrey() {
        ResourceLocation loc = TEXTURES.get(VehicleMarker.Kind.UNKNOWN);
        if (loc == null) return UNKNOWN_COLOR_FALLBACK;
        try {
            Resource res = Minecraft.getInstance().getResourceManager().getResource(loc).orElse(null);
            if (res == null) return UNKNOWN_COLOR_FALLBACK;
            try (InputStream in = res.open(); NativeImage img = NativeImage.read(in)) {
                long sumR = 0, sumG = 0, sumB = 0;
                int n = 0;
                int w = img.getWidth(), h = img.getHeight();
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        // NativeImage.getPixelRGBA is ABGR despite the name.
                        int abgr = img.getPixelRGBA(x, y);
                        int a = (abgr >>> 24) & 0xFF;
                        if (a < UNKNOWN_ALPHA_MIN) continue;
                        int b = (abgr >>> 16) & 0xFF;
                        int g = (abgr >>> 8) & 0xFF;
                        int r = abgr & 0xFF;
                        if (r <= UNKNOWN_BLACK_MAX && g <= UNKNOWN_BLACK_MAX && b <= UNKNOWN_BLACK_MAX) {
                            continue;
                        }
                        sumR += r;
                        sumG += g;
                        sumB += b;
                        n++;
                    }
                }
                if (n == 0) return UNKNOWN_COLOR_FALLBACK;
                return 0xFF000000
                        | ((int) (sumR / n) << 16)
                        | ((int) (sumG / n) << 8)
                        | (int) (sumB / n);
            }
        } catch (Exception e) {
            return UNKNOWN_COLOR_FALLBACK;
        }
    }

    /**
     * Outline thickness in map pixels, matched to APP-6 black stroke weight. Scans the armour
     * symbol's mid-row for short black runs (the ring / frame — not solid fills), averages their
     * texel width, then scales by {@code ICON_SIZE / TEXTURE_SIZE}. Floored at 1 so the tip never
     * loses its rim on light map tiles.
     */
    private static int headingOutlinePx() {
        if (headingOutlinePx >= 0) return headingOutlinePx;
        headingOutlinePx = measureIconBlackStrokePx();
        return headingOutlinePx;
    }

    private static int measureIconBlackStrokePx() {
        ResourceLocation loc = TEXTURES.get(VehicleMarker.Kind.ARMOR);
        if (loc == null) return 1;
        try {
            Resource res = Minecraft.getInstance().getResourceManager().getResource(loc).orElse(null);
            if (res == null) return 1;
            try (InputStream in = res.open(); NativeImage img = NativeImage.read(in)) {
                int w = img.getWidth();
                int h = img.getHeight();
                int y = h / 2;
                int runSum = 0;
                int runN = 0;
                int run = 0;
                // Ignore runs longer than ~1/4 of the art — those are fills, not strokes.
                int maxStroke = Math.max(2, w / 4);
                for (int x = 0; x <= w; x++) {
                    boolean black = false;
                    if (x < w) {
                        int abgr = img.getPixelRGBA(x, y);
                        int a = (abgr >>> 24) & 0xFF;
                        int b = (abgr >>> 16) & 0xFF;
                        int g = (abgr >>> 8) & 0xFF;
                        int r = abgr & 0xFF;
                        black = a >= UNKNOWN_ALPHA_MIN
                                && r <= UNKNOWN_BLACK_MAX
                                && g <= UNKNOWN_BLACK_MAX
                                && b <= UNKNOWN_BLACK_MAX;
                    }
                    if (black) {
                        run++;
                    } else if (run > 0) {
                        if (run <= maxStroke) {
                            runSum += run;
                            runN++;
                        }
                        run = 0;
                    }
                }
                if (runN == 0) return 1;
                float texels = runSum / (float) runN;
                int px = Math.round(texels * ICON_SIZE / (float) TEXTURE_SIZE);
                return Math.max(1, px);
            }
        } catch (Exception e) {
            return 1;
        }
    }

    /**
     * Per-frame state the reader needs but is not handed: which dimension's map is on screen.
     * Filled in {@link Renderer#preRender} from Xaero's own render info, the same way Xaero's
     * tracked-player renderer carries its dimension across.
     */
    public static final class Ctx {
        ResourceKey<Level> mapDimension;
    }

    public static final class Renderer extends ElementRenderer<VehicleMarker, Ctx, Renderer> {

        private Renderer() {
            super(new Ctx(), new Provider(), new Reader());
        }

        @Override
        public void preRender(ElementRenderInfo info, BufferSource buffers,
                              MultiTextureRenderTypeRendererProvider provider, boolean shadow) {
            this.context.mapDimension = info.mapDimension;
            RenderSystem.enableBlend();
            // Re-asserted every pass rather than latched: a resource reload rebuilds the texture
            // objects with their metadata's default (nearest), and five binds a frame while a map
            // screen is open is not worth a listener to avoid.
            Minecraft mc = Minecraft.getInstance();
            TEXTURES.values().forEach(texture -> mc.getTextureManager().getTexture(texture).setFilter(true, false));
        }

        @Override
        public void postRender(ElementRenderInfo info, BufferSource buffers,
                               MultiTextureRenderTypeRendererProvider provider, boolean shadow) {
        }

        @Override
        public void renderElementShadow(VehicleMarker marker, boolean hovered, float optionalScale,
                                        double partialX, double partialY, ElementRenderInfo info,
                                        GuiGraphics guiGraphics, BufferSource buffers,
                                        MultiTextureRenderTypeRendererProvider provider) {
            // Nothing: shouldRender refuses the shadow pass entirely.
        }

        @Override
        public boolean renderElement(VehicleMarker marker, boolean hovered, double optionalDepth,
                                     float optionalScale, double partialX, double partialY,
                                     ElementRenderInfo info, GuiGraphics guiGraphics, BufferSource buffers,
                                     MultiTextureRenderTypeRendererProvider provider) {
            PoseStack pose = guiGraphics.pose();
            pose.pushPose();
            pose.translate(partialX, partialY, optionalDepth);
            pose.scale(optionalScale, optionalScale, 1.0F);

            // OWN for orders; HOSTILE for Attack-this-unit designate. FRIENDLY stays hover-only.
            boolean selectable = marker.allegiance() == VehicleMarker.Allegiance.OWN
                    || marker.allegiance() == VehicleMarker.Allegiance.HOSTILE;
            if (hovered || (selectable && MapMarkers.isSelected(marker))) {
                guiGraphics.fill(-HIT_BOX, -HIT_BOX, HIT_BOX, HIT_BOX,
                        MapMarkers.isSelected(marker) ? SELECTION_COLOR : SELECTION_SHADE);
                guiGraphics.fill(-HIT_BOX + 1, -HIT_BOX + 1, HIT_BOX - 1, HIT_BOX - 1, SELECTION_SHADE);
            }

            int color = markerColor(marker);
            int headingColor = marker.kind() == VehicleMarker.Kind.UNKNOWN ? unknownGrey() : color;
            drawHeading(guiGraphics, marker, headingColor);
            if (ClientConfig.MAP_SHOW_ICONS.get()) {
                drawSymbol(guiGraphics, marker, color);
            }
            drawCommandDebug(guiGraphics, marker);
            drawPlayRoleTag(guiGraphics, marker);
            drawPmcVitals(guiGraphics, marker);
            drawPlatoonBar(guiGraphics, marker);
            drawCommanderBadge(guiGraphics, marker);

            pose.popPose();
            return true;
        }

        /**
         * Debug-only battle-group badge at the icon origin: ★ commander, dim · for in-group
         * non-commander (includes deferred election), nothing when not in a group. Optional
         * group id above the icon. Reads wire fields only — never mutates command state.
         */
        private void drawCommandDebug(GuiGraphics guiGraphics, VehicleMarker marker) {
            if (!ClientConfig.MAP_SHOW_COMMAND_DEBUG.get()) return;
            VehicleMarker.CommandRole role = marker.commandRole();
            if (role == VehicleMarker.CommandRole.NONE) return;

            Minecraft mc = Minecraft.getInstance();
            String glyph = role == VehicleMarker.CommandRole.COMMANDER ? "★" : "·";
            int glyphColor = role == VehicleMarker.CommandRole.COMMANDER ? 0xFFFFD700 : 0xFF888888;
            int glyphW = mc.font.width(glyph);
            // Top-right of the hit box — relative to icon origin, clear of the APP-6 art centre.
            guiGraphics.drawString(mc.font, glyph, HIT_BOX - glyphW, -HIT_BOX - 1, glyphColor, false);

            if (marker.groupId() != VehicleMarker.NO_GROUP) {
                String gid = "g" + marker.groupId();
                int gw = mc.font.width(gid);
                guiGraphics.drawString(mc.font, gid, -gw / 2, -HIT_BOX - mc.font.lineHeight - 1,
                        0xFFCCCCCC, false);
            }
        }

        /**
         * Platoon colour bar, centred just above the icon — owner-only (the server already zeroes
         * this field on anything but an {@code OWN} marker), always on, no debug gate. Kept clear of
         * the debug group-id label above it (rarely coexists — that label needs the debug flag on).
         */
        private void drawPlatoonBar(GuiGraphics guiGraphics, VehicleMarker marker) {
            if (marker.platoonColorRgb() == 0) return;
            int color = 0xFF000000 | marker.platoonColorRgb();
            int barLeft = -BAR_W / 2;
            int y = -HIT_BOX - BAR_H - 2;
            guiGraphics.fill(barLeft - 1, y - 1, barLeft + BAR_W + 1, y + BAR_H + 1, 0xFF000000);
            guiGraphics.fill(barLeft, y, barLeft + BAR_W, y + BAR_H, color);
        }

        /**
         * Always-on ★ top-left for a {@code PmcCommanderEntity} — entity identity, not the debug-only
         * "currently elected {@code BattleGroup} commander" star {@link #drawCommandDebug} already
         * draws top-right (a different, narrower, still debug-gated condition kept intentionally
         * separate; see the Commander/Platoon feature notes).
         */
        private void drawCommanderBadge(GuiGraphics guiGraphics, VehicleMarker marker) {
            if (!marker.isCommanderUnit()) return;
            Minecraft mc = Minecraft.getInstance();
            String glyph = "★";
            int color = marker.platoonColorRgb() != 0 ? (0xFF000000 | marker.platoonColorRgb()) : 0xFFFFD700;
            guiGraphics.drawString(mc.font, glyph, -HIT_BOX, -HIT_BOX - 1, color, false);
        }

        /**
         * Stage-4 play element tag (BoF / MNV / …) at bottom-left of the icon — distinct from the
         * commander star (top-right) and vitals (below). Wire field only.
         */
        private void drawPlayRoleTag(GuiGraphics guiGraphics, VehicleMarker marker) {
            if (!ClientConfig.MAP_SHOW_COMMAND_DEBUG.get()) return;
            VehicleMarker.PlayRole playRole = marker.playRole();
            if (playRole == VehicleMarker.PlayRole.NONE) return;

            Minecraft mc = Minecraft.getInstance();
            String tag = playRole.tag;
            int color = playRoleColor(playRole);
            int tw = mc.font.width(tag);
            int x = -HIT_BOX;
            int y = HIT_BOX - mc.font.lineHeight + 1;
            guiGraphics.fill(x - 1, y - 1, x + tw + 1, y + mc.font.lineHeight, 0xC0000000);
            guiGraphics.drawString(mc.font, tag, x, y, color, false);
        }

        private static int playRoleColor(VehicleMarker.PlayRole role) {
            return switch (role) {
                case BASE_OF_FIRE -> 0xFFFFAA00; // amber
                case MANEUVER -> 0xFFFF55FF;     // magenta
                case OVERWATCH -> 0xFFEEEEFF;    // pale
                case RESERVE -> 0xFF88AAFF;      // soft blue
                case HOLD -> 0xFFBBBBBB;         // grey
                case WITHDRAW -> 0xFFFF8855;     // coral
                case NONE -> 0xFFFFFFFF;
            };
        }

        /**
         * Compact ♥ / ⚡ bars under PMC markers only. Independent of faction colouring for the
         * symbol; values come from the live polled hull state on the wire.
         */
        private void drawPmcVitals(GuiGraphics guiGraphics, VehicleMarker marker) {
            // Unknown contacts must not leak class via vitals chrome.
            if (marker.kind() == VehicleMarker.Kind.UNKNOWN) return;
            if (marker.faction() != CrewFacts.Faction.PMC) return;
            // Vehicles only — on-foot PMC markers stay clean.
            if (marker.kind().isInfantry()) return;
            boolean health = ClientConfig.MAP_SHOW_HEALTH_BAR.get();
            boolean energy = ClientConfig.MAP_SHOW_ENERGY_BAR.get()
                    && marker.energyFrac() >= 0.0F;
            if (!health && !energy) return;

            Minecraft mc = Minecraft.getInstance();
            int y = HIT_BOX + 1;
            if (health) {
                drawVitalBar(guiGraphics, mc, "♥", marker.healthFrac(),
                        barColor(marker.healthFrac(), true), y);
                y += BAR_ROW;
            }
            if (energy) {
                drawVitalBar(guiGraphics, mc, "⚡", marker.energyFrac(),
                        barColor(marker.energyFrac(), false), y);
            }
        }

        private void drawVitalBar(GuiGraphics guiGraphics, Minecraft mc, String glyph,
                                  float frac, int fillColor, int y) {
            int glyphW = mc.font.width(glyph);
            int barLeft = -BAR_W / 2 + glyphW / 2;
            int barRight = barLeft + BAR_W;
            guiGraphics.drawString(mc.font, glyph, barLeft - glyphW - 1, y - 1, 0xFFFFFFFF, false);
            guiGraphics.fill(barLeft, y, barRight, y + BAR_H, 0xFF202020);
            int fill = Math.round(BAR_W * Math.max(0.0F, Math.min(1.0F, frac)));
            if (fill > 0) {
                guiGraphics.fill(barLeft, y, barLeft + fill, y + BAR_H, fillColor);
            }
        }

        /** Health: green→yellow→red. Energy: cyan→amber. */
        private static int barColor(float frac, boolean health) {
            if (health) {
                if (frac > 0.5F) return 0xFF55FF55;
                if (frac > 0.25F) return 0xFFFFFF55;
                return 0xFFFF5555;
            }
            if (frac > 0.35F) return 0xFF55FFFF;
            return 0xFFFFAA00;
        }

        /** The APP-6 symbol, tinted: black strokes survive the multiply, the white fill becomes {@code color}. */
        private void drawSymbol(GuiGraphics guiGraphics, VehicleMarker marker, int color) {
            PoseStack pose = guiGraphics.pose();
            pose.pushPose();
            float scale = (float) ICON_SIZE / TEXTURE_SIZE;
            pose.scale(scale, scale, 1.0F);
            guiGraphics.setColor(
                    (color >> 16 & 0xFF) / 255.0F, (color >> 8 & 0xFF) / 255.0F, (color & 0xFF) / 255.0F, 1.0F);
            // The short blit overload hardcodes a 256x256 sheet, so the texture size goes in explicitly.
            guiGraphics.blit(TEXTURES.get(marker.kind()), -TEXTURE_SIZE / 2, -TEXTURE_SIZE / 2, 0,
                    0.0F, 0.0F, TEXTURE_SIZE, TEXTURE_SIZE, TEXTURE_SIZE, TEXTURE_SIZE);
            guiGraphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
            pose.popPose();
        }

        /**
         * Look-direction marker: a triangle pointing along yaw, outlined in the same black as APP-6
         * strokes ({@link #headingOutlinePx()}). Tip sits proud of the icon so bearing reads clearly.
         * Yaw 0 faces south / down on the map — tip drawn "up" locally, then +180°.
         */
        private void drawHeading(GuiGraphics guiGraphics, VehicleMarker marker, int color) {
            PoseStack pose = guiGraphics.pose();
            pose.pushPose();
            pose.mulPose(Axis.ZP.rotationDegrees(marker.yaw() + 180.0F));
            int outline = headingOutlinePx();
            int fill = 0xFF000000 | (color & 0x00FFFFFF);
            // Outline first (expanded), then fill — rim stays visible on light and dark map tiles.
            fillHeadingTriangle(guiGraphics,
                    HEADING_END + outline, Math.max(0, HEADING_START - outline),
                    HEADING_HALF_BASE + outline, HEADING_OUTLINE_COLOR);
            fillHeadingTriangle(guiGraphics, HEADING_END, HEADING_START, HEADING_HALF_BASE, fill);
            pose.popPose();
        }

        /**
         * Tip at {@code (0, -tipDist)}, base from {@code (-halfBase, -baseDist)} to
         * {@code (+halfBase, -baseDist)}. Scanline fill — GuiGraphics has no triangle primitive.
         */
        private static void fillHeadingTriangle(GuiGraphics guiGraphics, int tipDist, int baseDist,
                                                int halfBase, int argb) {
            int yTip = -tipDist;
            int yBase = -baseDist;
            int height = yBase - yTip;
            if (height <= 0 || halfBase <= 0) return;
            for (int y = yTip; y <= yBase; y++) {
                float t = (y - yTip) / (float) height;
                int half = Math.max(y == yTip ? 1 : 0, Math.round(halfBase * t));
                if (half <= 0) continue;
                guiGraphics.fill(-half, y, half + 1, y + 1, argb);
            }
        }

        @Override
        public boolean shouldRender(ElementRenderLocation location, boolean shadow) {
            return !shadow && ClientConfig.mapMarkersEnabled();
        }

        /** Above Xaero's tracked players (200) — your own units are what you opened the map for. */
        @Override
        public int getOrder() {
            return 300;
        }
    }

    private static final class Provider extends ElementRenderProvider<VehicleMarker, Ctx> {
        private Iterator<VehicleMarker> iterator;

        @Override
        public void begin(ElementRenderLocation location, Ctx context) {
            this.iterator = MapMarkers.markers().iterator();
        }

        @Override
        public boolean hasNext(ElementRenderLocation location, Ctx context) {
            return this.iterator != null && this.iterator.hasNext();
        }

        @Override
        public VehicleMarker getNext(ElementRenderLocation location, Ctx context) {
            return this.iterator.next();
        }

        @Override
        public void end(ElementRenderLocation location, Ctx context) {
            this.iterator = null;
        }
    }

    private static final class Reader extends ElementReader<VehicleMarker, Ctx, Renderer> {

        /**
         * Hidden unless the map on screen is the marker's own dimension. Compared against the map's
         * dimension rather than the player's: looking at the Nether map from the Overworld must not
         * scatter Overworld hulls across it at raw coordinates.
         */
        @Override
        public boolean isHidden(VehicleMarker marker, Ctx context) {
            return context.mapDimension != null && !context.mapDimension.equals(marker.dimension());
        }

        @Override
        public double getRenderX(VehicleMarker marker, Ctx context, float partialTicks) {
            return marker.x();
        }

        @Override
        public double getRenderY(VehicleMarker marker, Ctx context, float partialTicks) {
            return marker.y();
        }

        @Override
        public double getRenderZ(VehicleMarker marker, Ctx context, float partialTicks) {
            return marker.z();
        }

        @Override
        public boolean hasYCoordinate() {
            return true;
        }

        @Override
        public boolean isInteractable(ElementRenderLocation location, VehicleMarker marker) {
            return true;
        }

        /**
         * False on purpose: right-clicking a symbol then falls through to the map's own menu, which
         * is where "move the selected units here" lives — the destination is the point of that
         * click, not the symbol. Flip this (and override {@code getRightClickOptions}) to give a
         * single hull its own menu.
         */
        @Override
        public boolean isRightClickValid(VehicleMarker marker) {
            return false;
        }

        @Override
        public int getInteractionBoxLeft(VehicleMarker marker, Ctx context, float partialTicks) { return -HIT_BOX; }

        @Override
        public int getInteractionBoxRight(VehicleMarker marker, Ctx context, float partialTicks) { return HIT_BOX; }

        @Override
        public int getInteractionBoxTop(VehicleMarker marker, Ctx context, float partialTicks) { return -HIT_BOX; }

        @Override
        public int getInteractionBoxBottom(VehicleMarker marker, Ctx context, float partialTicks) { return HIT_BOX; }

        @Override
        public int getRenderBoxLeft(VehicleMarker marker, Ctx context, float partialTicks) { return -RENDER_BOX; }

        @Override
        public int getRenderBoxRight(VehicleMarker marker, Ctx context, float partialTicks) { return RENDER_BOX; }

        @Override
        public int getRenderBoxTop(VehicleMarker marker, Ctx context, float partialTicks) { return -RENDER_BOX; }

        @Override
        public int getRenderBoxBottom(VehicleMarker marker, Ctx context, float partialTicks) {
            // Extra room for PMC ♥/⚡ bars under the symbol.
            return RENDER_BOX + 14;
        }

        @Override
        public int getLeftSideLength(VehicleMarker marker, Minecraft mc) {
            return 9 + mc.font.width(getMenuName(marker));
        }

        @Override
        public String getMenuName(VehicleMarker marker) {
            return marker.kind().name();
        }

        @Override
        public String getFilterName(VehicleMarker marker) {
            return getMenuName(marker);
        }

        @Override
        public int getMenuTextFillLeftPadding(VehicleMarker marker) {
            return 0;
        }

        @Override
        public int getRightClickTitleBackgroundColor(VehicleMarker marker) {
            return markerColor(marker);
        }

        @Override
        public boolean shouldScaleBoxWithOptionalScale() {
            return true;
        }
    }
}
