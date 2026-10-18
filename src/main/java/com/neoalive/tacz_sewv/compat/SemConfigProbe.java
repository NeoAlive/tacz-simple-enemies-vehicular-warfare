package com.neoalive.tacz_sewv.compat;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Reads SEM config values this mod only reports on, never acts on.
 *
 * <p>Reflective by class-name list for the same reason as
 * {@code VehicleTargeting.readSemFriendlyFlag}: SEM moves its config fields between builds and
 * a compile-time reference dies with {@code NoSuchFieldError} on every other one. Hence
 * {@code catch (Throwable)}, which also absorbs an unbaked config's {@code IllegalStateException}.
 */
public final class SemConfigProbe {

    private static final String[] TACTICAL_AI_CONFIG_CLASSES = {
            "net.nekoyuni.SimpleEnemyMod.config.common.TacticalAiConfig",
    };

    private SemConfigProbe() {}

    /**
     * Whether SEM's ladder-aware fallback routing is on. Unreadable counts as on, so a tip gated
     * on this is shown rather than silently dropped when SEM moves the field.
     */
    public static boolean ladderFallbackEnabled() {
        for (String className : TACTICAL_AI_CONFIG_CLASSES) {
            try {
                Object holder = Class.forName(className).getField("ENABLE_LADDER_FALLBACK").get(null);
                if (holder instanceof ForgeConfigSpec.ConfigValue<?> value
                        && value.get() instanceof Boolean flag) {
                    return flag;
                }
            } catch (Throwable ignored) {
                // Not this SEM layout, or not baked yet: try the next known home.
            }
        }
        return true;
    }
}
