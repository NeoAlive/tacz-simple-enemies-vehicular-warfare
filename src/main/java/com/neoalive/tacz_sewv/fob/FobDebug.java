package com.neoalive.tacz_sewv.fob;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.init.ModGameRules;

/**
 * FOB pipeline logging, gated on {@code /gamerule sewvDebugFob}.
 */
public final class FobDebug {

    private static final Logger LOGGER = LogUtils.getLogger();

    private FobDebug() {}

    public static void log(String message, Object... args) {
        if (!ModGameRules.server(ModGameRules.FOB_DEBUG)) return;
        LOGGER.info("[fob] " + message, args);
    }

    public static void logEntity(Entity entity, String message, Object... args) {
        if (!ModGameRules.server(ModGameRules.FOB_DEBUG)) return;
        Object[] full = new Object[args.length + 1];
        full[0] = entity.getStringUUID().substring(0, 8);
        System.arraycopy(args, 0, full, 1, args.length);
        LOGGER.info("[fob][{}] " + message, full);
    }
}
