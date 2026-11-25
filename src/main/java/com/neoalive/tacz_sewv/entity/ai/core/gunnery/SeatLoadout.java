package com.neoalive.tacz_sewv.entity.ai.core.gunnery;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.data.gun.AmmoConsumer;
import com.atsuishio.superbwarfare.data.gun.DefaultGunData;
import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.gun.GunProp;
import com.atsuishio.superbwarfare.data.vehicle.subdata.SeatInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.entity.EntityType;

import com.neoalive.tacz_sewv.entity.ai.core.VehicleWeapons;

/**
 * Every round a seat can fire, read once per vehicle type and seat and kept until the next
 * datapack reload. Placeholder slots are left out.
 */
public final class SeatLoadout {

    private record Key(EntityType<?> type, int seat) {}

    private record Entry(int weaponCount, List<Round> rounds) {}

    private static final Map<Key, Entry> CACHE = new ConcurrentHashMap<>();

    private SeatLoadout() {}

    public static void clear() {
        CACHE.clear();
    }

    public static List<Round> of(VehicleEntity vehicle, int seat) {
        SeatInfo info = vehicle.getSeat(seat);
        int weaponCount = info == null || info.weapons() == null ? 0 : info.weapons().size();
        Key key = new Key(vehicle.getType(), seat);
        Entry cached = CACHE.get(key);
        if (cached != null && cached.weaponCount() == weaponCount) return cached.rounds();
        List<Round> rounds = build(vehicle, seat, weaponCount);
        CACHE.put(key, new Entry(weaponCount, rounds));
        return rounds;
    }

    private static List<Round> build(VehicleEntity vehicle, int seat, int weaponCount) {
        List<Round> out = new ArrayList<>();
        for (int slot = 0; slot < weaponCount; slot++) {
            if (!VehicleWeapons.isRealWeapon(vehicle, seat, slot)) continue;
            try {
                GunData gun = VehicleWeapons.gunData(vehicle, seat, slot);
                if (gun == null) continue;
                int role = VehicleWeapons.slotRole(vehicle, seat, slot);
                DefaultGunData base = gun.getDefault();
                List<AmmoConsumer> consumers = gun.get(GunProp.AMMO_CONSUMER);
                if (consumers == null || consumers.isEmpty()) {
                    out.add(round(slot, 0, 1, "", base, null, role));
                    continue;
                }
                for (int i = 0; i < consumers.size(); i++) {
                    AmmoConsumer c = consumers.get(i);
                    if (c == null) continue;
                    out.add(round(slot, i, consumers.size(), c.getAmmo(), base, c.getOverride(), role));
                }
            } catch (Throwable ignored) {
                // Unreadable gun data: the slot is simply not a candidate.
            }
        }
        return List.copyOf(out);
    }

    private static Round round(int slot, int index, int count, @Nullable String ammo, DefaultGunData base,
                               @Nullable JsonObject o, int role) {
        String baseProjectile = base.projectile() == null ? "" : String.valueOf(base.projectile().getId());
        String projectile = str(o, "Projectile", baseProjectile).toLowerCase(Locale.ROOT);
        String shellType = str(o, "ShellType", base.getShellType() == null ? "" : base.getShellType());
        double blast = num(o, "ExplosionDamage", base.getExplosionDamage());
        String ammoId = ammo == null ? "" : ammo.toLowerCase(Locale.ROOT);
        return new Round(slot, index, count, ammoId, projectile, role,
                Round.kindOf(role, ammoId, projectile, shellType, blast),
                num(o, "Damage", base.damage),
                (int) num(o, "ProjectileAmount", Math.max(1, base.getProjectileAmount())),
                blast,
                num(o, "ExplosionRadius", base.getExplosionRadius()),
                num(o, "Spread", base.getSpread()));
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
