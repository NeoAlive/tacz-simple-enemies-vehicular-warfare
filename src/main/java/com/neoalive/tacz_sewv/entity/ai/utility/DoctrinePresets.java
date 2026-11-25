package com.neoalive.tacz_sewv.entity.ai.utility;

import java.util.List;

/**
 * The ledger editor's starting points. Each spends exactly {@link PlayerDoctrineData#BASE_BUDGET}; extra budget
 * from progression is left for the player. Pure, so {@code selfCheckDoctrine} can pin the totals and that the presets
 * really are different crews.
 */
public final class DoctrinePresets {

    public record Preset(String key, int[] axes) {}

    // AGGRESSION, PRESERVATION, COHESION, INITIATIVE, SUPPORT_RELIANCE, TARGET_PRIORITY, MANEUVER, RISK_TOLERANCE
    public static final List<Preset> ALL = List.of(
            new Preset("shock_action", new int[] {5, 0, 0, 5, 0, 0, 5, 5}),
            new Preset("static_defence", new int[] {-5, 5, 5, -5, 0, 0, 0, 0}),
            new Preset("mobile_raid", new int[] {0, 5, -5, 5, 0, 0, 5, 0}),
            new Preset("combined_arms", new int[] {0, 5, 5, 0, 5, 5, 0, 0}),
            new Preset("anti_armour", new int[] {5, 0, 0, 5, 0, 5, 0, 5}));

    private DoctrinePresets() {}
}
