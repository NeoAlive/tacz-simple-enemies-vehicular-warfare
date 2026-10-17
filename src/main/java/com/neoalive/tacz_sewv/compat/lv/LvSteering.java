package com.neoalive.tacz_sewv.compat.lv;

/**
 * SBW key semantics → Limitless Vehicles key semantics. Pure (no LV or Minecraft types) so the
 * self-check can pin it.
 *
 * <p>The drive code was written against SBW, so it is the reference and only the differences
 * are translated:
 * <ul>
 *   <li><b>Tracked.</b> SBW's track engine flips the turn sense whenever the BACK key is held
 *       ({@code trackEngine}: the {@code backInputDown} branch negates {@code deltaRot}). LV's
 *       flips whenever the hull is actually MOVING astern ({@code yawStep = forwardSpeed < 0 ?
 *       -turnSpeed : turnSpeed}). They agree once a reversing hull is under way and disagree in
 *       the transition either side of it, so left/right are swapped exactly when the two
 *       conditions differ.</li>
 *   <li><b>Wheeled.</b> Both invert with real motion astern (a car steering backwards), so the
 *       keys pass through. LV coasts with no input held and has no SBW-style rolling drag to
 *       stand on, so an all-released frame also pulls the handbrake ({@code up}) — {@code stop()}
 *       is meant to stop the hull.</li>
 *   <li><b>Vessel.</b> Both scale the turn by the direction of way on, so the keys pass through.</li>
 * </ul>
 */
public final class LvSteering {

    /** Below this (blocks/tick along the bow) the hull counts as moving astern. */
    static final double ASTERN_EPSILON = 1.0E-3;

    private LvSteering() {}

    /** LV control flags for one tick. */
    public record Keys(boolean forward, boolean backward, boolean left, boolean right, boolean handbrake) {}

    public enum Kind { WHEELED, TRACKED, VESSEL }

    public static Keys translate(Kind kind, boolean forward, boolean back, boolean left, boolean right,
                                 double forwardSpeed) {
        return switch (kind) {
            case TRACKED -> {
                boolean astern = forwardSpeed < -ASTERN_EPSILON;
                boolean swap = back != astern;
                yield new Keys(forward, back, swap ? right : left, swap ? left : right, false);
            }
            case WHEELED -> new Keys(forward, back, left, right, !(forward || back || left || right));
            case VESSEL -> new Keys(forward, back, left, right, false);
        };
    }
}
