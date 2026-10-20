package com.neoalive.tacz_sewv.config;

import javax.annotation.Nullable;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

import com.neoalive.tacz_sewv.map.FactionColors;
import com.neoalive.tacz_sewv.platform.DistExecutor;

/**
 * NeoForge port: Forge exposed {@code ConfigTracker.configSets()} and {@code ModConfig.save()}; NeoForge
 * has neither, but a loaded {@link ModConfigSpec} saves its own file. "Is it a real file config" became
 * "is the spec loaded" — a server config is only loaded while a world is.
 */
public final class ConfigPersistence {

    /** What {@code ModContainer.registerConfig} handed back; set from {@code TaczSewv}. */
    @Nullable
    private static ModConfig serverConfig;

    private ConfigPersistence() {}

    public static void remember(ModConfig.Type type, ModConfig config) {
        if (type == ModConfig.Type.SERVER) serverConfig = config;
    }

    /** The server config's resolved file, when one is loaded; null otherwise. */
    @Nullable
    public static java.nio.file.Path serverConfigPath() {
        ModConfig cfg = serverConfig;
        if (cfg == null || !SewvConfig.SPEC.isLoaded()) return null;
        return cfg.getFullPath();
    }

    public static void saveClient() {
        if (save(ClientConfig.SPEC)) {
            FactionColors.refreshConfigArgb();
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.neoalive.tacz_sewv.client.NotificationHud.refreshScreenTimeCache());
        }
    }

    public static void saveServer() {
        save(SewvConfig.SPEC);
    }

    private static boolean save(ModConfigSpec spec) {
        if (!spec.isLoaded()) return false;
        spec.save();
        return true;
    }
}
