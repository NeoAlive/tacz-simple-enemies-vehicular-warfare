package com.neoalive.tacz_sewv.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.config.ConfigTracker;
import net.minecraftforge.fml.config.ModConfig;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.map.FactionColors;

public final class ConfigPersistence {

    private ConfigPersistence() {}

    public static void saveClient() {
        ModConfig cfg = findConfig(ModConfig.Type.CLIENT);
        if (cfg != null) {
            cfg.save();
            FactionColors.refreshConfigArgb();
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.neoalive.tacz_sewv.client.NotificationHud.refreshScreenTimeCache());
        }
    }

    public static void saveServer() {
        ModConfig cfg = findConfig(ModConfig.Type.SERVER);
        if (cfg != null) {
            cfg.save();
        }
    }

    private static ModConfig findConfig(ModConfig.Type type) {
        for (ModConfig cfg : ConfigTracker.INSTANCE.configSets().get(type)) {
            if (TaczSewv.MODID.equals(cfg.getModId())) {
                CommentedConfig data = cfg.getConfigData();
                if (data instanceof CommentedFileConfig) {
                    return cfg;
                }
            }
        }
        return null;
    }
}
