package com.neoalive.tacz_sewv.ballistics;

import java.util.LinkedList;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.logging.LogUtils;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.resource.index.CommonGunIndex;
import com.tacz.guns.resource.pojo.data.gun.BulletData;
import com.tacz.guns.resource.pojo.data.gun.ExtraDamage;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.util.WarnOnce;

/**
 * What a TaCZ gun IS, read straight off its datapack {@link GunData}/{@link BulletData} — gathers,
 * never decides (see {@link BallisticClassifier}). Resolved once per {@code gunId} and cached: TaCZ's
 * own gun index is rebuilt on every resource reload, so a cached reference would otherwise keep
 * answering with pre-reload numbers for the rest of the session — {@link #clearCache()} is called
 * from {@link TranslationTable.Loader} on every {@code /reload} to prevent that.
 *
 * <p>{@link #of} returns {@code null} for any {@code gunId} that doesn't resolve to a loaded TaCZ
 * gun (a modded launcher datapack that hasn't finished loading, a bad id) — callers must treat that
 * as "pass this hit through untranslated", never guess at a category for data that isn't there.
 */
public final class BulletFacts {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<ResourceLocation, BulletFacts> CACHE = new ConcurrentHashMap<>();
    // Sentinel for "resolved to nothing", so a gunId that keeps missing isn't re-looked-up every hit.
    private static final BulletFacts MISSING = new BulletFacts("", 0F, 0F, 0F, 1, 300, 1, false);

    /** Lowercased {@code gunId + ammoId + datapack "type"} string — cue-tier matching only. */
    public final String nameHint;
    /** Per-bullet damage at close range (first {@code damage_adjust} band if the gun has one). */
    public final float damage;
    /** 0..1 fraction of {@link #damage} that ignores armor (TaCZ {@code bullet.extra_damage.armor_ignore}). */
    public final float armorIgnore;
    public final float speed;
    public final int pierce;
    public final int rpm;
    /** Pellets per shot ({@code bullet.bullet_amount}) — a shotgun fires several at once. */
    public final int pellets;
    public final boolean explosive;

    private BulletFacts(String nameHint, float damage, float armorIgnore, float speed,
                        int pierce, int rpm, int pellets, boolean explosive) {
        this.nameHint = nameHint;
        this.damage = damage;
        this.armorIgnore = armorIgnore;
        this.speed = speed;
        this.pierce = pierce;
        this.rpm = rpm;
        this.pellets = pellets;
        this.explosive = explosive;
    }

    public static BulletFacts of(ResourceLocation gunId) {
        if (gunId == null) return null;
        BulletFacts cached = CACHE.get(gunId);
        if (cached != null) return cached == MISSING ? null : cached;

        BulletFacts resolved = resolve(gunId);
        CACHE.put(gunId, resolved == null ? MISSING : resolved);
        return resolved;
    }

    /** Dropped on every datapack {@code /reload} — see {@link TranslationTable.Loader}. */
    public static void clearCache() {
        CACHE.clear();
    }

    private static BulletFacts resolve(ResourceLocation gunId) {
        try {
            Optional<CommonGunIndex> index = TimelessAPI.getCommonGunIndex(gunId);
            if (index.isEmpty()) {
                WarnOnce.warn(LOGGER, "sewv-ballistics-missing-" + gunId,
                        "[sewv] TaCZ gun '" + gunId + "' has no loaded index"
                                + " - ballistic translation passes its hits through unscaled.");
                return null;
            }

            GunData gunData = index.get().getGunData();
            BulletData bullet = gunData.getBulletData();
            ExtraDamage extra = bullet.getExtraDamage();

            float damage = bullet.getDamageAmount();
            if (extra != null) {
                LinkedList<ExtraDamage.DistanceDamagePair> band = extra.getDamageAdjust();
                if (band != null && !band.isEmpty()) damage = band.getFirst().getDamage();
            }
            float armorIgnore = extra != null ? Mth.clamp(extra.getArmorIgnore(), 0F, 1F) : 0F;
            boolean explosive = bullet.getExplosionData() != null;

            String ammoId = gunData.getAmmoId() != null ? gunData.getAmmoId().toString() : "";
            String type = index.get().getType();
            String nameHint = (gunId + " " + ammoId + " " + (type == null ? "" : type))
                    .toLowerCase(Locale.ROOT);

            return new BulletFacts(nameHint, damage, armorIgnore, bullet.getSpeed(),
                    Math.max(1, bullet.getPierce()), Math.max(1, gunData.getRoundsPerMinute()),
                    Math.max(1, bullet.getBulletAmount()), explosive);
        } catch (Exception e) {
            WarnOnce.warn(LOGGER, "sewv-ballistics-error-" + gunId,
                    "[sewv] Failed reading TaCZ gun data for '" + gunId + "'"
                            + " - ballistic translation passes its hits through unscaled.", e);
            return null;
        }
    }
}
