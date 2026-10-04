package com.neoalive.tacz_sewv.heli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleVecUtils;
import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.loading.FMLPaths;
import org.joml.Matrix4d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.heli.physics.McPose;

/**
 * Phase 0 instrumentation: one CSV row per tick for each traced helicopter, recording what SBW's
 * {@code helicopterEngine} was handed and what it produced. This is the baseline the physics
 * rework is measured against. Toggled per hull with {@code /sewv debug heliTrace}; written to
 * {@code logs/heli-trace-<id>.csv}.
 *
 * <p>Columns starting with {@code in_} are the state at engine entry, which is also the
 * post-{@code move} state of the previous tick. Columns starting with {@code out_} are the state
 * at engine exit. {@code tcRead} says whether {@code baseTick} read the TerrainCompat list on the
 * previous tick. {@code poseErr} is the largest component difference between {@link McPose}'s
 * up/nose vectors and SBW's own render transform for the same angles: the live check of the Euler
 * convention, which must stay under 1e-4.
 *
 * <p>Phase 2 diagnostics follow {@code model}: the runtime's intended position after publish
 * ({@code p_pred}), its procedures, last reference sample and situation summary (blank on
 * {@code sbw} rows); then {@code out_x..} and {@code dm_applied_..}, read right after
 * {@code baseTick}'s {@code move()}; then {@code p_ent_..}, the entity position at the NEXT tick's
 * engine entry. A row is therefore written one tick late. {@code out_x == in_x} with a non-zero dm
 * means {@code move()} did not apply it; {@code p_ent != out_x} means something wrote the position
 * between ticks.
 *
 * <p>Phase 3 diagnostics follow {@code p_ent}: the active procedure's age, fire phase and fire
 * window; the Situation fields that drive the attack rows; the target's position and velocity as
 * guidance saw them; then the fire assist's own verdict for the tick ({@code fire_event} 1 when
 * {@code vehicleShoot} was called, the gate's answer, the boresight-to-LOS angle it was judged on,
 * the cone, and an uncached terrain/obstacle line-of-fire test from muzzle to target).
 *
 * <p>Server thread only. The hot-path cost while nothing is traced is one empty-map check.
 */
public final class HeliTrace {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String HEADER = "gameTime,in_x,in_y,in_z,in_dmx,in_dmy,in_dmz,in_yaw,in_pitch,in_roll,"
            + "in_power,in_synchedRot,in_engStart,in_engOver,in_hover,in_fwd,in_back,in_down,in_left,in_right,"
            + "in_mouseX,in_mouseY,in_energy,in_onGround,tcRead,poseErr,"
            + "out_dmx,out_dmy,out_dmz,out_yaw,out_pitch,out_roll,out_power,out_synchedRot,model,"
            + "p_pred_x,p_pred_y,p_pred_z,proc_active,proc_requested,ref_t,ref_px,ref_py,ref_pz,ref_vx,ref_vy,ref_vz,"
            + "sit_orderKind,sit_underOrders,sit_hasDestination,sit_destinationDistance,"
            + "out_x,out_y,out_z,dm_applied_x,dm_applied_y,dm_applied_z,p_ent_x,p_ent_y,p_ent_z,"
            + "proc_age,fire_phase,fire_window,sit_targetValid,sit_armed,sit_ammoFrac,sit_targetCategory,"
            + "sit_targetDistance,sit_weaponGuided,sit_targetVy,target_x,target_y,target_z,target_vx,target_vy,target_vz,"
            + "fire_event,fire_gate,boresight_vs_los_deg,fire_cone_deg,los_clear,sit_targetId,aim_x,aim_y,aim_z\n";
    /** sit_targetId .. aim_z, blank. */
    private static final String NO_TAIL = ",,,,";
    /** proc_age .. target_vz, blank. */
    private static final String NO_RUNTIME = ",".repeat(16);
    /** fire_event .. los_clear when the goal reported nothing this tick. */
    private static final String NO_FIRE = "0,,,,";

    /** Hull id -> the half-built row for the current tick. */
    private static final Map<Integer, Row> TRACED = new HashMap<>();

    private static final class Row {
        final Path file;
        final StringBuilder line = new StringBuilder(256);
        boolean tcRead;
        /** The row has its post-move columns and waits for the next tick's p_ent. */
        boolean pending;
        /** Runtime columns (proc_age .. target_vz) and the goal's fire columns, written after p_ent. */
        String extra = NO_RUNTIME, fire = NO_FIRE, tail = NO_TAIL;

        Row(Path file) {
            this.file = file;
        }
    }

    private HeliTrace() {}

    /** Flip tracing for {@code hull}. Returns the new state. */
    public static boolean toggle(VehicleEntity hull) {
        Row old = TRACED.remove(hull.getId());
        if (old != null) {
            if (old.pending) write(hull, old, old.line.append(",,,").append(old.extra).append(old.fire).append(old.tail).append('\n'));
            return false;
        }
        Path file = file(hull);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, HEADER);
        } catch (IOException e) {
            LOGGER.error("[sewv heli] cannot open trace file {}", file, e);
            return false;
        }
        TRACED.put(hull.getId(), new Row(file));
        return true;
    }

    public static Path file(VehicleEntity hull) {
        return FMLPaths.GAMEDIR.get().resolve("logs").resolve("heli-trace-" + hull.getId() + ".csv");
    }

    /** Engine entry: start the row. */
    public static void head(VehicleEntity v) {
        Row row = row(v);
        if (row == null) return;
        StringBuilder s = row.line;
        if (row.pending) {
            cols(s, v.getX(), v.getY(), v.getZ());
            s.append(row.extra).append(row.fire).append(row.tail).append('\n');
            if (!write(v, row, s)) return;
        }
        row.pending = false;
        row.extra = NO_RUNTIME;
        row.fire = NO_FIRE;
        row.tail = NO_TAIL;
        s.setLength(0);
        var dm = v.getDeltaMovement();
        s.append(v.level().getGameTime()).append(',');
        cols(s, v.getX(), v.getY(), v.getZ(), dm.x, dm.y, dm.z,
                v.getYRot(), v.getXRot(), v.getRoll(), v.getPower(), v.getSynchedPropellerRot());
        bits(s, v.getEngineStart(), v.getEngineStartOver(), v.getHoverMode(), v.forwardInputDown(),
                v.backInputDown(), v.downInputDown(), v.leftInputDown(), v.rightInputDown());
        cols(s, v.getMouseMoveSpeedX(), v.getMouseMoveSpeedY(), v.getEnergy());
        bits(s, v.onGround(), row.tcRead);
        cols(s, poseError(v));
        row.tcRead = false;
    }

    /**
     * Engine exit: finish and append the row. {@code model} is {@code sbw} when SBW's own engine
     * ran, {@code sewv} when our integrator flew the hull and SBW's engine was cancelled.
     */
    public static void ret(VehicleEntity v, String model) {
        Row row = row(v);
        if (row == null || row.line.length() == 0) return;
        StringBuilder s = row.line;
        var dm = v.getDeltaMovement();
        cols(s, dm.x, dm.y, dm.z, v.getYRot(), v.getXRot(), v.getRoll(), v.getPower(), v.getSynchedPropellerRot());
        s.append(model).append(',');
        HeliRuntime r = HeliFlight.runtime(v);
        if (r == null || !"sewv".equals(model)) {
            s.append(",".repeat(16));
            return;
        }
        cols(s, r.predicted.x, r.predicted.y, r.predicted.z);
        s.append(r.activeId()).append(',').append(r.requested == null ? null : r.requested.id()).append(',');
        var ref = r.lastRef();
        if (ref == null) s.append(",".repeat(7));
        else cols(s, ref.t(), ref.p().x(), ref.p().y(), ref.p().z(), ref.v().x(), ref.v().y(), ref.v().z());
        var sit = r.sit;
        if (sit == null) {
            s.append(",,,,");
        } else {
            s.append(sit.order).append(',');
            bits(s, sit.underOrders, sit.hasDestination);
            cols(s, sit.destDistance);
        }
        StringBuilder e = new StringBuilder(160);
        cols(e, ref == null ? Double.NaN : ref.t() - r.procedureBegan());
        e.append(r.firePhase()).append(',');
        bits(e, r.fireWindow());
        if (sit == null) {
            e.append(",".repeat(13));
        } else {
            bits(e, sit.targetValid, sit.armed);
            cols(e, sit.ammoFrac);
            e.append(sit.targetCategory).append(',');
            cols(e, sit.targetDistance);
            bits(e, sit.weaponGuided);
            cols(e, sit.targetVy, sit.targetX, sit.targetY, sit.targetZ, sit.targetVx, sit.targetVy, sit.targetVz);
        }
        row.extra = e.toString();
        if (sit != null) {
            row.tail = "," + sit.targetId + "," + (Double.isNaN(sit.aimX) ? "" : sit.aimX) + ","
                    + (Double.isNaN(sit.aimY) ? "" : sit.aimY) + "," + (Double.isNaN(sit.aimZ) ? "" : sit.aimZ);
        }
    }

    /** True while {@code v} is being traced (cheap; lets callers skip work the trace alone needs). */
    public static boolean tracing(VehicleEntity v) {
        return row(v) != null;
    }

    /**
     * The pilot's fire assist for this tick (it runs after the hull's own tick, so it lands on the
     * row being completed): whether {@code vehicleShoot} was called, the gate's verdict, the
     * boresight-to-LOS angle and cone it was judged on, and the muzzle-to-target line of fire.
     */
    public static void noteFire(VehicleEntity v, boolean fired, String gate, double angleDeg, double coneDeg,
                                boolean losClear) {
        Row row = row(v);
        if (row == null) return;
        row.fire = (fired ? "1," : "0,") + gate + ',' + angleDeg + ',' + coneDeg + ',' + (losClear ? '1' : '0');
    }

    /** Right after {@code baseTick}'s {@code move()}: where the hull ended up, and its stored dm. */
    public static void postMove(VehicleEntity v) {
        Row row = row(v);
        if (row == null || row.line.length() == 0 || row.pending) return;
        var dm = v.getDeltaMovement();
        cols(row.line, v.getX(), v.getY(), v.getZ(), dm.x, dm.y, dm.z);
        row.pending = true;
    }

    private static boolean write(VehicleEntity v, Row row, CharSequence s) {
        try {
            Files.writeString(row.file, s, StandardOpenOption.APPEND);
            return true;
        } catch (IOException e) {
            LOGGER.error("[sewv heli] trace write failed for #{}; tracing stopped", v.getId(), e);
            TRACED.remove(v.getId());
            return false;
        }
    }

    /** {@code baseTick} read TerrainCompat this tick; reported on the next row. */
    public static void noteTerrainCompat(VehicleEntity v) {
        Row row = row(v);
        if (row != null) row.tcRead = true;
    }

    private static Row row(VehicleEntity v) {
        if (TRACED.isEmpty() || v.level().isClientSide) return null;
        return TRACED.get(v.getId());
    }

    /** Largest component gap between McPose's up/nose and SBW's render transform for the same angles. */
    private static double poseError(VehicleEntity v) {
        Matrix4d m = VehicleVecUtils.getVehicleYOffsetTransform(v, 1.0F);
        Quaterniond q = McPose.toQuat(v.getYRot(), v.getXRot(), v.getRoll());
        double err = 0.0;
        for (Vector3d axis : new Vector3d[] {new Vector3d(0, 1, 0), new Vector3d(0, 0, 1)}) {
            Vector3d sbw = m.transformDirection(new Vector3d(axis)).normalize();
            Vector3d ours = q.transform(new Vector3d(axis));
            err = Math.max(err, Math.max(Math.abs(sbw.x - ours.x),
                    Math.max(Math.abs(sbw.y - ours.y), Math.abs(sbw.z - ours.z))));
        }
        return err;
    }

    private static void cols(StringBuilder s, double... values) {
        for (double d : values) s.append(d).append(','); // StringBuilder.append(double) is locale-free
    }

    private static void bits(StringBuilder s, boolean... values) {
        for (boolean b : values) s.append(b ? '1' : '0').append(',');
    }
}
