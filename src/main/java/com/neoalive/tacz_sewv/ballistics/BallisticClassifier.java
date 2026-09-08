package com.neoalive.tacz_sewv.ballistics;

import java.util.Locale;

/**
 * Pure classifier: which kinetic archetype is this TaCZ bullet close enough to, for the purpose of
 * picking an SBW damage-scale factor? Same shape as
 * {@link com.neoalive.tacz_sewv.entity.ai.core.VehicleWeapons}'s weighted-signal scorer — numeric
 * thresholds decide first, name/ammo-id needles are the lowest-weight tier and exist only for guns
 * those thresholds can't tell apart. No gun id is ever hardcoded: every input is a value read off
 * the gun's own datapack data (see {@link BulletFacts}), so an addon's TaCZ gun classifies the same
 * way a stock one does.
 *
 * <p>Category → SBW handheld anchor (see {@code translation.json}):
 * LIGHT ≈ rifle/SMG/pistol (SBW M4/QBZ scale), HEAVY_MACHINE_GUN row ≈ precision bolt/DMR
 * (SBW M98B scale — stock TaCZ has no true 80-dmg HMG), ANTI_MATERIEL ≈ .50 (SBW NTW-20),
 * EXPLOSIVE ≈ RPG (only {@code explode:true}).
 */
public final class BallisticClassifier {

    public enum Category { LIGHT_AUTOMATIC, HEAVY_MACHINE_GUN, ANTI_MATERIEL, EXPLOSIVE }

    private static final int KINETIC_COUNT = Category.ANTI_MATERIEL.ordinal() + 1; // LIGHT, HMG, AM
    // EXPLOSIVE is decided outright by explosion data, ahead of scoring — see classify(). Among the
    // three kinetic categories, ties favour the more dangerous archetype.
    private static final Category[] TIE_PRIORITY = {
            Category.ANTI_MATERIEL, Category.HEAVY_MACHINE_GUN, Category.LIGHT_AUTOMATIC
    };

    private static final double WEIGHT_DAMAGE_BAND = 5.0;
    private static final double WEIGHT_ARMOR_IGNORE = 4.0;
    private static final double WEIGHT_PELLETS = 3.0;
    private static final double WEIGHT_SPEED = 2.0;
    private static final double WEIGHT_RPM_AUTO = 2.0;
    private static final double WEIGHT_BOLT = 3.0;
    // Last resort: capped at one hit per category (see scoreKinetic), so it can never outweigh a
    // single decisive numeric signal above — the smallest of which is 2.0.
    private static final double WEIGHT_CUE = 0.5;

    // Bands straddle the shipped translation-table anchors (light~9, precision~42, AM~75 —
    // see data/tacz_sewv/sewv/ballistics/translation.json). Thresholds are TaCZ datapack numbers:
    // stock rifles sit ~7-12, bolt snipers ~24-45, .50-class ~75+.
    private static final float DAMAGE_LIGHT_MAX = 20.0F;
    private static final float DAMAGE_ANTI_MATERIEL_MIN = 70.0F;
    private static final float ARMOR_IGNORE_ANTI_MATERIEL = 0.7F;
    // TaCZ bullet.speed is ~170 (pistol) … ~575 (AWP) — NOT SBW Velocity (~5-50). A threshold of
    // 10 matched every gun and shoved high-AP snipers into ANTI_MATERIEL. Only the fastest
    // rounds (true magnum / AM class) should get this nudge.
    private static final float SPEED_ANTI_MATERIEL = 500.0F;
    private static final int RPM_AUTO = 500;
    // Bolt / slow semi: pin mid-damage guns onto the precision (HMG row) band instead of light.
    private static final int RPM_BOLT_MAX = 220;

    private record Cue(Category category, String needle) {}

    // Last-resort needles only. Weighted signals above decide every gun these can't tell apart.
    private static final Cue[] CUES = {
            new Cue(Category.ANTI_MATERIEL, ".50"),
            new Cue(Category.ANTI_MATERIEL, "50cal"),
            new Cue(Category.ANTI_MATERIEL, "50_bmg"),
            new Cue(Category.ANTI_MATERIEL, "12_7"),
            new Cue(Category.ANTI_MATERIEL, "12.7"),
            new Cue(Category.ANTI_MATERIEL, "14_5"),
            new Cue(Category.ANTI_MATERIEL, "14.5"),
            new Cue(Category.ANTI_MATERIEL, "20mm"),
            new Cue(Category.ANTI_MATERIEL, "20_mm"),
            new Cue(Category.ANTI_MATERIEL, "anti_materiel"),
            new Cue(Category.ANTI_MATERIEL, "antimateriel"),
            new Cue(Category.ANTI_MATERIEL, "barrett"),
            new Cue(Category.ANTI_MATERIEL, "ntw"),
            new Cue(Category.ANTI_MATERIEL, "_ap"),
            new Cue(Category.ANTI_MATERIEL, "ap_"),
            new Cue(Category.HEAVY_MACHINE_GUN, "machine_gun"),
            new Cue(Category.HEAVY_MACHINE_GUN, "machinegun"),
            new Cue(Category.HEAVY_MACHINE_GUN, "kord"),
            new Cue(Category.HEAVY_MACHINE_GUN, "dshk"),
            new Cue(Category.HEAVY_MACHINE_GUN, "browning"),
    };

    private BallisticClassifier() {}

    public static Category classify(String nameHint, float damage, float armorIgnore, float speed,
                                     int rpm, int pellets, boolean explosive) {
        // Explosive is a structural fact (the gun has explosion data on its bullet), not a name
        // guess, and nothing else should ever contend with it — decided outright.
        if (explosive) return Category.EXPLOSIVE;
        return pickWinner(scoreKinetic(nameHint == null ? "" : nameHint.toLowerCase(Locale.ROOT),
                damage, armorIgnore, speed, rpm, pellets));
    }

    private static double[] scoreKinetic(String nameHint, float damage, float armorIgnore,
                                         float speed, int rpm, int pellets) {
        double[] scores = new double[KINETIC_COUNT];

        if (damage < DAMAGE_LIGHT_MAX) {
            scores[Category.LIGHT_AUTOMATIC.ordinal()] += WEIGHT_DAMAGE_BAND;
        } else if (damage >= DAMAGE_ANTI_MATERIEL_MIN) {
            scores[Category.ANTI_MATERIEL.ordinal()] += WEIGHT_DAMAGE_BAND;
        } else {
            // Mid band: bolt snipers / DMRs land here (HEAVY_MACHINE_GUN row = precision kinetic
            // in the translation table). Automatic mid-damage guns are rare in stock TaCZ.
            scores[Category.HEAVY_MACHINE_GUN.ordinal()] += WEIGHT_DAMAGE_BAND;
        }

        if (armorIgnore >= ARMOR_IGNORE_ANTI_MATERIEL) {
            scores[Category.ANTI_MATERIEL.ordinal()] += WEIGHT_ARMOR_IGNORE;
        }
        if (pellets > 1) {
            scores[Category.LIGHT_AUTOMATIC.ordinal()] += WEIGHT_PELLETS;
        }
        if (speed >= SPEED_ANTI_MATERIEL) {
            scores[Category.ANTI_MATERIEL.ordinal()] += WEIGHT_SPEED;
        }
        if (rpm > RPM_AUTO) {
            scores[Category.LIGHT_AUTOMATIC.ordinal()] += WEIGHT_RPM_AUTO;
        }
        // Slow single-projectile guns in the mid damage band are precision rifles, not light autos
        // — without this, an AWP (dmg 42, AP 0.6) could still lose to LIGHT on damage-band alone
        // when the light threshold was higher. Kept as a nudge so mid-band + bolt stays precise.
        if (rpm <= RPM_BOLT_MAX && pellets <= 1 && damage >= DAMAGE_LIGHT_MAX
                && damage < DAMAGE_ANTI_MATERIEL_MIN) {
            scores[Category.HEAVY_MACHINE_GUN.ordinal()] += WEIGHT_BOLT;
        }

        // At most one WEIGHT_CUE per category, however many of its needles hit — several needles
        // matching at once (a gun id spelling out both "barrett" and "12_7", say) must never let
        // the cue tier out-vote a single decisive numeric signal.
        boolean[] cueHit = new boolean[KINETIC_COUNT];
        for (Cue cue : CUES) {
            if (!nameHint.isEmpty() && nameHint.contains(cue.needle())) {
                cueHit[cue.category().ordinal()] = true;
            }
        }
        for (int c = 0; c < KINETIC_COUNT; c++) {
            if (cueHit[c]) scores[c] += WEIGHT_CUE;
        }

        return scores;
    }

    private static Category pickWinner(double[] scores) {
        double max = 0.0;
        for (double s : scores) if (s > max) max = s;
        if (max <= 0.0) return Category.LIGHT_AUTOMATIC; // no signal at all - safest default
        for (Category c : TIE_PRIORITY) {
            if (scores[c.ordinal()] == max) return c;
        }
        return Category.LIGHT_AUTOMATIC;
    }
}
