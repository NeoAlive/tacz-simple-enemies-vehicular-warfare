package com.neoalive.tacz_sewv.config;

import java.util.List;

/**
 * One-shot repairs for values corrupted by earlier UI bugs. Safe to run every world load.
 */
public final class ConfigMigration {

    private static final String NVG_TRUNCATED = "superbwarfare:thermal_imaging_go";
    private static final String NVG_CORRECT = "superbwarfare:thermal_imaging_goggles";

    private ConfigMigration() {}

    public static void applyServer() {
        repairTruncatedNvgList();
    }

    private static void repairTruncatedNvgList() {
        List<? extends String> items = SewvConfig.NVG_ELIGIBLE_ITEMS.get();
        if (items.size() != 1 || !NVG_TRUNCATED.equals(items.get(0))) {
            return;
        }
        SewvConfig.NVG_ELIGIBLE_ITEMS.set(List.of(NVG_CORRECT));
        ConfigPersistence.saveServer();
    }
}
