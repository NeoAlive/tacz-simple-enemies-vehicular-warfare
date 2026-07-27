package com.neoalive.tacz_sewv.entity.ai.command;

import com.neoalive.tacz_sewv.entity.ai.utility.Facts;
import com.neoalive.tacz_sewv.entity.ai.utility.UtilityWeights;

/**
 * Situational commander fitness from ready {@link Facts} — pure arithmetic, no world reads.
 *
 * <pre>
 *   w_health * health + w_ammo * ammoScore + w_central * centrality + w_allies * min(allies,3)/3
 * </pre>
 */
public final class CommanderFitness {

    private CommanderFitness() {}

    public static double ammoScore(Facts.Ammo ammo) {
        return switch (ammo) {
            case OK -> 1.0;
            case LOW -> 0.5;
            case OUT -> 0.0;
        };
    }

    /**
     * 1 at the centroid, 0 at {@code maxRadius} (half group diameter), clamped.
     */
    public static double centrality(double x, double z, double centroidX, double centroidZ, double maxRadius) {
        if (maxRadius <= 0.0) return 1.0;
        double dx = x - centroidX;
        double dz = z - centroidZ;
        double dist = Math.sqrt(dx * dx + dz * dz);
        double t = 1.0 - dist / maxRadius;
        return t < 0.0 ? 0.0 : (t > 1.0 ? 1.0 : t);
    }

    public static double alliesScore(int allies) {
        return Math.min(allies, 3) / 3.0;
    }

    public static double score(Facts facts, double x, double z,
                               double centroidX, double centroidZ, double maxRadius,
                               UtilityWeights weights) {
        return weights.scoreCommander(
                facts.health,
                ammoScore(facts.ammo),
                centrality(x, z, centroidX, centroidZ, maxRadius),
                alliesScore(facts.allies));
    }

    /** Headless path used by self-check — plain numbers, no Facts instance. */
    public static double score(double health, double ammo, double centrality, double allies,
                               UtilityWeights weights) {
        return weights.scoreCommander(health, ammo, centrality, allies);
    }
}
