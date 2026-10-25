package com.neoalive.tacz_sewv.mixin.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource.BufferSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.hud.minimap.element.render.MinimapElementRenderInfo;
import xaero.hud.minimap.radar.render.element.RadarRenderer;

import com.neoalive.tacz_sewv.client.xaerominimap.XaeroMinimapCompat;

/**
 * Skips Xaero's radar icon for an entity we already draw a marker for (and for anyone riding one),
 * so a tank's APP-6 symbol does not sit on top of its own radar dot and its crew's. Returning false
 * is the radar's own "not drawn" answer, so its element counting stays right.
 *
 * <p>Targets the {@code xaero.hud} class, which declares the method; the {@code xaero.common}
 * subclass is a compatibility shell. The full descriptor picks the {@code Entity} overload over
 * javac's {@code Object} bridge. {@code remap = false}: the class names in the descriptor are the
 * same in production, and the method is Xaero's.
 */
@Mixin(value = RadarRenderer.class, remap = false)
public abstract class MixinMinimapRadarHide {

    @Inject(method = "renderElement(Lnet/minecraft/world/entity/Entity;ZZDFDDLxaero/hud/minimap/element/render/MinimapElementRenderInfo;Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;)Z",
            at = @At("HEAD"), cancellable = true)
    private void tacz_sewv$skipMarked(Entity entity, boolean highlighted, boolean outOfBounds, double optionalDepth,
                                      float optionalScale, double partialX, double partialY,
                                      MinimapElementRenderInfo info, GuiGraphics guiGraphics, BufferSource buffers,
                                      CallbackInfoReturnable<Boolean> cir) {
        if (XaeroMinimapCompat.hidesRadarFor(entity, info.location)) {
            cir.setReturnValue(false);
        }
    }
}
