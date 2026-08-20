package com.neoalive.tacz_sewv.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.entity.ai.plane.DubinsPath;
import com.neoalive.tacz_sewv.init.ModGameRules;

/**
 * Debug wireframe for the plane Dubins landing entry: the straight fix->pad line the "existing LERP
 * approach" (see {@code DrivePlaneGoal.landFinal}) actually flies, in one color, and the computed
 * Dubins turn-in arc onto the alignment line in another, plus a marker + arrow at the hand-off point.
 * Gated on the {@code sewvPlaneCombatDebug} gamerule, same pattern as
 * {@link ExterminationShieldDebugRenderer}. Draws straight from {@link PlaneLandingDebugClient}'s
 * synced state, not from a world entity scan.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID, value = Dist.CLIENT)
public final class PlaneLandingDebugRenderer {

    private static final float[] LERP_COLOR = {0.2f, 0.85f, 1.0f};
    private static final float[] ARC_COLOR = {1.0f, 0.6f, 0.1f};
    private static final float[] MARKER_COLOR = {1.0f, 1.0f, 0.2f};
    private static final double ARC_SAMPLE_STEP = 4.0;
    private static final double ARROW_LENGTH = 3.0;

    private PlaneLandingDebugRenderer() {}

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        if (!ClientGameRules.get(ModGameRules.PLANE_COMBAT_DEBUG)) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        Vec3 cam = event.getCamera().getPosition();

        for (PlaneLandingDebugClient.State state : PlaneLandingDebugClient.states()) {
            drawState(pose, lines, cam, state);
        }

        buffers.endBatch(RenderType.lines());
    }

    private static void drawState(PoseStack pose, VertexConsumer lines, Vec3 cam,
                                  PlaneLandingDebugClient.State state) {
        double y = state.refY();
        Vec3 fix = new Vec3(state.fix().x, y, state.fix().z);
        Vec3 pad = new Vec3(state.pad().x, y, state.pad().z);
        line(pose, lines, fix, pad, cam, LERP_COLOR);

        for (DubinsPath.Segment seg : state.segments()) {
            drawSegment(pose, lines, cam, seg, y);
        }

        Vec3 entry = new Vec3(state.entry().x, y, state.entry().z);
        drawMarker(pose, lines, cam, entry, state.axisDir());
    }

    private static void drawSegment(PoseStack pose, VertexConsumer lines, Vec3 cam,
                                    DubinsPath.Segment seg, double y) {
        double length = seg.length();
        int steps = Math.max(1, (int) Math.ceil(length / ARC_SAMPLE_STEP));
        Vec3 prev = at(seg, 0.0, y);
        for (int i = 1; i <= steps; i++) {
            Vec3 next = at(seg, length * i / steps, y);
            line(pose, lines, prev, next, cam, ARC_COLOR);
            prev = next;
        }
    }

    private static Vec3 at(DubinsPath.Segment seg, double s, double y) {
        Vec3 p = seg.pointAt(s);
        return new Vec3(p.x, y, p.z);
    }

    private static void drawMarker(PoseStack pose, VertexConsumer lines, Vec3 cam, Vec3 entry,
                                   Vec3 axisDir) {
        double crossHalf = 1.0;
        line(pose, lines, entry.subtract(crossHalf, 0, 0), entry.add(crossHalf, 0, 0), cam,
                MARKER_COLOR);
        line(pose, lines, entry.subtract(0, 0, crossHalf), entry.add(0, 0, crossHalf), cam,
                MARKER_COLOR);

        Vec3 dir = axisDir.lengthSqr() > 1.0E-8 ? axisDir.normalize() : new Vec3(0, 0, 1);
        Vec3 tip = entry.add(dir.scale(ARROW_LENGTH));
        line(pose, lines, entry, tip, cam, MARKER_COLOR);
        // Two short barbs so the arrow reads as a direction, not just a longer tick.
        Vec3 side = new Vec3(-dir.z, 0.0, dir.x).scale(ARROW_LENGTH * 0.3);
        Vec3 back = tip.subtract(dir.scale(ARROW_LENGTH * 0.4));
        line(pose, lines, tip, back.add(side), cam, MARKER_COLOR);
        line(pose, lines, tip, back.subtract(side), cam, MARKER_COLOR);
    }

    private static void line(PoseStack pose, VertexConsumer lines, Vec3 from, Vec3 to, Vec3 cam,
                             float[] color) {
        Matrix4f matrix = pose.last().pose();
        Matrix3f normalMat = pose.last().normal();
        float dx = (float) (to.x - from.x);
        float dy = (float) (to.y - from.y);
        float dz = (float) (to.z - from.z);
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0e-6f) return;
        float nx = dx / len;
        float ny = dy / len;
        float nz = dz / len;

        float x0 = (float) (from.x - cam.x);
        float y0 = (float) (from.y - cam.y);
        float z0 = (float) (from.z - cam.z);
        float x1 = (float) (to.x - cam.x);
        float y1 = (float) (to.y - cam.y);
        float z1 = (float) (to.z - cam.z);

        lines.vertex(matrix, x0, y0, z0).color(color[0], color[1], color[2], 0.9f)
                .normal(normalMat, nx, ny, nz).endVertex();
        lines.vertex(matrix, x1, y1, z1).color(color[0], color[1], color[2], 0.9f)
                .normal(normalMat, nx, ny, nz).endVertex();
    }
}
