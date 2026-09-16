package com.neoalive.tacz_sewv.compat;

import net.minecraftforge.fml.ModList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Presence gate for Neo Arms (aircraft carrier). Imports nothing from {@code tech.neoarms.*}.
 * Typed access lives in {@link NeoArmsCarrierAccess} behind reflection so a checkout without
 * {@code libs/neoarms.jar} still compiles.
 */
public final class NeoArmsCompat {

    public static final String MODID = "neoarms";
    public static final String CARRIER_ID = "neoarms:aircraft_carrier";

    private static final Logger LOGGER = LogManager.getLogger();
    private static boolean logged;

    private NeoArmsCompat() {}

    public static boolean present() {
        return ModList.get().isLoaded(MODID);
    }

    public static void bootstrap() {
        if (!logged) {
            logged = true;
            if (present()) {
                LOGGER.info("Neo Arms soft-compat available — static carriers can act as airports");
            } else {
                LOGGER.info("Neo Arms absent — carrier soft-compat idle");
            }
        }
    }
}
