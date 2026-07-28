package com.neoalive.tacz_sewv.mixin.client;

import com.atsuishio.superbwarfare.client.overlay.VehicleTeamOverlay;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.client.HeliRunPhaseClient;
import com.neoalive.tacz_sewv.config.ClientConfig;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.DriveHelicopterGoal;
import com.neoalive.tacz_sewv.util.CrewFacts;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

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

    /**
     * The vehicle under the crosshair, resolved once per client tick by the overlay's own
     * raycast in {@code onVehicleTeamOverlayClientTick}. Shadowed rather than re-traced.
     */
    @Shadow
    private static Entity lookingEntity;

    @Unique
    private static final int TACZ_SEWV$BAR_BACKGROUND = 0x80000000;

    @ModifyArg(
            method = "render(Lcom/atsuishio/superbwarfare/client/overlay/RenderContext;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)I",
                    remap = true),
            index = 1)
    private Component tacz_sewv$appendHeliPhase(Component text) {
        if (!ClientConfig.HELI_SHOW_RUN_PHASE.get() && !SewvConfig.HELI_COMBAT_DEBUG.get()) {
            return text;
        }
        if (!(lookingEntity instanceof VehicleEntity vehicle)) return text;
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

        if (!(lookingEntity instanceof VehicleEntity vehicle)) return null;

        CrewFacts.Faction faction = CrewFacts.factionOf(vehicle);
        if (faction == null) return null;
        return switch (faction) {
            case RU -> ClientConfig.parseColor(ClientConfig.COLOR_RU.get(), -1);
            case US -> ClientConfig.parseColor(ClientConfig.COLOR_US.get(), -1);
            case PMC -> ClientConfig.parseColor(ClientConfig.COLOR_PMC.get(), -1);
        };
    }
}
