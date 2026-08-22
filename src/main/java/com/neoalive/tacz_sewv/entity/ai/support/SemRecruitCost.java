package com.neoalive.tacz_sewv.entity.ai.support;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Defensive reader of SEM's live recruit-table economy: {@code MiscConfig.RECRUIT_ITEM} and
 * {@code MiscConfig.RECRUIT_PRICE}, cached at server startup and refreshed on every world load.
 *
 * <p><b>Why defensive reflection?</b> CLAUDE.md documents that SEM moves config fields between
 * versions — a compile-time reference to {@code MiscConfig.RECRUIT_PRICE} would crash with
 * {@code NoSuchFieldError} (a {@code LinkageError}, not caught by {@code catch (Exception)}) on
 * every build except the exact dev jar this mod was compiled against. Per the established pattern
 * ({@link com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting#readSemFriendlyFlag(String)}),
 * both values are read via reflection with a fallback, and cached into {@code volatile} statics
 * refreshed on {@code ServerAboutToStartEvent}.
 *
 * <p><b>Fallback on failure:</b> {@link #Items#EMERALD EMERALD} and hardcoded price {@code 16}
 * (matching the stale reference-repo's old default), with a loud warning logged so a future SEM
 * move shows up as "recruitment disabled" rather than silently (a search for that specific error
 * message in the logs is how we'd discover the move). Capture price to the player is always
 * {@code Math.max(0, recruitPrice / 2)} (floor division).
 */
public class SemRecruitCost {
    private static final Logger LOGGER = LogManager.getLogger();

    /**
     * Where SimpleEnemyMod keeps its recruit economy config. Only one known home today; future
     * SEM versions should be added to the front of this list if the class moves again.
     */
    private static final String[] MISC_CONFIG_CLASSES = {
            "net.nekoyuni.SimpleEnemyMod.config.common.MiscConfig",
    };

    private static volatile int recruitPrice = 16; // fallback default
    private static volatile Item recruitItem = Items.EMERALD; // fallback default

    public static void refresh() {
        Integer price = readRecruitPrice();
        Item item = readRecruitItem();

        if (price != null) {
            recruitPrice = price;
        }
        if (item != null) {
            recruitItem = item;
        }

        if (price == null || item == null) {
            LOGGER.warn(
                    "SimpleEnemyMod's recruit economy (RECRUIT_PRICE, getRecruitItem) was not found "
                            + "in any known config class: {}. Using fallback (emerald, cost 16). "
                            + "SEM has most likely moved its config again — update "
                            + "SemRecruitCost.MISC_CONFIG_CLASSES with the new home.",
                    String.join(", ", MISC_CONFIG_CLASSES));
        }
    }

    /**
     * The number of items a player must hold to recruit a PMC. Capture price is this value halved.
     */
    public static int capturePrice() {
        return Math.max(0, recruitPrice / 2);
    }

    /**
     * The item type a player must hold (in the quantity of {@link #recruitPrice}) to recruit a PMC.
     * Non-null guaranteed; falls back to {@link Items#EMERALD} if SEM's config cannot be read.
     */
    public static Item currencyItem() {
        return recruitItem;
    }

    /**
     * Read {@code MiscConfig.RECRUIT_PRICE} via reflection. Returns {@code null} if this SEM build
     * does not expose it (older version, or class moved and list not updated).
     *
     * <p>{@code Throwable} — not {@code Exception} — is the correct catch: {@code NoSuchFieldError}
     * and {@code IllegalStateException} (if config not baked yet) are the expected failure modes,
     * plus the two exceptions that can still occur in theory.
     */
    private static Integer readRecruitPrice() {
        for (String className : MISC_CONFIG_CLASSES) {
            try {
                Object holder = Class.forName(className).getField("RECRUIT_PRICE").get(null);
                if (holder instanceof ForgeConfigSpec.IntValue value) {
                    Object price = value.get();
                    if (price instanceof Integer i) {
                        return i;
                    }
                }
            } catch (Throwable ignored) {
                // Not this SEM layout (ClassNotFound/NoSuchField), or the config is not baked yet
                // (IllegalState) — try the next known home.
            }
        }
        return null;
    }

    /**
     * Read {@code MiscConfig.getRecruitItem()} via reflection. Returns {@code null} if this SEM
     * build does not expose it.
     */
    private static Item readRecruitItem() {
        for (String className : MISC_CONFIG_CLASSES) {
            try {
                Object item = Class.forName(className).getMethod("getRecruitItem").invoke(null);
                if (item instanceof Item i) {
                    return i;
                }
            } catch (Throwable ignored) {
                // Not this SEM layout, or the config is not baked yet — try the next.
            }
        }
        return null;
    }
}
