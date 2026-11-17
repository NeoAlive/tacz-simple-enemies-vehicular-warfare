package com.neoalive.tacz_sewv.entity.ai.suppression;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;

import com.neoalive.tacz_sewv.bridge.IHullPressure;

/**
 * The crew-side counterpart of SEM's infantry suppression: how much fire a hull is taking, 0..1.
 *
 * <p>Fed by hits on the hull ({@code MixinVehicleHullPressure}, from the raw amount before armour,
 * so rounds ringing off the plate still count) and by detonations nearby ({@link SuppressionBus}).
 * The crew's own {@code RECENTLY_HIT} cannot do this job: it reads the crewman's own hurt record,
 * and a shell striking the hull does not hurt the man inside it.
 *
 * <p>Read on the utility refresh as {@code Signal.UNDER_FIRE}, where it is one more penalty on
 * {@code Confidence} and a push towards smoke and breaking contact. It is a pressure, not a gate:
 * nothing here can stop a crew acting.
 */
public final class HullPressure {

    /** Ticks for pressure to halve with no new fire: a burst unsettles a crew for a few seconds. */
    public static final double HALF_LIFE_TICKS = 60.0;
    /** Every attributable hit counts for at least this much, however little it got through. */
    public static final float HIT_FLOOR = 0.05f;
    /** A hit taking a quarter of the hull's health is a full-pressure event. */
    public static final float SHARE_GAIN = 4.0f;
    /** Most one hit can add, so a single lucky shot does not read as a sustained barrage. */
    public static final float HIT_CAP = 0.6f;

    /** A hit taking at least this share of the hull's health is a real threat, not small arms on armour. */
    public static final float HEAVY_SHARE = 0.03f;
    /** How long after a heavy hit its shooter stays the crew's preferred retaliation target. */
    public static final long RETALIATE_WINDOW = 100;

    private HullPressure() {}

    /** The last heavy hitter still alive and within {@link #RETALIATE_WINDOW}, or null. */
    @javax.annotation.Nullable
    public static net.minecraft.world.entity.LivingEntity recentHeavyAttacker(VehicleEntity hull, long now) {
        IHullPressure p = (IHullPressure) hull;
        long stamp = p.sewv$getHeavyAttackStamp();
        if (stamp == Long.MIN_VALUE || now - stamp > RETALIATE_WINDOW) return null;
        return hull.level().getEntity(p.sewv$getHeavyAttackerId())
                instanceof net.minecraft.world.entity.LivingEntity e && e.isAlive() ? e : null;
    }

    /** Current pressure on {@code hull}, decayed to {@code now}. */
    public static float read(VehicleEntity hull, long now) {
        IHullPressure p = (IHullPressure) hull;
        return decayed(p.sewv$getPressureRaw(), now - p.sewv$getPressureStamp());
    }

    /** Add {@code amount} to the hull's current pressure, capped at 1. */
    public static void add(VehicleEntity hull, float amount, long now) {
        if (amount <= 0.0f) return;
        IHullPressure p = (IHullPressure) hull;
        float current = decayed(p.sewv$getPressureRaw(), now - p.sewv$getPressureStamp());
        p.sewv$setPressureRaw(Math.min(1.0f, current + amount));
        p.sewv$setPressureStamp(now);
    }

    // --- pure (headless, see SuppressionSelfCheck) ---

    /** {@code value} after {@code ticks} without new fire. */
    public static float decayed(float value, long ticks) {
        if (value <= 0.0f) return 0.0f;
        if (ticks <= 0) return value;
        return (float) (value * Math.pow(0.5, ticks / HALF_LIFE_TICKS));
    }

    /** A hit worth turning the gun for: at least {@link #HEAVY_SHARE} of the hull, raw. Unknown max health never is. */
    public static boolean isHeavy(float rawDamage, float maxHealth) {
        return maxHealth > 0.0f && rawDamage >= maxHealth * HEAVY_SHARE;
    }

    /**
     * Should a crew drop its current lock for the shooter that just landed a heavy hit on it? Only a visible shooter,
     * and only if the current lock is not a better use of the gun: a target it cannot see, a target that is not
     * armour, or one farther away than the shooter. The last rule is what stops two tanks trading hits from flipping
     * the turret back and forth: only a CLOSER shooter takes the gun. Rifle fire is never heavy, so it never gets here.
     */
    public static boolean shouldRetaliate(boolean attackerVisible, boolean attackerIsCurrent, boolean currentVisible,
                                          boolean currentIsArmour, boolean attackerCloser) {
        if (!attackerVisible || attackerIsCurrent) return false;
        return !currentVisible || !currentIsArmour || attackerCloser;
    }

    /** What one hit adds, from its raw (pre-armour) damage and the hull's max health. */
    public static float hitAmount(float rawDamage, float maxHealth) {
        if (rawDamage <= 0.0f) return 0.0f;
        float share = maxHealth > 0.0f ? rawDamage / maxHealth : 0.0f;
        return Math.min(HIT_CAP, HIT_FLOOR + share * SHARE_GAIN);
    }
}
