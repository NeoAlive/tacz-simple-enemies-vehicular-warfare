package com.neoalive.tacz_sewv.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.common.minimap.MinimapProcessor;
import xaero.common.minimap.render.MinimapFBORenderer;
import xaero.hud.minimap.element.render.map.MinimapElementMapRendererHandler;

import com.neoalive.tacz_sewv.client.xaerominimap.XaeroMinimapCompat;

/**
 * Hangs our minimap renderers on Xaero's in-minimap element handler, at the moment Xaero builds it
 * and adds its own radar. The handler is a private field with no getter, created here and nowhere
 * else, so the tail of this method is both the only place to reach it and the one moment it is
 * certain to exist. When FBO is unsupported the field stays null and nothing is drawn — the same
 * condition that switches off Xaero's radar.
 *
 * <p>{@code remap = false}: every member named here is Xaero's own, literal in production. Lives in
 * {@code tacz_sewv.xaerominimap.mixins.json} behind {@code XaeroMinimapMixinPlugin}.
 */
@Mixin(value = MinimapFBORenderer.class, remap = false)
public abstract class MixinMinimapFBORenderer {

    @Shadow
    private MinimapElementMapRendererHandler minimapElementMapRendererHandler;

    @Inject(method = "loadFrameBuffer", at = @At("TAIL"))
    private void tacz_sewv$hangMarkers(MinimapProcessor minimapProcessor, CallbackInfo ci) {
        if (this.minimapElementMapRendererHandler != null) {
            XaeroMinimapCompat.bind(this.minimapElementMapRendererHandler);
        }
    }
}
