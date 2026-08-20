package com.neoalive.tacz_sewv.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.client.PlaneLandingDebugClient;
import com.neoalive.tacz_sewv.entity.ai.plane.DubinsPath;

/**
 * S->C: a plane's cached Dubins entry arc plus the straight fix->pad "existing LERP curve" line, for
 * the {@code sewvPlaneCombatDebug} wireframe overlay (persistentData is not synced, and the AI that
 * computes this runs server-side while the drawing is client-only — same split
 * {@code PacketHeliRunPhase}/{@code HeliRunPhaseClient} already solve for the heli run-phase overlay).
 *
 * <p>Purely horizontal: every point on the wire is an (x, z) pair, drawn at one shared reference
 * height, matching {@link DubinsPath}'s own "altitude is the caller's business" design.
 */
public final class PacketPlaneLandingDebug {

    private final int entityId;
    private final double refY;
    private final double fixX;
    private final double fixZ;
    private final double padX;
    private final double padZ;
    private final double entryX;
    private final double entryZ;
    private final double axisDirX;
    private final double axisDirZ;
    private final List<DubinsPath.Segment> segments;

    public PacketPlaneLandingDebug(int entityId, double refY, Vec3 fix, Vec3 pad, Vec3 entry,
                                   Vec3 axisDir, List<DubinsPath.Segment> segments) {
        this.entityId = entityId;
        this.refY = refY;
        this.fixX = fix.x;
        this.fixZ = fix.z;
        this.padX = pad.x;
        this.padZ = pad.z;
        this.entryX = entry.x;
        this.entryZ = entry.z;
        this.axisDirX = axisDir.x;
        this.axisDirZ = axisDir.z;
        this.segments = segments;
    }

    public PacketPlaneLandingDebug(FriendlyByteBuf buf) {
        this.entityId = buf.readVarInt();
        this.refY = buf.readDouble();
        this.fixX = buf.readDouble();
        this.fixZ = buf.readDouble();
        this.padX = buf.readDouble();
        this.padZ = buf.readDouble();
        this.entryX = buf.readDouble();
        this.entryZ = buf.readDouble();
        this.axisDirX = buf.readDouble();
        this.axisDirZ = buf.readDouble();

        int count = buf.readVarInt();
        List<DubinsPath.Segment> segs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            boolean isArc = buf.readBoolean();
            if (isArc) {
                double cx = buf.readDouble();
                double cz = buf.readDouble();
                double radius = buf.readDouble();
                double startAngle = buf.readDouble();
                double sweepMag = buf.readDouble();
                boolean ccw = buf.readBoolean();
                segs.add(new DubinsPath.Arc(new Vec3(cx, 0.0, cz), radius, startAngle, sweepMag, ccw));
            } else {
                double sx = buf.readDouble();
                double sz = buf.readDouble();
                double dx = buf.readDouble();
                double dz = buf.readDouble();
                double length = buf.readDouble();
                segs.add(new DubinsPath.Line(new Vec3(sx, 0.0, sz), new Vec3(dx, 0.0, dz), length));
            }
        }
        this.segments = segs;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(this.entityId);
        buf.writeDouble(this.refY);
        buf.writeDouble(this.fixX);
        buf.writeDouble(this.fixZ);
        buf.writeDouble(this.padX);
        buf.writeDouble(this.padZ);
        buf.writeDouble(this.entryX);
        buf.writeDouble(this.entryZ);
        buf.writeDouble(this.axisDirX);
        buf.writeDouble(this.axisDirZ);

        buf.writeVarInt(this.segments.size());
        for (DubinsPath.Segment seg : this.segments) {
            if (seg instanceof DubinsPath.Arc arc) {
                buf.writeBoolean(true);
                buf.writeDouble(arc.center().x);
                buf.writeDouble(arc.center().z);
                buf.writeDouble(arc.radius());
                buf.writeDouble(arc.startAngle());
                buf.writeDouble(arc.sweepMag());
                buf.writeBoolean(arc.ccw());
            } else if (seg instanceof DubinsPath.Line line) {
                buf.writeBoolean(false);
                buf.writeDouble(line.start().x);
                buf.writeDouble(line.start().z);
                buf.writeDouble(line.dir().x);
                buf.writeDouble(line.dir().z);
                buf.writeDouble(line.length());
            }
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> PlaneLandingDebugClient.put(this.entityId, this.refY,
                        new Vec3(this.fixX, 0.0, this.fixZ), new Vec3(this.padX, 0.0, this.padZ),
                        new Vec3(this.entryX, 0.0, this.entryZ),
                        new Vec3(this.axisDirX, 0.0, this.axisDirZ), this.segments)));
        ctx.get().setPacketHandled(true);
    }
}
