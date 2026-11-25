package com.neoalive.tacz_sewv.entity.ai.core.gunnery;

/** What the gunner calls the target, which decides the fire command list. */
public enum TargetClass {
    TROOPS, LIGHT_ARMOUR, HARD_ARMOUR, HEAVY_CREATURE, UNSCORED;

    /** Machine gun damage kept by a hull, as a fraction of raw, at or above which it is unarmoured. */
    public static final double UNARMOURED_FRACTION = 0.5;
    /** Below this fraction the machine gun counts as doing nothing. */
    public static final double NO_EFFECT_FRACTION = 0.05;

    /**
     * A hull by what the machine gun does to it. {@code mgFraction} is NaN when it could not be
     * measured; {@code otherRoundHurts} is whether any non machine gun round is on the card.
     */
    public static TargetClass ofHull(double mgFraction, boolean otherRoundHurts) {
        if (Double.isNaN(mgFraction)) return UNSCORED;
        if (mgFraction >= UNARMOURED_FRACTION) return TROOPS;
        if (mgFraction < NO_EFFECT_FRACTION) return otherRoundHurts ? HARD_ARMOUR : UNSCORED;
        return LIGHT_ARMOUR;
    }

    public static TargetClass ofLiving(boolean heavy) {
        return heavy ? HEAVY_CREATURE : TROOPS;
    }
}
