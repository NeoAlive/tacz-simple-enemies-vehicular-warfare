package com.neoalive.tacz_sewv.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.invasion.InvasionBillboard;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.util.List;

/**
 * World-space invasion billboards. Drawn via {@link RenderLevelStageEvent} so they are not
 * entity-distance-culled — only {@link #MAX_DRAW_DISTANCE} applies.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID, value = Dist.CLIENT)
public final class InvasionBillboardRenderer {

    /** Beyond normal entity tracking; still a hard cap so a distant map does not draw forever. */
    private static final double MAX_DRAW_DISTANCE = 512.0;
    private static final float SCALE = 0.025f;

    private InvasionBillboardRenderer() {}

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        ResourceKey<Level> dim = mc.level.dimension();
        List<InvasionBillboard> list = InvasionBillboards.billboards();
        if (list.isEmpty()) return;

        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        Font font = mc.font;
        double maxSq = MAX_DRAW_DISTANCE * MAX_DRAW_DISTANCE;

        for (InvasionBillboard billboard : list) {
            if (!billboard.dimension().equals(dim)) continue;
            double x = billboard.pos().getX() + 0.5;
            double y = billboard.pos().getY() + billboard.yOffset();
            double z = billboard.pos().getZ() + 0.5;
            double dx = x - cam.x;
            double dy = y - cam.y;
            double dz = z - cam.z;
            if (dx * dx + dy * dy + dz * dz > maxSq) continue;

            pose.pushPose();
            pose.translate(dx, dy, dz);
            pose.mulPose(camera.rotation());
            pose.scale(-SCALE, -SCALE, SCALE);

            Matrix4f matrix = pose.last().pose();
            int color = 0xFF000000 | billboard.colorRgb();
            String label = billboard.label();
            float textX = -font.width(label) * 0.5f;
            font.drawInBatch(label, textX, 0, color, false, matrix, buffers,
                    Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);

            if (billboard.showProgress()) {
                String bar = progressLabel(billboard);
                int barColor = billboard.contested() ? 0xFFFFAA00 : color;
                float barX = -font.width(bar) * 0.5f;
                font.drawInBatch(bar, barX, 10, barColor, false, matrix, buffers,
                        Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
            }

            pose.popPose();
        }
        buffers.endBatch();
    }

    private static String progressLabel(InvasionBillboard billboard) {
        int filled = Math.round(Math.max(0f, Math.min(1f, billboard.progress())) * 10f);
        StringBuilder sb = new StringBuilder(billboard.contested() ? "! [" : "[");
        for (int i = 0; i < 10; i++) {
            sb.append(i < filled ? '|' : '.');
        }
        sb.append(']');
        return sb.toString();
    }
}
