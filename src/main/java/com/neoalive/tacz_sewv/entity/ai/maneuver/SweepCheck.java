package com.neoalive.tacz_sewv.entity.ai.maneuver;

/**
 * The planner's only window on the world: is this swept segment drivable? Points are hull-centre
 * positions in world X/Z in travel order, {@code hx/hz} the hull's facing at each, {@code reverse}
 * whether the segment is driven astern (so the leading edge is the stern). The driver backs it
 * with {@code GroundTerrainSensor.sweepClear}; a false is final — the candidate is discarded,
 * never nudged.
 */
@FunctionalInterface
public interface SweepCheck {
    boolean clear(double[] x, double[] z, double[] hx, double[] hz, boolean reverse);
}
