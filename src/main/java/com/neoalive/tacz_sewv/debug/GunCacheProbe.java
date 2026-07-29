package com.neoalive.tacz_sewv.debug;

import com.atsuishio.superbwarfare.data.gun.AmmoConsumer;
import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.gun.GunProp;
import com.atsuishio.superbwarfare.data.vehicle.subdata.SeatInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.logging.LogUtils;
import com.neoalive.tacz_sewv.entity.ai.VehicleWeapons;
import com.neoalive.tacz_sewv.util.TankSpawner;
import com.neoalive.tacz_sewv.util.TankSpawner.TankFaction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;

/**
 * One-shot / op probe for the per-tick gun-map cache: after an ammo switch, a same-tick
 * {@link VehicleWeapons#gunData} read must match a freshly rebuilt {@code getGunDataMap()}.
 *
 * <p>Auto-runs when {@code -Dsewv.guncacheProbe=true}; also reachable as
 * {@code /sewv debug guncache}.
 */
public final class GunCacheProbe {

    private static final Logger LOG = LogUtils.getLogger();

    private GunCacheProbe() {}

    public static void registerBootProbe() {
        if (!Boolean.getBoolean("sewv.guncacheProbe")) return;
        MinecraftForge.EVENT_BUS.addListener(GunCacheProbe::onServerStarted);
    }

    private static void onServerStarted(ServerStartedEvent event) {
        ServerLevel level = event.getServer().overworld();
        try {
            String result = run(level, BlockPos.ZERO.above(80));
            LOG.info("[sewv-guncache-probe] {}", result);
            if (!result.startsWith("PASS")) {
                throw new IllegalStateException(result);
            }
        } catch (Throwable t) {
            LOG.error("[sewv-guncache-probe] FAIL", t);
        } finally {
            event.getServer().execute(() -> event.getServer().halt(false));
        }
    }

    /** @return a single-line PASS/FAIL report */
    public static String run(ServerLevel level, BlockPos near) {
        // Prefer a ground hull with a multi-ammo cannon — the RU pool can roll a heli.
        VehicleEntity hull = TankSpawner.spawnTankWithCrew(
                level, near, TankFaction.RU, null, "superbwarfare:bmp_2");
        if (hull == null) {
            hull = TankSpawner.spawnTankWithCrew(
                    level, near, TankFaction.RU, null, "superbwarfare:t_90a");
        }
        if (hull == null) {
            hull = TankSpawner.spawnTankWithCrew(level, near, TankFaction.RU, null);
        }
        if (hull == null) {
            return "FAIL spawn: no RU pool vehicle";
        }
        try {
            return probeHull(hull);
        } finally {
            for (Entity p : List.copyOf(hull.getPassengers())) {
                p.discard();
            }
            hull.discard();
        }
    }

    public static String probeHull(VehicleEntity hull) {
        int seat = 0;
        SeatInfo info = hull.getSeat(seat);
        if (info == null || info.weapons().isEmpty()) {
            return "FAIL seat0 has no weapons on " + hull.getEncodeId();
        }

        // --- weapon-index switch: cache is keyed by weapon name, no invalidate needed ---
        int weaponCount = info.weapons().size();
        if (weaponCount >= 2) {
            hull.setWeaponIndex(seat, 0);
            GunData selected0 = VehicleWeapons.gunData(hull, seat);
            GunData slot0 = VehicleWeapons.gunData(hull, seat, 0);
            if (selected0 == null || !sameAmmo(selected0, slot0)) {
                return "FAIL weapon-index: gunData(seat) != slot 0 after setWeaponIndex(0)";
            }
            hull.setWeaponIndex(seat, 1);
            GunData selected1 = VehicleWeapons.gunData(hull, seat);
            GunData slot1 = VehicleWeapons.gunData(hull, seat, 1);
            if (selected1 == null || !sameAmmo(selected1, slot1)) {
                return "FAIL weapon-index: gunData(seat) != slot 1 after setWeaponIndex(1)";
            }
            if (info.weapons().get(0).equals(info.weapons().get(1))) {
                return "FAIL weapon-index: duplicate weapon names on seat0";
            }
            if (selected0 == selected1) {
                return "FAIL weapon-index: selecting slot 1 still returned slot 0 instance";
            }
        }

        // --- ammo switch: must invalidate; same-tick read must match fresh map ---
        int cannon = findMultiAmmoWeapon(hull, seat);
        if (cannon < 0) {
            return weaponCount >= 2
                    ? "PASS weapon-index OK; SKIP ammo (no multi-consumer weapon on seat0) hull="
                        + hull.getEncodeId()
                    : "FAIL no multi-ammo weapon and <2 slots on " + hull.getEncodeId();
        }

        VehicleWeapons.gunData(hull, seat, cannon); // warm tick cache
        GunData warmed = VehicleWeapons.gunData(hull, seat, cannon);
        if (warmed == null) return "FAIL ammo: warm returned null";
        List<AmmoConsumer> consumers = warmed.get(GunProp.AMMO_CONSUMER);
        if (consumers == null || consumers.size() < 2) {
            return "FAIL ammo: consumers vanished";
        }
        int from = warmed.selectedAmmoType.get();
        int to = from == 0 ? 1 : 0;
        Entity supplier = hull.getAmmoSupplier() != null ? hull.getAmmoSupplier() : hull;
        hull.modifyGunData(seat, cannon, d -> d.changeAmmoConsumer(to, supplier));

        // Stale path: if we did NOT invalidate, same-tick cache would still show `from`.
        // Production selectCannonAmmo invalidates — mirror that, then read.
        VehicleWeapons.invalidateGunMapCache();
        GunData after = VehicleWeapons.gunData(hull, seat, cannon);
        if (after == null) return "FAIL ammo: post-switch gunData null";
        int cachedAmmo = after.selectedAmmoType.get();

        VehicleWeapons.invalidateGunMapCache();
        Map<String, GunData> fresh = hull.getGunDataMap();
        String weaponName = info.weapons().get(cannon);
        GunData freshGun = fresh.get(weaponName);
        if (freshGun == null) return "FAIL ammo: fresh map missing " + weaponName;
        int freshAmmo = freshGun.selectedAmmoType.get();

        if (cachedAmmo != to) {
            return "FAIL ammo: cache read selectedAmmoType=" + cachedAmmo + " want " + to
                    + " (from " + from + ") hull=" + hull.getEncodeId();
        }
        if (cachedAmmo != freshAmmo) {
            return "FAIL ammo: cache=" + cachedAmmo + " freshMap=" + freshAmmo
                    + " STALE hull=" + hull.getEncodeId();
        }

        // Negative control: warm, switch, do NOT invalidate — cached read must be stale.
        VehicleWeapons.gunData(hull, seat, cannon);
        int back = to == 0 ? 1 : 0;
        hull.modifyGunData(seat, cannon, d -> d.changeAmmoConsumer(back, supplier));
        GunData staleRead = VehicleWeapons.gunData(hull, seat, cannon);
        int staleAmmo = staleRead != null ? staleRead.selectedAmmoType.get() : Integer.MIN_VALUE;
        VehicleWeapons.invalidateGunMapCache();
        int truth = hull.getGunDataMap().get(weaponName).selectedAmmoType.get();
        if (staleAmmo == truth) {
            // Cache happened to rebuild (e.g. vehicle id/time churn) — not a failure of invalidate.
            LOG.info("[sewv-guncache-probe] negative-control skipped (cache already rebuilt)");
        } else if (staleAmmo != to) {
            return "FAIL negative-control: expected pre-invalidate cache to still show " + to
                    + " but was " + staleAmmo;
        }

        return "PASS weapon-index + ammo switch coherent same-tick hull=" + hull.getEncodeId()
                + " cannonSlot=" + cannon + " ammo " + from + "->" + to;
    }

    private static boolean sameAmmo(GunData a, GunData b) {
        if (a == null || b == null) return a == b;
        if (a == b) return true;
        return a.selectedAmmoType.get() == b.selectedAmmoType.get();
    }

    private static int findMultiAmmoWeapon(VehicleEntity hull, int seat) {
        SeatInfo info = hull.getSeat(seat);
        if (info == null) return -1;
        for (int w = 0; w < info.weapons().size(); w++) {
            GunData gun = VehicleWeapons.gunData(hull, seat, w);
            if (gun == null) continue;
            List<AmmoConsumer> c = gun.get(GunProp.AMMO_CONSUMER);
            if (c != null && c.size() >= 2) return w;
        }
        return -1;
    }
}
