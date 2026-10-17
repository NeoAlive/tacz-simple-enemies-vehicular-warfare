package com.neoalive.tacz_sewv.compat;

import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Softcompat gate for Phayriosis Two. Unlike Spore, Phayriosis defines no custom
 * {@code MobCategory} at all — every hostile it ships (the {@code converted_*} line,
 * {@code phayrector}, {@code monstrosity}, {@code abhorrence}, {@code gory_mass}, …) is
 * registered straight onto vanilla {@code MobCategory.MONSTER}, so a category name can't tell
 * one apart from a plain zombie. The only usable signal is the entity registry's own
 * {@code phayriosis_two} namespace.
 *
 * <p>Resolving that namespace is a {@code ForgeRegistries.ENTITY_TYPES.getKey(type)} registry
 * lookup, and {@link com.neoalive.tacz_sewv.entity.ai.goal.SoftEnemyTargetPriorityGoal} calls
 * this once per candidate on every scan — so the per-{@code EntityType} answer is cached after
 * the first resolve instead of being re-looked-up per entity per scan. There are only ever as
 * many distinct entity types in a world as are registered, so the cache is small and never
 * needs eviction.
 */
public final class PhayriosisCompat {

    public static final String MODID = "phayriosis_two";

    private static final Map<EntityType<?>, Boolean> NAMESPACE_CACHE = new IdentityHashMap<>();

    private PhayriosisCompat() {}

    public static boolean present() {
        return ModList.get().isLoaded(MODID);
    }

    /**
     * {@link SoftEnemyTargeting.Tier#MINOR} for anything registered under the
     * {@code phayriosis_two} namespace — the same "slight nudge above vanilla MONSTER" band as
     * Spore's {@code infected} category, since Phayriosis has no tier of its own to distinguish
     * finer than "one of ours".
     */
    public static SoftEnemyTargeting.Tier tierOf(LivingEntity target) {
        if (!present()) return SoftEnemyTargeting.Tier.NONE;
        return isPhayriosisType(target.getType())
                ? SoftEnemyTargeting.Tier.MINOR
                : SoftEnemyTargeting.Tier.NONE;
    }

    private static boolean isPhayriosisType(EntityType<?> type) {
        Boolean cached = NAMESPACE_CACHE.get(type);
        if (cached != null) return cached;
        ResourceLocation key = ForgeRegistries.ENTITY_TYPES.getKey(type);
        boolean result = key != null && MODID.equals(key.getNamespace());
        NAMESPACE_CACHE.put(type, result);
        return result;
    }
}
