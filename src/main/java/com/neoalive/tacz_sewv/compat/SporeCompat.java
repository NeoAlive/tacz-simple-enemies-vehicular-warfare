package com.neoalive.tacz_sewv.compat;

import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.fml.ModList;

/**
 * Softcompat gate for Spore's three custom {@code MobCategory} instances, built by
 * {@code com.Harbinger.Spore.Core.Sentities}'s static initializer via
 * {@code MobCategory.create(name, id, classLimit, isFriendly, isPersistent, noDespawnDistance)}:
 * {@code infected} (classLimit = the mod's own {@code mob_cap} config, default 40),
 * {@code organoid} (20) and {@code experiments} (15) — all below vanilla {@code MONSTER}'s 70.
 *
 * <p>No Spore class is ever referenced (no compile dependency, no reflection): a category is
 * identified purely by {@link net.minecraft.world.entity.MobCategory#getName()}, which is a
 * plain string already exposed on every {@code EntityType}. That also means this needs no
 * {@code ModList} check to stay safe — if Spore isn't loaded, nothing in the world ever carries
 * these category names — but the check is kept anyway as the same self-documenting early-out
 * every other compat class in this package uses.
 */
public final class SporeCompat {

    public static final String MODID = "spore";

    private static final String CATEGORY_ORGANOID = "organoid";
    private static final String CATEGORY_EXPERIMENTS = "experiments";
    private static final String CATEGORY_INFECTED = "infected";

    private SporeCompat() {}

    public static boolean present() {
        return ModList.get().isLoaded(MODID);
    }

    /**
     * {@link SoftEnemyTargeting.Tier#PRIME} for the hive/lab-tier categories ({@code organoid},
     * {@code experiments} — "important targets that define a gameplay"), {@link
     * SoftEnemyTargeting.Tier#MINOR} for the base {@code infected} category (a slight nudge
     * above vanilla {@code MONSTER}), {@link SoftEnemyTargeting.Tier#NONE} otherwise.
     */
    public static SoftEnemyTargeting.Tier tierOf(LivingEntity target) {
        if (!present()) return SoftEnemyTargeting.Tier.NONE;
        String category = target.getType().getCategory().getName();
        if (CATEGORY_ORGANOID.equals(category) || CATEGORY_EXPERIMENTS.equals(category)) {
            return SoftEnemyTargeting.Tier.PRIME;
        }
        if (CATEGORY_INFECTED.equals(category)) return SoftEnemyTargeting.Tier.MINOR;
        return SoftEnemyTargeting.Tier.NONE;
    }

    /**
     * Whether {@link com.neoalive.tacz_sewv.util.WorldTargetPriority} should allow
     * {@code categoryName} by default on a fresh per-faction list, same as vanilla
     * {@code monster}. Without this, {@code infected}/{@code organoid}/{@code experiments}
     * would sit in the default EXCLUDE set (that file's default is "monster allowed, everything
     * else excluded"), and {@link com.neoalive.tacz_sewv.entity.ai.goal.SoftEnemyTargetPriorityGoal}
     * would never get a chance to run — {@code categoryAllowed} rejects the target before the
     * tier scorer ever sees it. This only changes what a NEW per-faction list defaults to; a
     * server that already customised its list via {@code /sewv targetPriority} is untouched.
     */
    public static boolean defaultAllowsCategory(String categoryName) {
        if (!present()) return false;
        return CATEGORY_ORGANOID.equals(categoryName)
                || CATEGORY_EXPERIMENTS.equals(categoryName)
                || CATEGORY_INFECTED.equals(categoryName);
    }
}
