package com.neoalive.tacz_sewv.compat;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Presence gate for the Enhanced Falling Trees softcompat. Deliberately imports NOTHING from
 * {@code me.adda.enhanced_falling_trees.*} — only {@link ModList}/{@link ForgeRegistries}, plus
 * vanilla types.
 *
 * <p>This split from {@link EnhancedFallingTreesFeller} (which does the real work and imports
 * every EFT class) is load-bearing, not stylistic. JVM class verification is per-class: calling
 * ANY method on a class triggers linking of that WHOLE class, which requires verifying every one
 * of its OTHER methods too — including ones never actually reached at runtime. A class that mixes
 * a presence check with methods referencing an absent mod's types (as this class used to) cannot
 * even answer "is it present?" when that mod is absent: linking fails during verification of the
 * unreachable methods, and {@code NoClassDefFoundError} is thrown from the presence check itself —
 * confirmed by a real crash where the trace pointed at the {@code available()} call site. The same
 * two-class shape is why {@code XaeroMapCompat}/{@code KomodoMixinPlugin} are safe: the "is it
 * loaded" check never shares a class with code that references the optional mod's own types.
 *
 * <p>Every caller MUST check {@link #available()} true BEFORE calling anything on {@link
 * EnhancedFallingTreesFeller} — that is no longer just a perf optimization, it is what keeps the
 * Feller class's {@code invokestatic} instructions unreached (and therefore that class unlinked)
 * when Enhanced Falling Trees is absent.
 */
public final class EnhancedFallingTreesCompat {

    public static final String MODID = "efallingtrees";

    static final ResourceLocation TREE_ENTITY_ID = new ResourceLocation(MODID, "tree");

    /** Only latch {@code true}; an early false during registry bootstrap must not stick forever. */
    private static boolean resolvedPresent;
    private static boolean available;

    private EnhancedFallingTreesCompat() {}

    public static boolean present() {
        return ModList.get().isLoaded(MODID);
    }

    public static boolean available() {
        resolve();
        return available;
    }

    private static void resolve() {
        if (resolvedPresent && available) return;
        if (!present()) {
            resolvedPresent = true;
            available = false;
            return;
        }
        available = ForgeRegistries.ENTITY_TYPES.containsKey(TREE_ENTITY_ID);
        if (available) {
            resolvedPresent = true;
        }
    }
}
