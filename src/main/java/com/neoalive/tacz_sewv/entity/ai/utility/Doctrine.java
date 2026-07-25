package com.neoalive.tacz_sewv.entity.ai.utility;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.util.CrewFacts;
import net.minecraft.world.entity.Entity;

import javax.annotation.Nullable;

/**
 * How a commander prefers to fight — eight axes that shift the weight of every scored action
 * without changing what any action does.
 *
 * <p>Doctrine belongs to the COMMANDER, not the hull: two identical tanks under different
 * commanders should fight differently, which is the whole reason this is a separate layer from
 * {@link Facts}. Today the commander is the faction, read from a config preset. The design's
 * per-player doctrine editor replaces {@link #forCrew}'s PMC branch and nothing else.
 *
 * <p>Axes are stored raw in their configured -5..+5 range and handed to the scorer
 * {@linkplain #get normalised} to -1..+1, so a weight in the JSON file is "points at full
 * doctrine" and reads the same size as every other modifier beside it.
 */
public final class Doctrine {

    /** The configured range of every axis, either side of neutral. */
    public static final int AXIS_LIMIT = 5;

    /**
     * The doctrine axes, in the order the config table and the weights file name them.
     *
     * <p>Ordinals index {@link #axes}, so <b>appending only</b> — reordering silently
     * repoints every existing config value and every weights key at the wrong axis.
     */
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

    /** Every axis neutral: the fallback whenever a crew's faction can't be read. */
    public static final Doctrine NEUTRAL = new Doctrine(new int[Axis.VALUES.length]);

    // Read once from config at server start, for the same reason VehicleTargeting caches SEM's
    // friendly flags: ConfigValue.get() throws on an unbaked spec, and the scorer runs in an AI
    // tick where that must never happen. Volatile because the refresh is on the server thread
    // and crews are scored from it too — but a torn read of a reference is the only hazard.
    //
    // Deliberately left NULL until refreshed rather than seeded with defaults here: SewvConfig
    // reads Axis for its config keys, so seeding would have this class call back into SewvConfig
    // while SewvConfig's own static initialiser is still running. Null simply means neutral.
    @Nullable
    private static volatile Doctrine[] presets;

    private final int[] axes;

    private Doctrine(int[] axes) {
        this.axes = axes;
    }

    /**
     * A doctrine with the given raw axis values, clamped to the configured range.
     *
     * <p>The only way to build one outside the config path. Exists for the scorer self-check,
     * which needs a doctrine without a baked Forge config behind it, and for whatever eventually
     * reads a player's own doctrine.
     */
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

    /**
     * The doctrine commanding this unit.
     *
     * <p>A PMC's owning player has no doctrine of its own yet, so all three factions resolve to
     * a config preset. When the doctrine editor lands, only the PMC branch changes.
     */
    public static Doctrine forCrew(Entity unit) {
        Doctrine[] snapshot = presets;
        if (snapshot == null) return NEUTRAL;
        CrewFacts.Faction faction = CrewFacts.factionOfCrew(unit);
        return faction == null ? NEUTRAL : snapshot[faction.ordinal()];
    }

    /**
     * Snapshot the config presets. Called from {@code ServerAboutToStartEvent} alongside
     * {@link com.neoalive.tacz_sewv.entity.ai.VehicleTargeting#refreshFactionFriendlyFlags()} —
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
