package com.neoalive.tacz_sewv.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import software.bernie.geckolib.cache.object.GeoQuad;
import software.bernie.geckolib.cache.object.GeoVertex;
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

    /**
     * Entity diffuse darkens rotated dish faces; flatten to an "up" normal. Light is resampled two
     * blocks above the cube (where the mast actually sits) so the dish is not shaded by its own
     * base — still a real world lightmap, not full-bright/emissive. Do not override
     * {@code render(...)}: GeoBlockRenderer's {@code render(BlockEntity, …)} clashes with BER's
     * generic {@code render(T, …)} after erasure.
     */
    @Override
    public void createVerticesOfQuad(GeoQuad quad, Matrix4f poseState, Vector3f normal,
                                     VertexConsumer buffer, int packedLight, int packedOverlay,
                                     float red, float green, float blue, float alpha) {
        int light = packedLight;
        RunwayBlockEntity be = getAnimatable();
        if (be != null) {
            Level level = be.getLevel();
            if (level != null) {
                light = LevelRenderer.getLightColor(level, be.getBlockPos().above(2));
            }
        }
        for (GeoVertex vertex : quad.vertices()) {
            Vector3f position = vertex.position();
            Vector4f transformed = poseState.transform(
                    new Vector4f(position.x(), position.y(), position.z(), 1.0F));
            buffer.vertex(transformed.x(), transformed.y(), transformed.z(),
                    red, green, blue, alpha,
                    vertex.texU(), vertex.texV(),
                    packedOverlay, light,
                    0.0F, 1.0F, 0.0F);
        }
    }

    @Override
    public boolean shouldRenderOffScreen(RunwayBlockEntity animatable) {
        return false;
    }
}
