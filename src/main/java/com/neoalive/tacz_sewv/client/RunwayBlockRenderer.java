package com.neoalive.tacz_sewv.client;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

import com.neoalive.tacz_sewv.block.RunwayBlockEntity;

/**
 * Draws the radar mast. The 1×1 cube stays a baked model so neighbouring faces still cull against
 * it; this renderer is only the bits that stick out, and GeckoLib only advances the spin while
 * this is actually submitted — a frustum miss freezes it.
 */
public class RunwayBlockRenderer extends GeoBlockRenderer<RunwayBlockEntity> {

    public RunwayBlockRenderer(BlockEntityRendererProvider.Context context) {
        super(new RunwayBlockModel());
    }

    @Override
    public RenderType getRenderType(RunwayBlockEntity animatable, ResourceLocation texture,
                                    MultiBufferSource bufferSource, float partialTick) {
        return RenderType.entityCutout(getTextureLocation(animatable));
    }

    @Override
    public boolean shouldRenderOffScreen(RunwayBlockEntity animatable) {
        return false;
    }
}
