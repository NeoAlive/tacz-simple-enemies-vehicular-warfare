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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.block.HelipadBlockEntity;
import com.neoalive.tacz_sewv.block.RunwayBlockEntity;

/**
 * Client-only wireframe of the strip AABB (and optional blocker). It outlives the airport GUI on
 * purpose — surveying a runway means walking it with the box drawn — so it is bound to the level it
 * was set in and drops itself on a dimension change rather than haunting the next world.
 */
@EventBusSubscriber(modid = TaczSewv.MODID, value = Dist.CLIENT)
public final class AirportPreview {

    /** Orange, fixed — the slot-start overlay is a location, not a status, so it never traffic-lights. */
    private static final float SLOT_START_R = 1.0f;
    private static final float SLOT_START_G = 0.6f;
    private static final float SLOT_START_B = 0.1f;

    @Nullable private static AABB footprint;
    @Nullable private static BlockPos blocker;
    @Nullable private static AABB slotStart;
    @Nullable private static ResourceKey<Level> dimension;
    /** The runway or helipad block the box belongs to, and whether it was cleared when drawn. */
    @Nullable private static BlockPos owner;
    private static boolean ownerWasCleared;
    private static float r = 0.2f;
    private static float g = 0.9f;
    private static float b = 0.3f;

    private AirportPreview() {}

    public static void set(AABB box, @Nullable BlockPos blockerPos, float red, float green, float blue) {
        set(null, box, blockerPos, red, green, blue);
    }

    /** As above, tied to the block that owns the box: the preview drops when that block goes or loses clearance. */
    public static void set(@Nullable BlockPos ownerPos, AABB box, @Nullable BlockPos blockerPos,
                           float red, float green, float blue) {
        Minecraft mc = Minecraft.getInstance();
        owner = ownerPos == null ? null : ownerPos.immutable();
        Boolean cleared = mc.level != null && owner != null ? clearedAt(mc.level, owner) : null;
        ownerWasCleared = Boolean.TRUE.equals(cleared);
        dimension = mc.level != null ? mc.level.dimension() : null;
        footprint = box;
        blocker = blockerPos;
        r = red;
        g = green;
        b = blue;
    }

    /**
     * Second, independent overlay: where the parking slots begin, and which way they count from
     * there — the same box the runway editor's diagram already draws flat, just in the world. Null
     * clears it without touching the footprint/blocker overlay, since the two are toggled and
     * refreshed together but are otherwise unrelated.
     */
    public static void setSlotStart(@Nullable AABB box) {
        slotStart = box;
    }

    public static void clear() {
        footprint = null;
        blocker = null;
        slotStart = null;
        dimension = null;
        owner = null;
        ownerWasCleared = false;
    }

    /** Cleared state of a runway or helipad block entity, or null when there is none at {@code pos}. */
    @Nullable
    private static Boolean clearedAt(Level level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof RunwayBlockEntity runway) return runway.isCleared();
        if (be instanceof HelipadBlockEntity pad) return pad.isCleared();
        return null;
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
        if (owner != null && mc.level.isLoaded(owner)) {
            Boolean cleared = clearedAt(mc.level, owner);
            if (cleared == null || (ownerWasCleared && !cleared)) {
                clear();
                return;
            }
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

        if (slotStart != null) {
            AABB startBox = slotStart.move(-cam.x, -cam.y, -cam.z);
            LevelRenderer.renderLineBox(pose, lines, startBox,
                    SLOT_START_R, SLOT_START_G, SLOT_START_B, 0.9f);
        }

        buffers.endBatch(RenderType.lines());
    }
}
