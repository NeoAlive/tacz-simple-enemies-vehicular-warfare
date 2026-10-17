package com.neoalive.tacz_sewv.entity.ai.formation;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * Where a formation stands and which way it faces: an anchor plus a horizontal unit forward.
 *
 * <p>The one place the "left/right of a heading" rotation lives. Right is {@code (-fz, fx)} — for a
 * frame facing north {@code (0, -1)} that is east, as it should be — and left is its negation. The
 * command layer's "left" is measured on the enemy→us axis; it is exactly the left of a frame facing
 * the enemy, which is what {@link #battleLeftX}/{@link #battleLeftZ} answer without allocating.
 *
 * <p>Pure vector work: safe on the client and in headless self-checks.
 */
public record FormationFrame(double x, double y, double z, double fx, double fz) {

    /** Frame facing a cardinal. Cardinal step vectors are already unit length. */
    public static FormationFrame ofCardinal(Vec3 anchor, Direction axis) {
        return new FormationFrame(anchor.x, anchor.y, anchor.z, axis.getStepX(), axis.getStepZ());
    }

    /**
     * Frame facing along {@code (dx, dz)}, normalised. A degenerate heading faces north rather than
     * producing NaN slots.
     */
    public static FormationFrame facing(double x, double y, double z, double dx, double dz) {
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0e-9) return new FormationFrame(x, y, z, 0.0, -1.0);
        return new FormationFrame(x, y, z, dx / len, dz / len);
    }

    /**
     * Frame on a bearing in radians, in {@link Math#atan2}{@code (dx, dz)} convention — forward is
     * {@code (sin, cos)}, which is how {@code IdleGroupSupport} stores its travel bearing.
     */
    public static FormationFrame ofBearing(double x, double y, double z, double bearing) {
        return new FormationFrame(x, y, z, Math.sin(bearing), Math.cos(bearing));
    }

    /**
     * Frame at {@code (x, z)} facing the enemy, given the battlefield's enemy→us axis. Toward the
     * enemy is {@code -axis}.
     */
    public static FormationFrame ofBattleAxis(double x, double y, double z, double axisX, double axisZ) {
        return facing(x, y, z, -axisX, -axisZ);
    }

    public double rightX() {
        return -this.fz;
    }

    public double rightZ() {
        return this.fx;
    }

    public double leftX() {
        return leftX(this.fx, this.fz);
    }

    public double leftZ() {
        return leftZ(this.fx, this.fz);
    }

    /** Signed distance of {@code (px, pz)} to the left of the anchor along this frame. */
    public double lateral(double px, double pz) {
        return (px - this.x) * leftX() + (pz - this.z) * leftZ();
    }

    /** Signed distance of {@code (px, pz)} ahead of the anchor along this frame. */
    public double ahead(double px, double pz) {
        return (px - this.x) * this.fx + (pz - this.z) * this.fz;
    }

    public Vec3 anchor() {
        return new Vec3(this.x, this.y, this.z);
    }

    public Vec3 forward() {
        return new Vec3(this.fx, 0.0, this.fz);
    }

    /** Left of a heading {@code (fx, fz)}, X component. */
    public static double leftX(double fx, double fz) {
        return fz;
    }

    /** Left of a heading {@code (fx, fz)}, Z component. */
    public static double leftZ(double fx, double fz) {
        return -fx;
    }

    /** Left of a frame facing the enemy along the enemy→us {@code axis}: {@code (-axisZ, axisX)}. */
    public static double battleLeftX(double axisX, double axisZ) {
        return leftX(-axisX, -axisZ);
    }

    /** See {@link #battleLeftX}. */
    public static double battleLeftZ(double axisX, double axisZ) {
        return leftZ(-axisX, -axisZ);
    }
}
