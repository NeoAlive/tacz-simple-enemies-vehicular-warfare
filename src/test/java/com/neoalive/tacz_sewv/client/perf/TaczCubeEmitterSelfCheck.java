package com.neoalive.tacz_sewv.client.perf;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.tacz.guns.client.model.bedrock.BedrockCube;
import com.tacz.guns.client.model.bedrock.BedrockCubeBox;
import com.tacz.guns.client.model.bedrock.BedrockCubePerFace;
import com.tacz.guns.client.model.bedrock.BedrockPolygon;
import com.tacz.guns.client.resource.pojo.model.FaceUVsItem;

/**
 * Pins {@link TaczCubeEmitter} to TaCZ's own cube compile: every vertex TaCZ writes, minus the
 * zero-area empty sides, must come out bit for bit the same.
 */
public final class TaczCubeEmitterSelfCheck {

    public static void main(String[] args) throws Exception {
        PoseStack poseStack = new PoseStack();
        poseStack.translate(0.37, -1.2, 4.9);
        poseStack.mulPose(Axis.YP.rotationDegrees(-180.0F));
        poseStack.mulPose(Axis.XP.rotationDegrees(-95.0F));
        poseStack.mulPose(Axis.ZP.rotationDegrees(13.0F));
        poseStack.scale(1.0F, -1.0F, -1.0F);
        PoseStack.Pose pose = poseStack.last();

        check("box", new BedrockCubeBox(4.0F, 8.0F, -2.5F, 1.0F, 3.25F, 6.0F, 2.0F, 1.5F, 0.25F, false, 64.0F, 32.0F), pose);
        check("box mirrored", new BedrockCubeBox(0.0F, 0.0F, 1.0F, -3.0F, 2.0F, 1.0F, 5.0F, 2.0F, 0.0F, true, 128.0F, 128.0F), pose);
        check("per face", new BedrockCubePerFace(-1.5F, 2.0F, 3.0F, 2.0F, 4.0F, 1.0F, 0.1F, 64.0F, 64.0F,
                FaceUVsItem.singleSouthFace()), pose);

        System.out.println("TaczCubeEmitterSelfCheck OK");
    }

    private static void check(String name, BedrockCube cube, PoseStack.Pose pose) throws Exception {
        Recorder reference = new Recorder();
        cube.compile(pose, reference, 0xF000F0, 0x0A0000, 0.9F, 0.8F, 0.7F, 1.0F);

        Recorder fast = new Recorder();
        new TaczCubeEmitter().emit(polygons(cube), pose, fast, 0xF000F0, 0x0A0000, 0.9F, 0.8F, 0.7F, 1.0F);

        List<float[]> expected = dropEmptyQuads(reference.vertices);
        assert !expected.isEmpty() : name + ": reference wrote nothing";
        assert expected.size() == fast.vertices.size()
                : name + ": " + expected.size() + " reference vertices, " + fast.vertices.size() + " fast";
        for (int i = 0; i < expected.size(); i++) {
            float[] a = expected.get(i);
            float[] b = fast.vertices.get(i);
            for (int k = 0; k < a.length; k++) {
                assert Float.floatToRawIntBits(a[k]) == Float.floatToRawIntBits(b[k])
                        : name + ": vertex " + i + " field " + k + " " + a[k] + " != " + b[k];
            }
        }
    }

    /** TaCZ's empty sides put all four corners on one position (their UVs differ). */
    private static List<float[]> dropEmptyQuads(List<float[]> vertices) {
        List<float[]> kept = new ArrayList<>();
        for (int q = 0; q + 3 < vertices.size(); q += 4) {
            float[] first = vertices.get(q);
            boolean empty = true;
            for (int k = 1; k < 4 && empty; k++) {
                float[] other = vertices.get(q + k);
                for (int f = 0; f < 3; f++) {
                    if (Float.floatToRawIntBits(first[f]) != Float.floatToRawIntBits(other[f])) {
                        empty = false;
                        break;
                    }
                }
            }
            if (!empty) {
                for (int k = 0; k < 4; k++) kept.add(vertices.get(q + k));
            }
        }
        return kept;
    }

    private static BedrockPolygon[] polygons(BedrockCube cube) throws Exception {
        Field field = cube.getClass().getDeclaredField("polygons");
        field.setAccessible(true);
        return (BedrockPolygon[]) field.get(cube);
    }

    /** x y z u v r g b a nx ny nz overlay light, as floats. */
    private static final class Recorder implements VertexConsumer {
        final List<float[]> vertices = new ArrayList<>();

        @Override
        public void vertex(float x, float y, float z, float r, float g, float b, float a, float u, float v,
                           int overlay, int light, float nx, float ny, float nz) {
            vertices.add(new float[]{x, y, z, u, v, r, g, b, a, nx, ny, nz, overlay, light});
        }

        @Override public VertexConsumer vertex(double x, double y, double z) { return this; }
        @Override public VertexConsumer color(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer uv(float u, float v) { return this; }
        @Override public VertexConsumer overlayCoords(int u, int v) { return this; }
        @Override public VertexConsumer uv2(int u, int v) { return this; }
        @Override public VertexConsumer normal(float x, float y, float z) { return this; }
        @Override public void endVertex() {}
        @Override public void defaultColor(int r, int g, int b, int a) {}
        @Override public void unsetDefaultColor() {}
    }

    private TaczCubeEmitterSelfCheck() {}
}
