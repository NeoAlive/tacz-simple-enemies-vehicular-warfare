package com.neoalive.tacz_sewv.entity.ai.utility;

import javax.annotation.Nullable;

/**
 * The vocabulary a weight can be multiplied by — every fact the scorer is allowed to care about.
 *
 * <p>A signal is normalised to 0..1, or -1..1 where it has a direction, so a weight always reads as
 * "points at full strength" and two weights side by side are directly comparable. {@link Facts}
 * gathers the raw world; {@link TacticalBrain#sample} projects it onto these; nothing else may
 * invent a number for the scorer to use.
 *
 * <p>Doctrine axes are usable as modifier keys too and are resolved through
 * {@link Doctrine.Axis#byKey}; they are not listed here because they already name themselves.
 *
 * <p>Adding a consideration to this AI means adding a constant here and one line in
 * {@code sample} — never a branch in the brain.
 */
public enum Signal {

    /** Always 1 — an action's flat starting utility. */
    BASE("base"),

    // ---- the enemy ----
    /** We hold a live target. */
    ENEMY_VISIBLE("enemyVisible"),
    /** That target is riding a vehicle, i.e. this is an armor fight. */
    ENEMY_ARMOR("enemyArmor"),
    /** That target is on foot. */
    ENEMY_INFANTRY("enemyInfantry"),
    /** 0..1 by how far inside the preferred engagement ring we are. */
    TOO_CLOSE("tooClose"),
    /** 0..1 by how far outside it we are. */
    TOO_FAR("tooFar"),

    // ---- our own state ----
    /** Battlefield advantage, -1 (hopeless) to +1 (dominant), 0 at an even 50. */
    CONFIDENCE("confidence"),
    /** Ramps 0..1 as the hull falls from half health to destroyed. */
    LOW_HEALTH("lowHealth"),
    /** 0 with a full rack, 0.5 running low, 1 empty. */
    LOW_AMMO("lowAmmo"),
    /** Ramps 0..1 as a PMC hull's charge falls from half to flat. Always 0 for RU/US. */
    LOW_ENERGY("lowEnergy"),
    /** The gun cannot fire right now (reloading, overheated, empty, throttled). */
    CANNOT_SHOOT("cannotShoot"),
    /** A smoke volley is loaded and ready. */
    SMOKE_READY("smokeReady"),
    /** Our line to the target is already through smoke — more would buy nothing. */
    SCREENED("screened"),
    /** 1 shortly after taking a hit, decaying to 0. */
    RECENTLY_HIT("recentlyHit"),

    // ---- everyone else ----
    /** 0..1 by how badly the local force ratio is against us. */
    OUTNUMBERED("outnumbered"),
    /** 0..1 by how many enemies are around beyond the first, saturating at four. */
    THREAT_DENSITY("threatDensity"),
    /** 0..1 by how much friendly armor is around, saturating at three. */
    ALLIES_NEARBY("alliesNearby"),
    /** 1 when no friendly unit is in sensing range at all. */
    ALONE("alone"),
    /**
     * 0..1 by how many nearby friendlies have no target of their own, saturating at three.
     *
     * <p>Whether supporting fire is available at all is <b>not</b> a signal — it is a hard
     * feasibility gate on the three Call actions, so a weight can never talk a crew into radioing
     * a battery that does not exist.
     */
    IDLE_ALLY("idleAlly"),

    // ---- out of contact ----
    /** We remember where an enemy was, recently enough to be worth going to look. */
    LOST_CONTACT("lostContact"),
    /** A player order or area task is standing, so where we go is not ours to choose. */
    UNDER_ORDERS("underOrders"),

    // ---- where we are ----
    // Exactly one ground signal and at most one sky signal is raised at a time.
    /** Open ground: long sightlines, nothing to hide behind. */
    OPEN("open"),
    /** Woodland: close cover, broken sightlines, good flanking country. */
    FOREST("forest"),
    /** Built-up: very short engagement ranges and blind corners. */
    URBAN("urban"),
    /** High ground: steep, awkward, and hard on a turret's elevation arc. */
    MOUNTAIN("mountain"),
    /** Wetland: soft going and poor footing. */
    SWAMP("swamp"),
    /** Desert or badlands: the longest sightlines in the game. */
    DESERT("desert"),

    RAIN("rain"),
    SNOW("snow"),
    STORM("storm"),

    /** 0..1 by how steep the ground around the hull is. */
    STEEP_GROUND("steepGround"),
    /** 0..1 by how far above ordinary fighting altitude we are. */
    HIGH_ALTITUDE("highAltitude"),

    // ---- command tier (Stage 5) ----
    // Raised 0/1 from the crew's published assignment. Strong biases, never lockouts —
    // self-preservation (LOW_HEALTH / RECENTLY_HIT) must still be able to win.
    /** Commander wants this hull as base-of-fire (hold the ring and shoot). */
    TASKED_BASE_OF_FIRE("taskedBaseOfFire"),
    /** Commander wants this hull on a flank maneuver (side comes from the assignment). */
    TASKED_FLANK("taskedFlank"),
    /** Commander wants this hull to push forward (bounding advance element). */
    TASKED_ADVANCE("taskedAdvance"),
    /** Commander wants this hull to hold / overwatch. */
    TASKED_HOLD("taskedHold"),
    /** Commander wants this hull pulling back. */
    TASKED_WITHDRAW("taskedWithdraw");

    /** The key naming this signal in the weights file. */
    public final String key;

    Signal(String key) {
        this.key = key;
    }

    public static final Signal[] VALUES = values();

    @Nullable
    public static Signal byKey(String key) {
        for (Signal signal : VALUES) {
            if (signal.key.equals(key)) return signal;
        }
        return null;
    }
}
