package com.neoalive.tacz_sewv.config;

/**
 * The numbers behind Easy Mode, in one pure table (no Forge, no Minecraft), so
 * {@code ./gradlew selfCheckEasyMode} can pin them headless.
 *
 * <p>Easy Mode is pitched at a young player and an arcade feel, not "realism toned down", so these
 * are deliberately large. Every lever is a <b>multiplier or floor on the normal value</b>, never a
 * replacement constant: a server that has tuned a knob keeps its tuning, Easy just leans on it.
 * Slower must never become silent — cadences stretch, nothing here is a gate that can stop a crew
 * acting altogether (the parked-statue and 4°-silence bugs were both gates).
 *
 * <p>"Hostile" below means {@link EasyMode#nerfs}; "friendly" means {@link EasyMode#buffs}.
 */
public final class EasyTuning {

    private EasyTuning() {}

    // --- Vehicle fire (hostile crews) ---

    /** Interval between AI shots, as a multiple of the weapon's natural RPM interval. */
    public static final double FIRE_INTERVAL_MULT = 3.0;
    /** Floor on that interval, game ticks: a 600 RPM MG still fires, just ~200 RPM. */
    public static final int FIRE_INTERVAL_FLOOR_TICKS = 6;
    /** Multiplier on configured cooldowns that have no natural RPM to stretch (LV, plane bombs). */
    public static final double COOLDOWN_MULT = 3.0;
    /** Reaction delay on each new target, game ticks (floor on {@code aiFireHesitationTicks}). */
    public static final int HESITATION_FLOOR_TICKS = 60;
    /** Projectile spread: max(configured × mult, floor), degrees. */
    public static final double SPREAD_MULT = 3.0;
    public static final double SPREAD_FLOOR_DEG = 12.0;
    /** Emplacement / missile-system / artillery cooldown and reload multiplier. */
    public static final double CREW_SERVED_RELOAD_MULT = 2.5;
    /** Mortar / Type-63 / vehicle-mortar fire cooldown multiplier. */
    public static final double INDIRECT_COOLDOWN_MULT = 2.5;
    /** Indirect-fire impact scatter radius multiplier. */
    public static final double INDIRECT_DISPERSION_MULT = 3.0;

    // --- Mobility & cadence (hostile crews) ---

    /** Turret traverse multiplier for EVERY AI-crewed hull, either side (Easy master switch only). */
    public static final float AI_TURRET_TRAVERSE_MULT = 0.5f;
    /**
     * Extra turret traverse multiplier for a hostile crew, on top of {@link #AI_TURRET_TRAVERSE_MULT}:
     * 0.7 lands at ~0.35 of SBW's rate overall. Much lower and it stops tracking a running player at
     * all, which is a gate, not a pace.
     */
    public static final float TURRET_TRAVERSE_MULT = 0.7f;
    /** Track / wheel engine thrust multiplier. */
    public static final float DRIVE_THRUST_MULT = 0.65f;
    /** Repath cooldowns / path max age multiplier. */
    public static final double PATH_CADENCE_MULT = 2.0;
    /** Target-scan interval multiplier. */
    public static final double SCAN_INTERVAL_MULT = 2.0;
    /** Target-scan radius multiplier (96 → 64). */
    public static final double SCAN_RADIUS_MULT = 2.0 / 3.0;

    // --- Morale (hostile crews) ---

    /** Subtracted from crew confidence (0..100, neutral 50). */
    public static final double CONFIDENCE_MALUS = 25.0;
    public static final double BIAS_RETREAT = 12.0;
    public static final double BIAS_DEPLOY_SMOKE = 10.0;
    public static final double BIAS_ADVANCE = -12.0;
    /** Applied to BOTH flank actions: a mirrored pair must stay identical. */
    public static final double BIAS_FLANK = -8.0;
    /** Command-layer force balance multiplier for hostile groups. */
    public static final double FORCE_BALANCE_MULT = 0.6;
    /** Command-layer minimum play duration multiplier. */
    public static final double MIN_PLAY_TICKS_MULT = 2.0;

    // --- AT infantry ---

    /** Aim time before an AT gunner fires, game ticks (normal 15). */
    public static final int AT_AIM_TICKS = 50;
    /** Extra pause after a reload completes, game ticks. */
    public static final int AT_POST_RELOAD_TICKS = 40;

    // --- SEM infantry (hostile units) ---

    /** Rifle spread floor, degrees (SEM caps at ±1.2°). */
    public static final float RIFLE_SPREAD_FLOOR_DEG = 5.0f;
    public static final int RIFLE_BURST_MIN = 2;
    public static final int RIFLE_BURST_MAX = 3;
    public static final int RIFLE_BURST_COOLDOWN_MIN = 40;
    public static final int RIFLE_BURST_COOLDOWN_MAX = 60;
    /** Reaction delay before a rifleman's first shot on a new engagement, goal ticks. */
    public static final int RIFLE_REACTION_MIN = 30;
    public static final int RIFLE_REACTION_MAX = 50;
    /** Health fraction under which SEM's tactical manager retreats (SEM 0.35). */
    public static final double RETREAT_HEALTH_FRACTION = 0.6;
    /** Hostile unit max-health multiplier. */
    public static final double HOSTILE_HEALTH_MULT = 0.7;
    /** TaCZ bullet damage multiplier from a hostile unit into a friendly victim. */
    public static final float INFANTRY_BULLET_MULT = 0.6f;
    /** Suppression a timid hostile unit takes, as a multiple of the normal amount. */
    public static final float SUPPRESSION_HOSTILE_MULT = 1.5f;
    /** Suppression one of the player's own units takes, as a multiple of the normal amount. */
    public static final float SUPPRESSION_FRIENDLY_MULT = 0.5f;
    /** Target-acquisition delay for a hostile, as a multiple of the normal delay. */
    public static final double ACQUISITION_MULT = 2.0;

    // --- Grenades (hostile units) ---

    public static final double GRENADE_MIN_RANGE = 6.0;
    /**
     * Throw inaccuracy, in vanilla {@code shootFromRotation} units (gaussian, 0.0075 rad per unit
     * and axis): 12 ≈ 5° standard deviation, a couple of blocks at a typical throw.
     */
    public static final float GRENADE_INACCURACY = 12.0f;
    public static final int GRENADE_FRAG_CAP = 1;
    public static final int GRENADE_RGO_CAP = 0;

    // --- Player & PMC sustain ---

    /** Extra multiplier on explosion damage to a friendly victim, on top of the base scale. */
    public static final float EXPLOSION_EXTRA_MULT = 0.5f;
    /** Owned PMC: hostile damage taken. */
    public static final float PMC_DAMAGE_TAKEN = 0.6f;
    /** Owned PMC: max-health multiplier. */
    public static final double PMC_HEALTH_MULT = 1.5;
    /**
     * Most a single hostile VEHICLE weapon hit (shell, splash, MG, TOW) may deal a player, after
     * {@code easyPlayerDamageTaken}: 4 = two hearts. Hull weapons are tuned for hull HP, so the base
     * scale alone let one shell take half the bar, and a flat x0.2 on top left it under half a heart.
     */
    public static final float VEHICLE_HIT_CAP = 4.0f;
    /** Hostile damage to a hull crewed by a player or owned PMC. */
    public static final float FRIENDLY_HULL_DAMAGE_MULT = 0.5f;
    /** Respawn grace before hostiles may proactively lock the player, game ticks. */
    public static final int RESPAWN_GRACE_TICKS = 1200;
    /** Threat highlight: radius and glow duration (refreshed every pass). */
    public static final double HIGHLIGHT_RADIUS = 96.0;
    public static final int HIGHLIGHT_GLOW_TICKS = 40;

    // --- Events ---

    /** SEM global event cooldown multiplier. */
    public static final double EVENT_COOLDOWN_MULT = 2.0;
    /** Event squad-size multiplier, and the floor it never goes under. */
    public static final double SQUAD_SIZE_MULT = 0.6;
    public static final int SQUAD_SIZE_MIN = 2;

    // --- Pure helpers ---

    /** Easy interval between AI vehicle shots, from the weapon's natural interval. */
    public static int fireInterval(int naturalTicks) {
        return Math.max(FIRE_INTERVAL_FLOOR_TICKS, (int) Math.ceil(Math.max(1, naturalTicks) * FIRE_INTERVAL_MULT));
    }

    public static int scaleTicks(int base, double mult) {
        return (int) Math.ceil(base * mult);
    }

    public static double spread(double configured) {
        return Math.max(configured * SPREAD_MULT, SPREAD_FLOOR_DEG);
    }

    public static int squadSize(int base) {
        if (base <= SQUAD_SIZE_MIN) return base;
        return Math.max(SQUAD_SIZE_MIN, (int) Math.round(base * SQUAD_SIZE_MULT));
    }

    /**
     * Additive utility bias by action name (the {@code Action} enum's name, kept as a string so this
     * table stays free of the AI package). Unknown names answer 0. HOLD is deliberately absent: it
     * sits in both dispatches, and biasing it would win over PATROL out of contact — statues again.
     */
    public static double actionBias(String action) {
        return switch (action) {
            case "RETREAT" -> BIAS_RETREAT;
            case "DEPLOY_SMOKE" -> BIAS_DEPLOY_SMOKE;
            case "ADVANCE" -> BIAS_ADVANCE;
            case "FLANK_LEFT", "FLANK_RIGHT" -> BIAS_FLANK;
            default -> 0.0;
        };
    }
}
