package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;

/**
 * Drivetrain facts about the hull a crew is riding, cached for its lifetime.
 *
 * <p>Both are read from {@code data().compute()}, which is far too expensive to call from
 * an AI tick, and neither can change mid-drive — so they are resolved once per hull and
 * held. {@link #attach} is keyed on entity identity, so calling it every tick costs a
 * reference comparison and re-boarding the same hull reuses the answers.
 *
 * <p>Reads are defensive: unreadable hull data must never crash the AI tick, and each
 * fact falls back to the answer whose behaviour is the safe one.
 */
final class HullFacts {

    private VehicleEntity vehicle;
    private boolean helicopter;
    private boolean tracked;

    void attach(VehicleEntity v) {
        if (this.vehicle == v) return;
        this.vehicle = v;
        this.helicopter = computeHelicopter(v);
        this.tracked = computeTracked(v);
    }

    /** Helicopters and fixed-wing (which subclass {@link EngineInfo.Helicopter}) fly, not drive. */
    boolean isHelicopter() {
        return this.helicopter;
    }

    /** Tracked hulls pivot in place; wheeled ones must roll through a turn. */
    boolean isTracked() {
        return this.tracked;
    }

    private static boolean computeHelicopter(VehicleEntity v) {
        try {
            return v.getEngineInfo() instanceof EngineInfo.Helicopter;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean computeTracked(VehicleEntity v) {
        try {
            var data = v.data().compute();
            if (data != null) {
                var trackRotSpeed = data.getEngineInfo().get("TrackRotSpeed");
                return trackRotSpeed != null && trackRotSpeed.getAsInt() > 0;
            }
        } catch (Exception ignored) {}
        return false;
    }
}
