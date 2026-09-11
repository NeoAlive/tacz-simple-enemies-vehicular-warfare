package com.neoalive.tacz_sewv.entity.ai.support;

import java.lang.reflect.Method;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.data.gun.AmmoConsumer;
import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.gun.GunProp;
import com.atsuishio.superbwarfare.entity.vehicle.TowEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.init.ModSounds;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.block.EmplacementSupport;
import com.neoalive.tacz_sewv.bridge.IIssuedAmmo;
import com.neoalive.tacz_sewv.compat.FcpEmplacementCompat;

/**
 * TOW / FCP-emplacement reload logic used by {@link com.neoalive.tacz_sewv.entity.ai.goal.ManTowGoal}.
 *
 * <p>SBW {@link TowEntity} and FCP emplacements share the same gap: nothing loads them for a mob.
 * SBW's player {@code interact} / FCP's player-only reload timer never run for SEM crews.
 */
public final class TowSupport {

    public static final int GUNNER_SEAT = 0;

    private TowSupport() {}

    /**
     * AT / single-shot launcher crew (SBW TOW, FCP TOW / Kornet / ZiS-3) — used for fire-mission
     * kind and target priority. Magazine MGs are excluded on purpose.
     */
    public static boolean isCrewing(Entity unit) {
        Entity vehicle = unit.getVehicle();
        if (vehicle instanceof TowEntity) return true;
        return vehicle instanceof VehicleEntity hull && FcpEmplacementCompat.isManual(hull);
    }

    /** Anything {@link ManTowGoal} should keep loaded (AT launchers + FCP magazine emplacements). */
    public static boolean needsAiReload(Entity unit) {
        Entity vehicle = unit.getVehicle();
        if (vehicle instanceof TowEntity) return true;
        return vehicle instanceof VehicleEntity hull && FcpEmplacementCompat.needsAiReload(hull);
    }

    /**
     * Loads the weapon the unit is riding. SBW TOW and FCP emplacements both go through
     * {@link GunData#reloadAmmo(Entity)} + {@code virtualAmmo} for issued / pad supply.
     */
    public static boolean reload(VehicleEntity hull, AbstractUnit unit) {
        if (hull instanceof TowEntity tow) {
            return reloadTow(tow, unit);
        }
        if (FcpEmplacementCompat.needsAiReload(hull)) {
            return reloadGeneric(hull, unit, FcpEmplacementCompat.isManual(hull));
        }
        return false;
    }

    private static boolean reloadTow(TowEntity tow, AbstractUnit unit) {
        try {
            GunData gun = tow.getGunData(GUNNER_SEAT);
            if (gun == null) return false;

            boolean loaded = gun.hasEnoughAmmoToShoot(unit);
            if (tow.getLoaded() != loaded) tow.setLoaded(loaded);

            if (loaded) return false;
            if (tow.getReloadCooldown() != 0) return false;

            boolean issued = hasIssuedAmmo(gun, unit);
            boolean fromPad = false;
            if (!issued && gun.countBackupAmmo(unit) <= 0) {
                fromPad = EmplacementSupport.tryFeedTowVirtualAmmo(tow, gun);
                if (!fromPad) {
                    notifyDry(unit, tow);
                    return false;
                }
            }

            boolean useVirtual = issued || fromPad;
            tow.modifyGunData(GUNNER_SEAT, data -> {
                if (useVirtual) data.virtualAmmo.set(data.get(GunProp.MAGAZINE));
                data.reloadAmmo(unit);
            });
            tow.setLoaded(true);
            playReload(tow);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * FCP emplacement reload. Manual guns honour {@code getReloadCooldown}/{@code setLoaded} when
     * present (ZiS-3 / FCP TOW / Kornet); magazine guns just refill via virtualAmmo.
     */
    private static boolean reloadGeneric(VehicleEntity hull, AbstractUnit unit, boolean manual) {
        try {
            GunData gun = hull.getGunData(GUNNER_SEAT);
            if (gun == null) return false;

            boolean loaded = gun.hasEnoughAmmoToShoot(unit);
            syncLoaded(hull, loaded);
            if (loaded) return false;
            if (manual && reloadCooldown(hull) != 0) return false;

            boolean issued = hasIssuedAmmo(gun, unit);
            if (!issued && gun.countBackupAmmo(unit) <= 0) {
                notifyDry(unit, hull);
                return false;
            }

            hull.modifyGunData(GUNNER_SEAT, data -> {
                if (issued) data.virtualAmmo.set(data.get(GunProp.MAGAZINE));
                data.reloadAmmo(unit);
            });
            syncLoaded(hull, true);
            playReload(hull);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void notifyDry(AbstractUnit unit, VehicleEntity weapon) {
        if (unit instanceof net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity pmc) {
            com.neoalive.tacz_sewv.notify.HudNotify.pmcAmmoOut(pmc, weapon,
                    net.minecraft.network.chat.Component.translatable(
                            "notification.tacz_sewv.kind.tow"));
        }
    }

    private static void playReload(VehicleEntity hull) {
        if (hull.level() instanceof ServerLevel serverLevel) {
            serverLevel.playSound(null, hull.blockPosition(), ModSounds.TYPE_63_RELOAD.get(),
                    SoundSource.NEUTRAL, 1.0F, hull.getRandom().nextFloat() * 0.1F + 0.9F);
        }
    }

    private static boolean hasIssuedAmmo(GunData gun, AbstractUnit unit) {
        if (!(unit instanceof IIssuedAmmo crew)) return false;
        Item issued = crew.sewv$getIssuedAmmo();
        if (issued == null) return false;
        AmmoConsumer consumer = gun.selectedAmmoConsumer();
        return consumer != null && consumer.isAmmoItem(new ItemStack(issued));
    }

    /** Reflect {@code setLoaded} when the hull exposes it (FCP EmplacementEntity / SBW Tow). */
    private static void syncLoaded(VehicleEntity hull, boolean loaded) {
        try {
            Method get = findMethod(hull.getClass(), "isLoaded");
            Method set = findMethod(hull.getClass(), "setLoaded", boolean.class);
            if (get == null || set == null) {
                get = findMethod(hull.getClass(), "getLoaded");
            }
            if (set == null) return;
            if (get != null) {
                Object cur = get.invoke(hull);
                if (cur instanceof Boolean b && b == loaded) return;
            }
            set.invoke(hull, loaded);
        } catch (Exception ignored) {
            // display-only; fire path does not need LOADED
        }
    }

    private static int reloadCooldown(VehicleEntity hull) {
        try {
            Method m = findMethod(hull.getClass(), "getReloadCooldown");
            if (m == null) return 0;
            Object v = m.invoke(hull);
            return v instanceof Number n ? n.intValue() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    @Nullable
    private static Method findMethod(Class<?> type, String name, Class<?>... params) {
        Class<?> c = type;
        while (c != null && c != Object.class) {
            try {
                Method m = c.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException e) {
                c = c.getSuperclass();
            }
        }
        return null;
    }
}
