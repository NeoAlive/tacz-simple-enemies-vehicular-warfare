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
 * <p>Server thread only. The hot-path cost while nothing is traced is one empty-map check.
 */
public final class HeliTrace {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String HEADER = "gameTime,in_x,in_y,in_z,in_dmx,in_dmy,in_dmz,in_yaw,in_pitch,in_roll,"
            + "in_power,in_synchedRot,in_engStart,in_engOver,in_hover,in_fwd,in_back,in_down,in_left,in_right,"
            + "in_mouseX,in_mouseY,in_energy,in_onGround,tcRead,poseErr,"
            + "out_dmx,out_dmy,out_dmz,out_yaw,out_pitch,out_roll,out_power,out_synchedRot\n";

    /** Hull id -> the half-built row for the current tick. */
    private static final Map<Integer, Row> TRACED = new HashMap<>();

    private static final class Row {
        final Path file;
        final StringBuilder line = new StringBuilder(256);
        boolean tcRead;

        Row(Path file) {
            this.file = file;
        }
    }

    private HeliTrace() {}

    /** Flip tracing for {@code hull}. Returns the new state. */
    public static boolean toggle(VehicleEntity hull) {
        if (TRACED.remove(hull.getId()) != null) return false;
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

    /** Engine exit (every return path): finish and append the row. */
    public static void ret(VehicleEntity v) {
        Row row = row(v);
        if (row == null || row.line.length() == 0) return;
        StringBuilder s = row.line;
        var dm = v.getDeltaMovement();
        cols(s, dm.x, dm.y, dm.z, v.getYRot(), v.getXRot(), v.getRoll(), v.getPower(), v.getSynchedPropellerRot());
        s.setCharAt(s.length() - 1, '\n');
        try {
            Files.writeString(row.file, s, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOGGER.error("[sewv heli] trace write failed for #{}; tracing stopped", v.getId(), e);
            TRACED.remove(v.getId());
        }
        s.setLength(0);
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
