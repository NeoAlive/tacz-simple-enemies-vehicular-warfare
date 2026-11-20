package com.neoalive.tacz_sewv.entity.ai.core.gunnery;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleUnaryOperator;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.entity.vehicle.damage.DamageModifier;
import com.atsuishio.superbwarfare.init.ModDamageTypes;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;

/**
 * What each of a seat's rounds does to one hull type, through that hull's own damage rules: the
 * direct hit as projectile_hit (grapeshot_hit for grapeshot, gunfire for a machine gun) per pellet,
 * plus the blast as custom_explosion. Rounds that score 0 are off the card. It filters, it does not
 * rank. Cached per shooter type, seat and target type until the next datapack reload.
 *
 * <p>Rules keyed on the direct entity type are not matched, since no projectile exists yet. Health,
 * hit angle and component damage are not read: two hulls of one type are the same target.
 */
public final class PenetrationCard {

    /** A rifle round, used for the machine gun test when the seat has no machine gun of its own. */
    static final double REFERENCE_ROUND = 8.0;

    /**
     * {@code damage} lines up with the seat's loadout. {@code mgFraction} is the best machine gun
     * damage kept by the hull as a fraction of raw, NaN when unmeasurable.
     */
    public record Card(double[] damage, double mgFraction, boolean otherRoundHurts) {}

    private record Key(EntityType<?> shooter, int seat, EntityType<?> target) {}

    private static final Map<Key, Card> CACHE = new ConcurrentHashMap<>();
    private static final Card NONE = new Card(new double[0], Double.NaN, false);

    private PenetrationCard() {}

    private static final Map<EntityType<?>, Double> REFERENCE = new ConcurrentHashMap<>();

    public static void clear() {
        CACHE.clear();
        REFERENCE.clear();
    }

    /**
     * What a rifle round keeps against {@code target}'s damage rules, as a fraction of raw: the
     * on-foot observer's view of a hull. Cached per hull type; NaN when unreadable.
     */
    public static double referenceFraction(VehicleEntity target) {
        return REFERENCE.computeIfAbsent(target.getType(), t -> {
            try {
                DamageModifier modifier = target.getDamageModifier();
                DamageSource gunfire = ModDamageTypes.causeGunFireDamage(target.level().registryAccess(), null, null);
                return Math.max(0, modifier.compute(target, gunfire, (float) REFERENCE_ROUND)) / REFERENCE_ROUND;
            } catch (Throwable e) {
                return Double.NaN;
            }
        });
    }

    @Nullable
    public static Card of(VehicleEntity shooter, int seat, List<Round> loadout, VehicleEntity target) {
        Key key = new Key(shooter.getType(), seat, target.getType());
        Card card = CACHE.get(key);
        if (card == null || card.damage().length != loadout.size()) {
            card = compute(loadout, target);
            CACHE.put(key, card);
        }
        return card == NONE ? null : card;
    }

    private static Card compute(List<Round> loadout, VehicleEntity target) {
        try {
            DamageModifier modifier = target.getDamageModifier();
            RegistryAccess access = target.level().registryAccess();
            DamageSource hit = ModDamageTypes.causeProjectileHitDamage(access, null, null);
            DamageSource grape = ModDamageTypes.causeGrapeShotHitDamage(access, null, null);
            DamageSource gunfire = ModDamageTypes.causeGunFireDamage(access, null, null);
            DamageSource blast = ModDamageTypes.causeCustomExplosionDamage(access, null, null);
            DoubleUnaryOperator gun = raw -> modifier.compute(target, gunfire, (float) raw);
            DoubleUnaryOperator blastMod = raw -> modifier.compute(target, blast, (float) raw);
            return build(loadout, r -> r.kind() == Round.Kind.COAX ? gun
                    : r.grapeshot() ? raw -> modifier.compute(target, grape, (float) raw)
                    : raw -> modifier.compute(target, hit, (float) raw), blastMod, gun);
        } catch (Throwable t) {
            return NONE;
        }
    }

    /** Pure half of {@link #compute}, so the self-check can feed it plain modifiers. */
    static Card build(List<Round> loadout, java.util.function.Function<Round, DoubleUnaryOperator> direct,
                      DoubleUnaryOperator blastMod, DoubleUnaryOperator gunfire) {
        double[] damage = new double[loadout.size()];
        double mgFraction = Double.NaN;
        boolean otherHurts = false;
        for (int i = 0; i < damage.length; i++) {
            Round r = loadout.get(i);
            damage[i] = expected(r, direct.apply(r), blastMod);
            if (r.kind() == Round.Kind.COAX) {
                double raw = r.directDamage() * Math.max(1, r.pellets());
                if (raw > 0) {
                    double f = damage[i] / raw;
                    mgFraction = Double.isNaN(mgFraction) ? f : Math.max(mgFraction, f);
                }
            } else if (damage[i] > 0 && r.kind() != Round.Kind.OTHER) {
                otherHurts = true;
            }
        }
        if (Double.isNaN(mgFraction)) {
            mgFraction = Math.max(0, gunfire.applyAsDouble(REFERENCE_ROUND)) / REFERENCE_ROUND;
        }
        return new Card(damage, mgFraction, otherHurts);
    }

    /** One shot of {@code r} after the hull's modifiers. Never negative. */
    static double expected(Round r, DoubleUnaryOperator directMod, DoubleUnaryOperator blastMod) {
        double direct = r.directDamage() > 0 ? Math.max(0, directMod.applyAsDouble(r.directDamage())) : 0;
        double total = direct * Math.max(1, r.pellets());
        if (r.blastDamage() > 0 && r.blastRadius() > 0) {
            total += Math.max(0, blastMod.applyAsDouble(r.blastDamage()));
        }
        return total;
    }
}
