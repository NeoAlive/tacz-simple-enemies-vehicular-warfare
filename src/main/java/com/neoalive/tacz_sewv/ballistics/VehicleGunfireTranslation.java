package com.neoalive.tacz_sewv.ballistics;

import java.util.Locale;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.entity.vehicle.damage.DamageModifier;
import com.atsuishio.superbwarfare.init.ModDamageTypes;
import com.mojang.logging.LogUtils;
import com.tacz.guns.entity.EntityKineticBullet;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.ballistics.BallisticClassifier.Category;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.init.ModGameRules;
import com.neoalive.tacz_sewv.util.WarnOnce;

/**
 * Rewrites a TaCZ bullet's damage onto SBW's own scale and replays it through the hull's own
 * {@link DamageModifier} under a synthetic SBW source - so a hull's whole armor doctrine (per-hull
 * {@code DamageModifiers} rows, immunities, {@code custom()} closures keyed on the literal SBW
 * damage type) applies exactly as it would to a native SBW weapon of the same archetype.
 *
 * <p>An exploding TaCZ round (RPG, grenade launcher, ...) hits a hull through TWO separate
 * {@code hurt()} calls, and they need TWO different SBW channels:
 * <ul>
 *   <li>the direct kinetic hit ({@code tacz:bullet}/{@code tacz:bullet_ignore_armor}) - already
 *   sits in {@code #superbwarfare:projectile}/{@code #superbwarfare:projectile_absolute} (see
 *   {@code ModDamageTypeTagProvider} in the SuperbWarfare source), same as any other bullet;</li>
 *   <li>the AoE blast (TaCZ's own {@code ExplodeUtil}, fired from {@code EntityKineticBullet}) -
 *   carries plain vanilla {@code minecraft:explosion}/{@code player_explosion}, a channel neither
 *   tag above contains at all, so it passed straight through completely untranslated until this
 *   gate was widened to also catch it.</li>
 * </ul>
 * For a non-explosive gun only the first ever fires. For an explosive one (see
 * {@link BallisticClassifier.Category#EXPLOSIVE}) SBW's own equivalent ordnance
 * ({@code RpgRocketStandardEntity} et al., all built on {@code FastThrowableProjectile}) deals its
 * own direct hit via {@code superbwarfare:projectile_hit} and its own AoE via
 * {@code superbwarfare:custom_explosion} - two channels neither of which is {@code gunfire}/
 * {@code gunfire_absolute} - so EXPLOSIVE hits are routed there instead, each through its own factor
 * ({@link TranslationTable#factorFor} / {@link TranslationTable#aoeFactorFor}). Every other category
 * keeps the {@code gunfire}/{@code gunfire_absolute} split, which matches how SBW's own kinetic guns
 * work.
 *
 * <p>See {@link BulletFacts} (gathers), {@link BallisticClassifier} (classifies, pure) and
 * {@link TranslationTable} (the datapack factor table this reads).
 */
public final class VehicleGunfireTranslation {

    private static final Logger LOGGER = LogUtils.getLogger();

    private VehicleGunfireTranslation() {}

    /**
     * Called from {@code MixinVehicleDamageRedirect} in place of one
     * {@code modifier.compute(source, amount)}. Returns {@code null} whenever this hit isn't a TaCZ
     * bullet's damage, the feature is off, or the gun can't be resolved - the caller must then run
     * its own default {@code compute(source, amount)}, leaving TaCZ's existing behaviour exactly as
     * it was. Returns the already-computed hull damage otherwise.
     */
    public static Float tryTranslate(VehicleEntity hull, DamageModifier modifier, DamageSource source, float amount) {
        if (amount <= 0.0F) return null;
        if (hull.level().isClientSide()) return null;
        if (!SewvConfig.TACZ_BALLISTIC_TRANSLATION_ENABLED.get()) return null;
        if (!(source.getDirectEntity() instanceof EntityKineticBullet bullet)) return null;

        boolean isBulletHit = source.is(com.tacz.guns.init.ModDamageTypes.BULLETS_TAG);
        boolean isAoeExplosion = !isBulletHit
                && (source.is(DamageTypes.EXPLOSION) || source.is(DamageTypes.PLAYER_EXPLOSION));
        if (!isBulletHit && !isAoeExplosion) return null;

        try {
            ResourceLocation gunId = bullet.getGunId();
            BulletFacts facts = BulletFacts.of(gunId);
            if (facts == null) return null;

            Category category = BallisticClassifier.classify(facts.nameHint, facts.damage, facts.armorIgnore,
                    facts.speed, facts.rpm, facts.pellets, facts.explosive);
            EngineType engineType = HullFacts.engineType(hull);
            double globalScale = SewvConfig.TACZ_BALLISTIC_GLOBAL_SCALE.get();
            Entity attacker = source.getEntity();

            if (isAoeExplosion) {
                // Only an EXPLOSIVE-classified gun can ever reach this: a non-explosive bullet never
                // makes TaCZ fire its ExplodeUtil path in the first place. Defensive rather than
                // asserted, in case a future TaCZ version detonates something this mod didn't expect.
                if (category != Category.EXPLOSIVE) return null;

                double factor = TranslationTable.active().aoeFactorFor(category, engineType) * globalScale;
                float scaled = (float) (amount * factor);
                DamageSource synthetic = ModDamageTypes.causeCustomExplosionDamage(
                        hull.level().registryAccess(), bullet, attacker);
                float result = modifier.compute(synthetic, scaled);
                debugLog(gunId, category, factor, "AoE", amount, scaled, result);
                return result;
            }

            boolean armorIgnoreHalf = source.is(com.tacz.guns.init.ModDamageTypes.BULLET_IGNORE_ARMOR)
                    || source.is(com.tacz.guns.init.ModDamageTypes.BULLET_VOID_IGNORE_ARMOR);
            double factor = TranslationTable.active().factorFor(category, armorIgnoreHalf, engineType) * globalScale;
            float scaled = (float) (amount * factor);

            DamageSource synthetic = category == Category.EXPLOSIVE
                    // SBW's own RPG/grenade ordnance has no armor-ignoring variant of its direct hit -
                    // one channel regardless of which TaCZ half this call came from.
                    ? ModDamageTypes.causeProjectileHitDamage(hull.level().registryAccess(), bullet, attacker)
                    : armorIgnoreHalf
                            ? ModDamageTypes.causeGunFireAbsoluteDamage(hull.level().registryAccess(), bullet, attacker)
                            : ModDamageTypes.causeGunFireDamage(hull.level().registryAccess(), bullet, attacker);

            float result = modifier.compute(synthetic, scaled);
            debugLog(gunId, category, factor, armorIgnoreHalf ? "AP" : "normal", amount, scaled, result);
            return result;
        } catch (Exception e) {
            WarnOnce.warn(LOGGER, "sewv-ballistics-translate-error",
                    "[sewv] Ballistic translation failed for a TaCZ hit - passing it through unscaled.", e);
            return null;
        }
    }

    private static void debugLog(ResourceLocation gunId, Category category, double factor, String half,
                                 float in, float out, float result) {
        if (!ModGameRules.server(ModGameRules.BALLISTIC_TRANSLATION_DEBUG)) return;
        LOGGER.info("[sewv-ballistics] {} -> {} factor={} half={} {}->{} => {}",
                gunId, category, String.format(Locale.ROOT, "%.3f", factor), half, in, out, result);
    }
}
