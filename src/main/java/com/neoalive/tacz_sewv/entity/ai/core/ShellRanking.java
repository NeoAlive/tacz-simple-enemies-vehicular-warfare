package com.neoalive.tacz_sewv.entity.ai.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleUnaryOperator;
import java.util.function.ToDoubleFunction;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.data.gun.AmmoConsumer;
import com.atsuishio.superbwarfare.data.gun.DefaultGunData;
import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.gun.GunProp;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.entity.vehicle.damage.DamageModifier;
import com.atsuishio.superbwarfare.init.ModDamageTypes;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;

/**
 * Which shell a multi-ammo cannon should load against a given hull, by what each round would
 * actually do to it. Every round the gun can load is run through the target hull's own
 * DamageModifiers: the direct hit as projectile_hit (grapeshot_hit for grapeshot) per pellet, the
 * blast as custom_explosion. A max-health threshold cannot do this: packs balance their hulls on
 * different scales (FCP 2.0 tanks are 290 HP behind a flat -11, where HE does next to nothing).
 *
 * <p>Rules keyed on the direct entity's type ("@superbwarfare:...") are not matched, since no
 * projectile exists yet. Every round of one gun mostly shares a projectile, so the ranking holds.
 */
public final class ShellRanking {

    /** One loadable round: its ammo item id and the numbers it fires with. */
    public record Round(String ammoId, String projectileId, double directDamage, int pellets,
                        double blastDamage, double blastRadius) {
        boolean grapeshot() {
            return projectileId.contains("grapeshot");
        }
    }

    private record Key(EntityType<?> shooter, int seat, int slot, EntityType<?> target) {}

    private record Entry(@Nullable String[] ranked, long computedAt) {}

    private static final long LIFETIME_TICKS = 1200L;
    private static final Map<Key, Entry> CACHE = new ConcurrentHashMap<>();

    private ShellRanking() {}

    /**
     * Ammo ids for {@code slot}, best first against {@code target}, or null when there is no
     * choice to make or nothing could be scored. Only rounds that would do damage are listed.
     */
    @Nullable
    public static String[] rank(VehicleEntity shooter, int seat, int slot, VehicleEntity target) {
        Key key = new Key(shooter.getType(), seat, slot, target.getType());
        long now = shooter.level().getGameTime();
        Entry cached = CACHE.get(key);
        if (cached != null && now - cached.computedAt() < LIFETIME_TICKS && now >= cached.computedAt()) {
            return cached.ranked();
        }
        String[] ranked = compute(shooter, seat, slot, target);
        CACHE.put(key, new Entry(ranked, now));
        return ranked;
    }

    @Nullable
    private static String[] compute(VehicleEntity shooter, int seat, int slot, VehicleEntity target) {
        try {
            GunData gun = VehicleWeapons.gunData(shooter, seat, slot);
            if (gun == null) return null;
            List<AmmoConsumer> consumers = gun.get(GunProp.AMMO_CONSUMER);
            if (consumers == null || consumers.size() < 2) return null;
            List<Round> rounds = rounds(gun.getDefault(), consumers);
            if (rounds.size() < 2) return null;

            DamageModifier modifier = target.getDamageModifier();
            RegistryAccess access = target.level().registryAccess();
            DamageSource hit = ModDamageTypes.causeProjectileHitDamage(access, null, null);
            DamageSource grape = ModDamageTypes.causeGrapeShotHitDamage(access, null, null);
            DamageSource blast = ModDamageTypes.causeCustomExplosionDamage(access, null, null);
            return order(rounds, r -> expected(r,
                    raw -> modifier.compute(target, r.grapeshot() ? grape : hit, (float) raw),
                    raw -> modifier.compute(target, blast, (float) raw)));
        } catch (Throwable t) {
            return null;
        }
    }

    /** Each consumer's round: the gun's own numbers with that consumer's override on top. */
    static List<Round> rounds(DefaultGunData base, List<AmmoConsumer> consumers) {
        List<Round> out = new ArrayList<>();
        String baseProjectile = base.projectile() == null ? "" : String.valueOf(base.projectile().getId());
        for (AmmoConsumer c : consumers) {
            if (c == null || c.getAmmo() == null) continue;
            JsonObject o = c.getOverride();
            out.add(new Round(
                    c.getAmmo().toLowerCase(Locale.ROOT),
                    str(o, "Projectile", baseProjectile).toLowerCase(Locale.ROOT),
                    num(o, "Damage", base.damage),
                    (int) num(o, "ProjectileAmount", Math.max(1, base.getProjectileAmount())),
                    num(o, "ExplosionDamage", base.getExplosionDamage()),
                    num(o, "ExplosionRadius", base.getExplosionRadius())));
        }
        return out;
    }

    /** Damage one shot of {@code r} does after the hull's modifiers. Never negative. */
    static double expected(Round r, DoubleUnaryOperator directMod, DoubleUnaryOperator blastMod) {
        double direct = r.directDamage() > 0 ? Math.max(0, directMod.applyAsDouble(r.directDamage())) : 0;
        double total = direct * Math.max(1, r.pellets());
        if (r.blastDamage() > 0 && r.blastRadius() > 0) {
            total += Math.max(0, blastMod.applyAsDouble(r.blastDamage()));
        }
        return total;
    }

    /** Ammo ids by score, best first, dropping rounds that score nothing. Ties keep data order. */
    @Nullable
    static String[] order(List<Round> rounds, ToDoubleFunction<Round> score) {
        List<Round> scored = new ArrayList<>();
        Map<Round, Double> scores = new java.util.IdentityHashMap<>();
        for (Round r : rounds) {
            double s = score.applyAsDouble(r);
            if (s > 0) {
                scored.add(r);
                scores.put(r, s);
            }
        }
        if (scored.isEmpty()) return null;
        scored.sort(Comparator.comparingDouble((Round r) -> scores.get(r)).reversed());
        String[] ids = new String[scored.size()];
        for (int i = 0; i < ids.length; i++) ids[i] = scored.get(i).ammoId();
        return ids;
    }

    private static double num(@Nullable JsonObject o, String key, double fallback) {
        if (o == null) return fallback;
        JsonElement e = o.get(key);
        try {
            return e != null && e.isJsonPrimitive() ? e.getAsDouble() : fallback;
        } catch (Exception ex) {
            return fallback;
        }
    }

    private static String str(@Nullable JsonObject o, String key, String fallback) {
        if (o == null) return fallback;
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : fallback;
    }
}
