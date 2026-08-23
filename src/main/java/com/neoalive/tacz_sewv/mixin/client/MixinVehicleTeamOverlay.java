package com.neoalive.tacz_sewv.mixin.client;

import com.atsuishio.superbwarfare.client.overlay.OverlayTraceHandler;
import com.atsuishio.superbwarfare.client.overlay.VehicleTeamOverlay;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import com.neoalive.tacz_sewv.client.ClientGameRules;
import com.neoalive.tacz_sewv.client.HeliRunPhaseClient;
import com.neoalive.tacz_sewv.client.MapMarkers;
import com.neoalive.tacz_sewv.client.invasion.InvasionHudClient;
import com.neoalive.tacz_sewv.config.ClientConfig;
import com.neoalive.tacz_sewv.crew.CrewFacts;
import com.neoalive.tacz_sewv.entity.ai.goal.DriveHelicopterGoal;
import com.neoalive.tacz_sewv.init.ModGameRules;
import com.neoalive.tacz_sewv.map.FactionColors;
import com.neoalive.tacz_sewv.map.VehicleMarker;

/**
 * Colours SuperbWarfare's hover overlay by whoever is crewing the vehicle, so an enemy tank
 * reads as an enemy tank at a glance.
 *
 * <p>SuperbWarfare draws that overlay in one colour taken from the driver's <b>scoreboard team</b>
 * ({@code Player.getTeamColor()}), reached through a branch that requires
 * {@code getFirstPassenger() is Player}. An SEM unit is not a Player, so every AI-crewed hull in
 * the game falls through to the default white — the one case where the overlay would be most
 * useful is the one it cannot speak to.
 *
 * <p>Rather than reimplement the overlay, both colour arguments are intercepted where they are
 * passed: the name/range text through {@code GuiGraphics.drawString} and the health bar through
 * {@code RenderHelper.fill}. Two {@code @ModifyArg}s, no layout code, and SuperbWarfare keeps
 * ownership of everything about how the thing looks.
 *
 * <p>Also appends the AI helicopter firing-run phase to the name line when enabled — phase is
 * synced via {@code PacketHeliRunPhase} because Forge persistent NBT is not client-visible.
 */
@Mixin(value = VehicleTeamOverlay.class, remap = false)
public abstract class MixinVehicleTeamOverlay {

    @Unique
    private static final int TACZ_SEWV$BAR_BACKGROUND = 0x80000000;

    /**
     * The vehicle under the crosshair. 0.8.9.1 moved the per-tick trace out of this object's own
     * static field into {@link OverlayTraceHandler#cameraEntity} (a Kotlin {@code @JvmField}, so a
     * plain static read — no shadow needed, and none possible: the field no longer exists here).
     */
    private static VehicleEntity tacz_sewv$lookingVehicle() {
        return OverlayTraceHandler.cameraEntity instanceof VehicleEntity v ? v : null;
    }

    @ModifyArg(
            method = "render(Lcom/atsuishio/superbwarfare/client/overlay/RenderContext;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)I",
                    remap = true),
            index = 1)
    private Component tacz_sewv$appendHeliPhase(Component text) {
        if (!ClientConfig.HELI_SHOW_RUN_PHASE.get() && !ClientGameRules.get(ModGameRules.HELI_COMBAT_DEBUG)) {
            return text;
        }
        VehicleEntity vehicle = tacz_sewv$lookingVehicle();
        if (vehicle == null) return text;
        DriveHelicopterGoal.RunPhase phase = HeliRunPhaseClient.get(vehicle.getId());
        if (phase == null || phase == DriveHelicopterGoal.RunPhase.IDLE) return text;
        return text.copy().append(Component.literal(" [" + phase.name() + "]"));
    }

    @ModifyArg(
            method = "render(Lcom/atsuishio/superbwarfare/client/overlay/RenderContext;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)I",
                    remap = true),
            index = 4)
    private int tacz_sewv$colorText(int color) {
        Integer faction = tacz_sewv$factionColor();
        return faction == null ? color : faction;
    }

    @ModifyArg(
            method = "render(Lcom/atsuishio/superbwarfare/client/overlay/RenderContext;)V",
            at = @At(value = "INVOKE",
                    target = "Lcom/atsuishio/superbwarfare/client/RenderHelper;fill(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/renderer/RenderType;FFFFFI)V"),
            index = 7)
    private int tacz_sewv$colorBar(int argb) {
        if (argb == TACZ_SEWV$BAR_BACKGROUND) return argb;
        Integer faction = tacz_sewv$factionColor();
        return faction == null ? argb : faction;
    }

    @Unique
    private static Integer tacz_sewv$factionColor() {
        if (!ClientConfig.FACTION_COLORS_ENABLED.get()) return null;

        VehicleEntity vehicle = tacz_sewv$lookingVehicle();
        if (vehicle == null) return null;

        // Invasion match: HUD A/B palette overrides SEM faction colours.
        Integer invasion = InvasionHudClient.overlayColor(vehicle);
        if (invasion != null) return invasion;

        CrewFacts.Faction faction = CrewFacts.factionOf(vehicle);
        if (faction == null) return null;

        // Prefer server-synced OpenPAC tint from map markers when this hull is known.
        VehicleMarker marker = MapMarkers.markerForVehicle(vehicle.getId());
        if (marker != null) return FactionColors.displayArgb(faction, marker.tintRgb());
        return FactionColors.configArgb(faction);
    }
}
