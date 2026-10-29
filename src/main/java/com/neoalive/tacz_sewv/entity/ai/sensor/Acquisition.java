package com.neoalive.tacz_sewv.entity.ai.sensor;

import it.unimi.dsi.fastutil.ints.Int2LongMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import com.neoalive.tacz_sewv.entity.ai.suppression.InfantryArmorDoctrine;

/**
 * How long it takes to notice a target, Steve's Army's detection model reduced to a number computed
 * once per sighting.
 *
 * <p>SA accumulates detection points every tick. Here the same factors (range, how the target moves,
 * the light it stands in, whether it just fired, how rattled the observer is) set a single delay
 * when a new target is first seen. The steady state is one int and one long compare, which a
 * battle of hundreds of units can afford and SA's per-tick accumulator could not.
 *
 * <p>Two consumers: SEM's proactive target scan for infantry ({@code MixinSemTargetAcquisition}) and
 * {@code FireHesitation} for crews. Retaliation, radio orders, drone broadcasts and anything inside
 * {@link #POINT_BLANK} never wait. The numbers are deterministic; per-unit variation comes from an
 * id hash, so client, server and a repeat run agree.
 */
public final class Acquisition {

    /** Inside this, a target is seen at once whatever it is doing. */
    public static final double POINT_BLANK = 6.0;
    /** Range at which distance starts to matter: SA's full-rate detection ring. */
    public static final double CLEAR_RANGE = 16.0;
    /** Extra ticks per (block beyond {@link #CLEAR_RANGE}) squared. 64 blocks adds ~40 ticks. */
    public static final double RANGE_GAIN = 0.017;
    /** Most distance can add on its own. */
    public static final double RANGE_CAP_TICKS = 40.0;
    /** No delay ever exceeds this: noticing is slowed, never prevented. */
    public static final int MAX_TICKS = 100;
    /** Base delay for an infantryman noticing someone in plain view at close range. */
    public static final int INFANTRY_BASE_TICKS = 10;
    /** How long a shot gives its shooter away. */
    public static final long FIRED_WINDOW_TICKS = 60;

    public enum Motion { SPRINT, WALK, SNEAK, STILL }

    private static final Int2LongOpenHashMap LAST_FIRED = new Int2LongOpenHashMap();
    private static long nextPrune = Long.MIN_VALUE;

    private Acquisition() {}

    // --- world side ---

    /** A shot was fired by {@code shooterId} at {@code now}. Fed from {@code GunFireEvent} and the vehicle fire gate. */
    public static void noteFired(int shooterId, long now) {
        LAST_FIRED.put(shooterId, now);
        if (now >= nextPrune) {
            nextPrune = now + 600;
            ObjectIterator<Int2LongMap.Entry> it = LAST_FIRED.int2LongEntrySet().fastIterator();
            while (it.hasNext()) {
                if (now - it.next().getLongValue() > FIRED_WINDOW_TICKS) it.remove();
            }
        }
    }

    public static boolean firedRecently(LivingEntity target, long now) {
        long at = LAST_FIRED.getOrDefault(target.getId(), Long.MIN_VALUE);
        return at != Long.MIN_VALUE && now - at <= FIRED_WINDOW_TICKS;
    }

    public static Motion motionOf(LivingEntity e) {
        if (e.isSprinting()) return Motion.SPRINT;
        if (e.isCrouching() || e.isVisuallyCrawling()) return Motion.SNEAK;
        double dx = e.getX() - e.xo;
        double dz = e.getZ() - e.zo;
        return dx * dx + dz * dz > 1.0e-4 ? Motion.WALK : Motion.STILL;
    }

    /** Light the target stands in, 0..15, sky darkening included. */
    public static int lightAt(LivingEntity e) {
        return e.level().getMaxLocalRawBrightness(e.blockPosition());
    }

    /**
     * Ticks before {@code observer}, rattled by {@code stress} (0..1), notices {@code target}.
     * {@code base} is the delay in plain view at close range.
     */
    public static int delayTicks(int base, Vec3 observer, LivingEntity target, double stress, int observerId, long now) {
        double d = observer.distanceTo(target.position());
        if (d <= POINT_BLANK) return 0;
        boolean inHull = InfantryArmorDoctrine.tierOf(target) > 0.0f;
        return delayTicks(base, d, motionOf(target), lightAt(target), inHull,
                firedRecently(target, now), stress, spread(observerId));
    }

    // --- pure (headless, see AcquisitionSelfCheck) ---

    public static int delayTicks(int base, double distance, Motion motion, int light, boolean inHull,
                                 boolean firedRecently, double stress, double jitter) {
        if (base <= 0 || distance <= POINT_BLANK) return 0;
        double rangeTicks = firedRecently ? 0.0 : rangeTicks(distance);
        double factor = motionFactor(motion) * lightFactor(light)
                * (1.0 + clamp(stress, 0.0, 1.0))
                * (0.85 + 0.3 * clamp(jitter, 0.0, 1.0));
        // A hull is big and loud; a muzzle flash gives a shooter away. Both still take a beat.
        if (inHull) factor *= 0.25;
        if (firedRecently) factor *= 0.3;
        long ticks = Math.round((base + rangeTicks) * factor);
        return (int) Math.max(0, Math.min(MAX_TICKS, ticks));
    }

    static double rangeTicks(double distance) {
        if (distance <= CLEAR_RANGE) return 0.0;
        double over = distance - CLEAR_RANGE;
        return Math.min(RANGE_CAP_TICKS, over * over * RANGE_GAIN);
    }

    /** SA's movement detection rates (sprint 1.5, walk 1, still 0.7, sneak 0.3) turned into delay. */
    static double motionFactor(Motion motion) {
        return switch (motion) {
            case SPRINT -> 0.67;
            case WALK -> 1.0;
            case STILL -> 1.43;
            case SNEAK -> 2.5;
        };
    }

    /** SA's brightness rate {@code 0.3 + 0.7 * sqrt(light / 15)}, as a delay multiplier. */
    static double lightFactor(int light) {
        double rate = 0.3 + 0.7 * Math.sqrt(clamp(light, 0, 15) / 15.0);
        return 1.0 / rate;
    }

    /** Deterministic [0,1) from an entity id (Fibonacci hash, top 16 bits). */
    public static double spread(int id) {
        return ((id * 0x9E3779B9) >>> 16) / 65536.0;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
