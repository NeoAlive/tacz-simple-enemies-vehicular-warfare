package com.neoalive.tacz_sewv.client.fpas;

/**
 * FPAS pose over time: which transition clip plays and where the radar points. Pure, so the
 * self-check can drive it without a world.
 *
 * <p>Azimuth is one acceleration-limited servo for every phase; a phase only picks the velocity
 * it wants. That is what keeps every change smooth (spin to track, contact lost, power cut
 * mid-spin) without a special case per transition. Power off goes ACTIVE, STOWING (decelerate
 * and come round to heading 0), then CLOSING; the dish never folds while the radar is turning.
 *
 * <p>A transition interrupted by the opposite power state runs its own clip backwards instead of
 * starting the other one. The close clip ends where the open clip starts, so either clip at time
 * zero is the other's end pose.
 */
public final class FpasPose {

    public enum Phase { CLOSED, OPENING, ACTIVE, STOWING, CLOSING }

    /** Degrees per tick. Matches the old slew rate. */
    static final float AZ_MAX = 3.0F;
    /** Degrees per tick squared. Spin reaches full speed in about a second. */
    static final float AZ_ACCEL = 0.1F;
    static final float SETTLED_DEG = 0.25F;
    static final float SETTLED_VEL = 0.05F;

    private final float openTicks;
    private final float closeTicks;
    private final float spinRate;

    private Phase phase;
    private float azimuth;
    private float azVel;
    private float stowTarget;
    private float clipTime;
    private boolean reversing;

    /**
     * Starts in the steady state for the current power, so a chunk load does not replay the open.
     *
     * @param spinRate degrees per tick, signed as the radar's Y rotation
     */
    public FpasPose(float openTicks, float closeTicks, float spinRate, boolean powered) {
        this.openTicks = openTicks;
        this.closeTicks = closeTicks;
        this.spinRate = spinRate;
        this.phase = powered ? Phase.ACTIVE : Phase.CLOSED;
    }

    /**
     * @param bearing heading to track in degrees, or NaN to spin
     * @param dt ticks since the last step
     */
    public void step(boolean powered, float bearing, float dt) {
        while (dt > 0.0F) {
            float h = Math.min(dt, 1.0F);
            dt -= h;
            tick(powered, bearing, h);
        }
    }

    private void tick(boolean powered, float bearing, float h) {
        switch (this.phase) {
            case CLOSED -> {
                if (powered) start(Phase.OPENING);
            }
            case OPENING -> {
                this.reversing = !powered;
                this.clipTime += this.reversing ? -h : h;
                if (!this.reversing && this.clipTime >= this.openTicks) {
                    this.phase = Phase.ACTIVE;
                } else if (this.reversing && this.clipTime <= 0.0F) {
                    this.phase = Phase.CLOSED;
                }
            }
            case ACTIVE -> {
                if (!powered) {
                    this.phase = Phase.STOWING;
                    this.stowTarget = stowTarget();
                }
            }
            case STOWING -> {
                if (powered) {
                    this.phase = Phase.ACTIVE;
                } else if (Math.abs(this.stowTarget - this.azimuth) < SETTLED_DEG
                        && Math.abs(this.azVel) < SETTLED_VEL) {
                    this.azimuth = 0.0F;
                    this.azVel = 0.0F;
                    start(Phase.CLOSING);
                }
            }
            case CLOSING -> {
                this.reversing = powered;
                this.clipTime += this.reversing ? -h : h;
                if (!this.reversing && this.clipTime >= this.closeTicks) {
                    this.phase = Phase.CLOSED;
                } else if (this.reversing && this.clipTime <= 0.0F) {
                    this.phase = Phase.ACTIVE;
                }
            }
        }
        this.clipTime = Math.max(0.0F, this.clipTime);

        float want;
        if (this.phase == Phase.ACTIVE) {
            want = Float.isNaN(bearing) ? this.spinRate : approach(wrap(bearing - this.azimuth));
        } else if (this.phase == Phase.STOWING) {
            want = approach(this.stowTarget - this.azimuth);
        } else {
            want = approach(wrap(-this.azimuth));
        }
        this.azVel += clamp(want - this.azVel, AZ_ACCEL * h);
        this.azimuth += this.azVel * h;
        if (this.azimuth >= 180.0F || this.azimuth < -180.0F) {
            float shift = 360.0F * (float) Math.floor((this.azimuth + 180.0F) / 360.0F);
            this.azimuth -= shift;
            this.stowTarget -= shift;
        }
    }

    private void start(Phase next) {
        this.phase = next;
        this.clipTime = 0.0F;
        this.reversing = false;
    }

    /** Heading 0 ahead in the direction already turning, so a stop never backs up through a turn. */
    private float stowTarget() {
        if (this.azVel > SETTLED_VEL) return this.azimuth + mod360(-this.azimuth);
        if (this.azVel < -SETTLED_VEL) return this.azimuth - mod360(this.azimuth);
        return this.azimuth + wrap(-this.azimuth);
    }

    /**
     * Fastest speed that can still stop at the error under the acceleration limit, in whole ticks:
     * v + (v - a) + ... covers v (v + a) / 2a, so it never overshoots.
     */
    private static float approach(float err) {
        float e = Math.abs(err);
        float stop = (float) ((-AZ_ACCEL + Math.sqrt(AZ_ACCEL * AZ_ACCEL + 8.0F * AZ_ACCEL * e)) / 2.0F);
        return Math.copySign(Math.min(AZ_MAX, Math.min(stop, e)), err);
    }

    private static float clamp(float v, float limit) {
        return Math.max(-limit, Math.min(limit, v));
    }

    static float wrap(float deg) {
        float d = deg % 360.0F;
        if (d >= 180.0F) d -= 360.0F;
        if (d < -180.0F) d += 360.0F;
        return d;
    }

    private static float mod360(float deg) {
        float d = deg % 360.0F;
        return d < 0.0F ? d + 360.0F : d;
    }

    public Phase phase() {
        return this.phase;
    }

    public float azimuth() {
        return this.azimuth;
    }

    public float azVel() {
        return this.azVel;
    }

    /** Ticks into the playing clip; only meaningful while OPENING or CLOSING. */
    public float clipTime() {
        return this.clipTime;
    }

    /** The servo is slewing onto something rather than spinning. */
    public boolean azSlewing(boolean tracking) {
        boolean servo = this.phase == Phase.STOWING || this.phase == Phase.ACTIVE && tracking;
        return servo && Math.abs(this.azVel) > SETTLED_VEL;
    }

    public boolean hydraulicsMoving() {
        return this.phase == Phase.OPENING || this.phase == Phase.CLOSING;
    }
}
