package com.neoalive.tacz_sewv.client;

import com.atsuishio.superbwarfare.tools.TraceTool;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.init.ModItems;

/**
 * Artillery-indicator zoom/rangefinder on the handheld radio — FOV + meter readout only,
 * no spyglass texture overlay. Hold {@link #ZOOM} with the radio in hand; scroll adjusts
 * magnification (same numbers as SBW's Artillery Indicator).
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID, value = Dist.CLIENT)
public final class RadioZoom {

    private static final String CATEGORY = "key.categories." + TaczSewv.MODID;

    /** Default <b>Z</b>: free of vanilla, SBW, and this mod's other binds. */
    public static final KeyMapping ZOOM = new KeyMapping(
            "key." + TaczSewv.MODID + ".radio_zoom",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_Z,
            CATEGORY);

    private static final double BASE_ZOOM = 4.0;
    private static final double CUSTOM_MIN = -2.0;
    private static final double CUSTOM_MAX = 6.0;
    private static final double RANGE_MAX = 500.0;

    private static double customZoom;
    private static double zoomFactor = 1.0;

    private RadioZoom() {}

    static boolean active(Player player) {
        if (player == null || !ZOOM.isDown()) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.options.getCameraType() != CameraType.FIRST_PERSON) return false;
        return player.getMainHandItem().is(ModItems.HANDHELD_RADIO.get())
                || player.getOffhandItem().is(ModItems.HANDHELD_RADIO.get());
    }

    @SubscribeEvent
    public static void onFov(ViewportEvent.ComputeFov event) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        double target = active(player) ? BASE_ZOOM + customZoom : 1.0;
        float dt = Math.min(mc.getDeltaFrameTime(), 1.6f);
        zoomFactor = Mth.lerp(0.3 * dt, zoomFactor, target);
        if (zoomFactor > 1.001) {
            event.setFOV(event.getFOV() / zoomFactor);
        } else {
            zoomFactor = 1.0;
            customZoom = 0.0;
        }
    }

    @SubscribeEvent
    public static void onScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (!active(mc.player)) return;
        customZoom = Mth.clamp(customZoom + 0.4 * event.getScrollDelta(), CUSTOM_MIN, CUSTOM_MAX);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (!active(player) || mc.options.hideGui || mc.level == null) return;

        Vec3 eye = player.getEyePosition(1f);
        HitResult blockHit = player.level().clip(new ClipContext(
                eye, eye.add(player.getViewVector(1f).scale(512.0)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        double blockRange = eye.distanceTo(blockHit.getLocation());

        Entity looking = TraceTool.findLookingEntity(player, 520.0);
        Component line;
        if (looking != null) {
            String meters = String.format(java.util.Locale.ROOT, "%.1fM ", player.distanceTo(looking));
            line = Component.translatable("message.tacz_sewv.radio.range")
                    .append(Component.literal(meters + looking.getDisplayName().getString()));
        } else if (blockRange > RANGE_MAX) {
            line = Component.translatable("message.tacz_sewv.radio.range")
                    .append(Component.literal("---M"));
        } else {
            line = Component.translatable("message.tacz_sewv.radio.range")
                    .append(Component.literal(String.format(java.util.Locale.ROOT, "%.1fM", blockRange)));
        }

        GuiGraphics g = event.getGuiGraphics();
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        g.drawString(mc.font, line, sw / 2 + 12, sh / 2 - 28, 0xFFFFFFFF, false);
    }
}
