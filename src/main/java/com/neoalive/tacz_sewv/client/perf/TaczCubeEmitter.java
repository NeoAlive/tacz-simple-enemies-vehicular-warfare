package com.neoalive.tacz_sewv.client.perf;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.tacz.guns.client.model.bedrock.BedrockPolygon;
import com.tacz.guns.client.model.bedrock.BedrockVertex;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * TaCZ's cube {@code compile} without its two allocations per vertex. The same JOML calls run on
 * reused vectors, so every emitted float is the one TaCZ would emit. The only difference: an empty
 * per-face side (four corners on one shared position, zero area) is not written. Single thread only.
 */
public final class TaczCubeEmitter {

    private final Vector3f normal = new Vector3f();
    private final Vector4f position = new Vector4f();

    public void emit(BedrockPolygon[] polygons, PoseStack.Pose pose, VertexConsumer consumer, int light,
                     int overlay, float red, float green, float blue, float alpha) {
        Matrix4f matrix4f = pose.pose();
        Matrix3f matrix3f = pose.normal();
        for (BedrockPolygon polygon : polygons) {
            BedrockVertex[] vertices = polygon.vertices;
            if (isEmptyFace(vertices)) continue;
            normal.set(polygon.normal).mul(matrix3f);
            float nx = normal.x();
            float ny = normal.y();
            float nz = normal.z();
            for (BedrockVertex vertex : vertices) {
                position.set(vertex.pos.x() / 16.0F, vertex.pos.y() / 16.0F, vertex.pos.z() / 16.0F, 1.0F)
                        .mul(matrix4f);
                consumer.vertex(position.x(), position.y(), position.z(), red, green, blue, alpha,
                        vertex.u, vertex.v, overlay, light, nx, ny, nz);
            }
        }
    }

    /**
     * TaCZ builds an empty side from one shared vertex; remapping its UVs makes four new vertices
     * that still share that one position, so the quad has no area.
     */
    static boolean isEmptyFace(BedrockVertex[] vertices) {
        return vertices.length == 4 && vertices[0].pos == vertices[1].pos && vertices[1].pos == vertices[2].pos
                && vertices[2].pos == vertices[3].pos;
    }
}
