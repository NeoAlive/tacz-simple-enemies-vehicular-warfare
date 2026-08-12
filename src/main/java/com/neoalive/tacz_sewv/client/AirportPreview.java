package com.neoalive.tacz_sewv.client;

import javax.annotation.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.neoalive.tacz_sewv.TaczSewv;

/**
 * Client-only wireframe of the strip AABB (and optional blocker). It outlives the airport GUI on
 * purpose — surveying a runway means walking it with the box drawn — so it is bound to the level it
 * was set in and drops itself on a dimension change rather than haunting the next world.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID, value = Dist.CLIENT)
public final class AirportPreview {

    @Nullable private static AABB footprint;
    @Nullable private static BlockPos blocker;
    @Nullable private static ResourceKey<Level> dimension;
    private static float r = 0.2f;
    private static float g = 0.9f;
    private static float b = 0.3f;

    private AirportPreview() {}

    public static void set(AABB box, @Nullable BlockPos blockerPos, float red, float green, float blue) {
        Minecraft mc = Minecraft.getInstance();
        dimension = mc.level != null ? mc.level.dimension() : null;
        footprint = box;
        blocker = blockerPos;
        r = red;
        g = green;
        b = blue;
    }

    public static void clear() {
        footprint = null;
        blocker = null;
        dimension = null;
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        if (footprint == null) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (dimension != null && !mc.level.dimension().equals(dimension)) {
            clear();
            return;
        }

        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        Vec3 cam = event.getCamera().getPosition();

        AABB box = footprint.move(-cam.x, -cam.y, -cam.z);
        LevelRenderer.renderLineBox(pose, lines, box, r, g, b, 0.9f);

        if (blocker != null) {
            AABB blockBox = new AABB(blocker).move(-cam.x, -cam.y, -cam.z);
            LevelRenderer.renderLineBox(pose, lines, blockBox, 1.0f, 0.15f, 0.15f, 1.0f);
        }

        buffers.endBatch(RenderType.lines());
    }
}
