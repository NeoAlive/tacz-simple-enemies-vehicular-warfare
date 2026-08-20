package com.neoalive.tacz_sewv.client;

import com.atsuishio.superbwarfare.client.RenderHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.neoalive.tacz_sewv.TaczSewv;

/**
 * Reinvokes SBW's own artillery-indicator ring widget ({@code RenderHelper.renderCircularRing}) for
 * this mod's revival channels — {@code PlayerReviveGoal} (a PMC reviving a downed player),
 * {@code PmcReviveGoal} (a medic PMC reviving a downed squadmate — shown to that squadmate's owning
 * player), and {@code PmcDownedSupport}'s own hold-to-revive channel (a player reviving a downed
 * PMC). SBW's own overlay, {@code SpyglassRangeOverlay}, is hard-gated at the bytecode level to only
 * draw while holding the literal Artillery Indicator item and using/scoping it — reusing that class
 * would need a mixin to relax that guard. {@code RenderHelper.renderCircularRing} itself is a plain,
 * unbound public static utility with no such coupling, so this calls it directly instead, driven by
 * whatever {@link #accept} was last told, with none of SBW's own item/state gating.
 *
 * <p><b>Colors and radii are copied verbatim from SBW's own call site</b> (read out of
 * {@code SpyglassRangeOverlay}'s bytecode directly, not guessed): a black track
 * {@code (0,0,0, 0.4·t)} and a white fill {@code (1,1,1, 0.8·t)}, where {@code t} is a fade-in
 * factor ramping 0→1 as progress climbs 0→0.25 and holding at 1.0 past that
 * ({@code clamp(progress·20, 0, 5) · 0.2}) — reproduced here exactly so a freshly-started channel
 * doesn't pop the ring in at full opacity the way an unfaded copy would. {@code outerRadius}/
 * {@code innerRadius} (0.07f/0.052f) are the same normalized units SBW's call uses.
 *
 * <p><b>Stale-safety:</b> {@link #accept} resets a tick counter every time a fresh update arrives;
 * if none has arrived for {@link #STALE_TIMEOUT_TICKS}, the ring stops rendering even though
 * {@code active} was never explicitly cleared. This is a client-side backstop for a server-side
 * cleanup that might not fire — e.g. the reviving unit/player dying doesn't reliably route through a
 * goal's {@code stop()} on every removal path — rather than something relied on in the normal case.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID, value = Dist.CLIENT)
public final class RevivalRingOverlay {

    private static final float OUTER_RADIUS = 0.07F;
    private static final float INNER_RADIUS = 0.052F;
    private static final float[] TRACK_RGB = {0.0F, 0.0F, 0.0F};
    private static final float TRACK_ALPHA = 0.4F;
    private static final float[] FILL_RGB = {1.0F, 1.0F, 1.0F};
    private static final float FILL_ALPHA = 0.8F;

    /** ~2 seconds at 20 ticks/s — comfortably above any normal update cadence (every tick to every
     * few ticks depending on the channel), short enough to self-heal promptly if something got stuck. */
    private static final int STALE_TIMEOUT_TICKS = 40;

    private static volatile float progress;
    private static volatile boolean active;
    private static volatile int ticksSinceUpdate = Integer.MAX_VALUE;

    private RevivalRingOverlay() {}

    /** Called from {@code PacketReviveProgress} on receipt. */
    public static void accept(float progress, boolean active) {
        RevivalRingOverlay.progress = progress;
        RevivalRingOverlay.active = active;
        RevivalRingOverlay.ticksSinceUpdate = 0;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (ticksSinceUpdate < Integer.MAX_VALUE) ticksSinceUpdate++;
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (!active || ticksSinceUpdate > STALE_TIMEOUT_TICKS) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null) return;

        // Same fade-in SBW itself applies (SpyglassRangeOverlay), so a freshly-started channel
        // doesn't pop the ring in at full opacity.
        float t = Mth.clamp(progress * 20.0F, 0.0F, 5.0F) * 0.2F;
        float[] track = {TRACK_RGB[0], TRACK_RGB[1], TRACK_RGB[2], TRACK_ALPHA * t};
        float[] fill = {FILL_RGB[0], FILL_RGB[1], FILL_RGB[2], FILL_ALPHA * t};

        GuiGraphics g = event.getGuiGraphics();
        float centerX = mc.getWindow().getGuiScaledWidth() / 2.0F;
        float centerY = mc.getWindow().getGuiScaledHeight() / 2.0F;
        RenderHelper.renderCircularRing(g, centerX, centerY, OUTER_RADIUS, INNER_RADIUS,
                track, fill, progress, true);
    }
}
