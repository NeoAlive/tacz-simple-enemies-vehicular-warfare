package com.neoalive.tacz_sewv.entity.ai.support;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.nekoyuni.SimpleEnemyMod.config.common.MiscConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Reader of SEM's recruit-table economy ({@link MiscConfig#RECRUIT_ITEM} /
 * {@link MiscConfig#RECRUIT_PRICE}), cached at server startup.
 */
public class SemRecruitCost {
    private static final Logger LOGGER = LogManager.getLogger();

    private static volatile int recruitPrice = 16;
    private static volatile Item recruitItem = Items.EMERALD;

    public static void refresh() {
        try {
            recruitPrice = MiscConfig.RECRUIT_PRICE.get();
            Item item = MiscConfig.getRecruitItem();
            if (item != null) {
                recruitItem = item;
            }
        } catch (Throwable t) {
            LOGGER.warn("Failed to read MiscConfig recruit economy; using emerald/16 fallback", t);
            recruitPrice = 16;
            recruitItem = Items.EMERALD;
        }
    }

    public static int capturePrice() {
        return Math.max(0, recruitPrice / 2);
    }

    public static Item currencyItem() {
        return recruitItem;
    }
}
