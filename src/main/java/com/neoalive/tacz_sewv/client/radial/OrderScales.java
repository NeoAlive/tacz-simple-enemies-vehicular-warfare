package com.neoalive.tacz_sewv.client.radial;

import javax.annotation.Nullable;

import com.neoalive.tacz_sewv.bridge.IHelicopterPilot;
import com.neoalive.tacz_sewv.command.quick.QuickCommandRegistry;
import com.neoalive.tacz_sewv.network.PacketHelicopterCommand;
import com.neoalive.tacz_sewv.network.PacketPatrolVehicle;

/**
 * The Ctrl+scroll values a wheel order carries (patrol / sweep radius, cruise altitude). Session
 * statics, as the TDT steppers they replace were, and read by the Xaero right-click orders too so
 * the map and the wheel never disagree. Clamped to what the packets accept, so a value on screen is
 * always one the server will take. Pure: every referenced constant is a compile-time literal.
 */
public final class OrderScales {

    public enum Scale {
        PATROL(PacketPatrolVehicle.MIN_RADIUS, PacketPatrolVehicle.MAX_RADIUS, 16, 256),
        SEARCH(PacketPatrolVehicle.MIN_RADIUS, PacketPatrolVehicle.MAX_RADIUS, 16, 256),
        ALTITUDE(PacketHelicopterCommand.MIN_ALTITUDE, PacketHelicopterCommand.MAX_ALTITUDE, 5,
                IHelicopterPilot.DEFAULT_CRUISE_ALTITUDE);

        public final int min;
        public final int max;
        public final int step;
        final int initial;

        Scale(int min, int max, int step, int initial) {
            this.min = min;
            this.max = max;
            this.step = step;
            this.initial = initial;
        }

        /** Where {@code value} sits between min and max, for the card's fill bar. */
        public float fraction(int value) {
            return this.max == this.min ? 1.0f : (value - this.min) / (float) (this.max - this.min);
        }
    }

    /** Shift multiplies the step: a 48–1024 radius is 61 notches at 16. */
    public static final int COARSE = 4;

    private static final int[] VALUES = new int[Scale.values().length];

    static {
        for (Scale s : Scale.values()) VALUES[s.ordinal()] = clamp(s, s.initial);
    }

    private OrderScales() {}

    public static int get(Scale s) {
        return VALUES[s.ordinal()];
    }

    public static int patrolRadius() {
        return get(Scale.PATROL);
    }

    public static int searchRadius() {
        return get(Scale.SEARCH);
    }

    public static int heliAltitude() {
        return get(Scale.ALTITUDE);
    }

    /** @return whether the value moved (false at either end, so the caller can stay silent) */
    public static boolean step(Scale s, int dir, boolean coarse) {
        int before = VALUES[s.ordinal()];
        VALUES[s.ordinal()] = stepped(s, before, dir, coarse);
        return VALUES[s.ordinal()] != before;
    }

    static int stepped(Scale s, int value, int dir, boolean coarse) {
        return clamp(s, value + Integer.signum(dir) * s.step * (coarse ? COARSE : 1));
    }

    static int clamp(Scale s, int value) {
        return Math.max(s.min, Math.min(s.max, value));
    }

    /** The scale a wheel leaf carries, or null. */
    @Nullable
    public static Scale forPipeline(String pipelineId) {
        return switch (pipelineId) {
            case QuickCommandRegistry.ID_QUICK_PATROL -> Scale.PATROL;
            case QuickCommandRegistry.ID_QUICK_SEARCH -> Scale.SEARCH;
            case QuickCommandRegistry.ID_QUICK_TAKEOFF -> Scale.ALTITUDE;
            default -> null;
        };
    }
}
