package com.neoalive.tacz_sewv.entity.ai.utility;

import javax.annotation.Nullable;

import net.minecraft.world.entity.Entity;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.crew.CrewFacts;

public final class Doctrine {

    public static final int AXIS_LIMIT = 5;


    public enum Axis {
        AGGRESSION("aggression",
                "Willingness to initiate combat, pursue enemies, and tolerate damage."),
        PRESERVATION("preservation",
                "How valuable the vehicle is considered: retreat timing, smoke, casualty tolerance."),
        COHESION("cohesion",
                "Formation discipline, reinforcement, and willingness to operate alone."),
        INITIATIVE("initiative",
                "How aggressively opportunities are exploited and contacts investigated."),
        SUPPORT_RELIANCE("supportReliance",
                "Dependence on indirect fire: mortar, TOW and air support requests."),
        TARGET_PRIORITY("targetPriority",
                "Negative prioritises infantry, positive prioritises armored targets."),
        MANEUVER("maneuver",
                "Negative favours direct assault, positive increases flanking preference."),
        RISK_TOLERANCE("riskTolerance",
                "Willingness to attack under poor odds and cross exposed terrain.");

        /** The config key and the weights-file key — one name, so the two can never drift. */
        public final String key;
        public final String description;

        Axis(String key, String description) {
            this.key = key;
            this.description = description;
        }

        public static final Axis[] VALUES = values();

        /** The axis a weights-file key names, or null if it names something else. */
        @Nullable
        public static Axis byKey(String key) {
            for (Axis axis : VALUES) {
                if (axis.key.equals(key)) return axis;
            }
            return null;
        }
    }

    public static final Doctrine NEUTRAL = new Doctrine(new int[Axis.VALUES.length]);

    @Nullable
    private static volatile Doctrine[] presets;

    private final int[] axes;

    private Doctrine(int[] axes) {
        this.axes = axes;
    }

    public static Doctrine ofAxes(int[] raw) {
        int[] axes = new int[Axis.VALUES.length];
        for (int i = 0; i < axes.length && i < raw.length; i++) {
            axes[i] = Math.max(-AXIS_LIMIT, Math.min(AXIS_LIMIT, raw[i]));
        }
        return new Doctrine(axes);
    }

    /** This axis normalised to -1..+1, which is the form every weight is calibrated against. */
    public double get(Axis axis) {
        return this.axes[axis.ordinal()] / (double) AXIS_LIMIT;
    }

    /** The configured value, for debug output only. */
    public int raw(Axis axis) {
        return this.axes[axis.ordinal()];
    }

    public static Doctrine forCrew(Entity unit) {
        Doctrine[] snapshot = presets;
        CrewFacts.Faction faction = CrewFacts.factionOfCrew(unit);

        if (faction == CrewFacts.Faction.PMC && unit instanceof net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity pmc) {
            java.util.UUID owner = pmc.getOwnerUUID();
            if (owner != null && !unit.level().isClientSide) {
                Doctrine playerDoctrine = PlayerDoctrineData.get(unit.level()).getDoctrine(owner);
                if (playerDoctrine != null) return playerDoctrine;
            }
        }

        if (snapshot == null) return NEUTRAL;
        return faction == null ? NEUTRAL : snapshot[faction.ordinal()];
    }

    /**
     * Snapshot the config presets. Called from {@code ServerAboutToStartEvent} alongside
     * {@link com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting#refreshFactionFriendlyFlags()} —
     * the one moment every config is baked and nothing has ticked.
     */
    public static void refreshPresets() {
        CrewFacts.Faction[] factions = CrewFacts.Faction.values();
        Doctrine[] built = new Doctrine[factions.length];
        try {
            for (int f = 0; f < factions.length; f++) {
                int[] axes = new int[Axis.VALUES.length];
                for (int a = 0; a < axes.length; a++) {
                    axes[a] = SewvConfig.DOCTRINE[f][a].get();
                }
                built[f] = new Doctrine(axes);
            }
            presets = built;
        } catch (Throwable ignored) {
            // Unreadable config must not take the AI down with it — fight neutrally instead.
            presets = null;
        }
    }

    /**
     * A crew's temperament on the bold-to-cautious scale, from {@link #caution}. Doctrine is one value per faction
     * (or per PMC owner), so without a per-crew spread every hull of a side reads the battlefield identically: they
     * all take cover together, wait together, and stalemate together.
     */
    public enum Temperament {
        /** Never digs in: no battle position, no babysitting its infantry. */
        BOLD,
        /** Takes a position only when the moment calls for it (under fire, outnumbered, hurt, rattled). */
        STEADY,
        /** Takes a position on every contact and holds it longer. */
        CAUTIOUS;

        public static final double BOLD_BELOW = 0.35;
        public static final double CAUTIOUS_ABOVE = 0.7;

        public static Temperament of(double caution) {
            if (caution < BOLD_BELOW) return BOLD;
            return caution > CAUTIOUS_ABOVE ? CAUTIOUS : STEADY;
        }
    }

    /** Half-width of the per-crew spread around the doctrine's lean. */
    public static final double CAUTION_SPREAD = 0.35;
    /** How far the doctrine's lean moves the centre off 0.5. */
    public static final double CAUTION_LEAN = 0.3;

    /**
     * 0 (bold) to 1 (cautious). The doctrine sets the centre (preservation pulls up, aggression and risk tolerance
     * pull down); the hull's UUID sets where in a ±{@link #CAUTION_SPREAD} band this crew sits. Deterministic and
     * stable across sessions (UUIDs persist), like the flank parity, so a reported behaviour is reproducible.
     */
    public static double caution(Doctrine doctrine, long hullSeed) {
        double lean = (doctrine.get(Axis.PRESERVATION) - doctrine.get(Axis.AGGRESSION)
                - doctrine.get(Axis.RISK_TOLERANCE)) / 3.0;
        double c = 0.5 + CAUTION_LEAN * lean + CAUTION_SPREAD * spread(hullSeed);
        return Math.max(0.0, Math.min(1.0, c));
    }

    /** SplitMix64 of the seed, mapped to -1..+1. */
    static double spread(long seed) {
        long z = seed + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z = z ^ (z >>> 31);
        return ((z >>> 11) * 0x1.0p-53) * 2.0 - 1.0;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (Axis axis : Axis.VALUES) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(axis.key).append('=').append(this.axes[axis.ordinal()]);
        }
        return sb.toString();
    }
}
