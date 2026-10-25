package com.neoalive.tacz_sewv.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import xaero.hud.minimap.element.render.map.MinimapElementMapRendererHandler;

import com.neoalive.tacz_sewv.bridge.IMinimapProjectionView;

/**
 * Exposes the in-minimap handler's element transform so the overlay renderer can project lines with
 * exactly the maths Xaero places icons with. {@code @Shadow} fields behind a duck interface rather
 * than {@code @Accessor}s, per the project's accessor-renaming note; the fields are refreshed by
 * Xaero's {@code prepareRender} each frame, before any element renders.
 */
@Mixin(value = MinimapElementMapRendererHandler.class, remap = false)
public abstract class MixinMinimapMapRendererHandler implements IMinimapProjectionView {

    @Shadow
    private double ps;

    @Shadow
    private double pc;

    @Shadow
    private double zoom;

    @Shadow
    private float halfWView;

    @Override
    public double sewv$ps() {
        return this.ps;
    }

    @Override
    public double sewv$pc() {
        return this.pc;
    }

    @Override
    public double sewv$zoom() {
        return this.zoom;
    }

    @Override
    public float sewv$halfView() {
        return this.halfWView;
    }
}
