package com.neoalive.tacz_sewv.compat;

import net.minecraftforge.fml.ModList;

/**
 * Presence gate for the Coltan softcompat (SBW → GemRender vehicle/armor bridge). Imports nothing
 * from {@code com.neoalive.coltan.*} — only {@link ModList}.
 *
 * <p>Callers must check {@link #bridgeActive()} before touching {@link ColtanSkinBridge} /
 * {@link ColtanArmorBridge} / held-gun draw; those classes load Coltan types reflectively, and
 * linking them when Coltan or GemRender is absent would be pointless noise even though reflection
 * itself would not crash the class verifier the way a hard import would.
 */
public final class ColtanCompat {

    public static final String MODID = "coltan";
    public static final String GEMRENDER_MODID = "gemrender";

    private ColtanCompat() {}

    public static boolean present() {
        return ModList.get().isLoaded(MODID);
    }

    /** True when both halves of the bridge stack are installed. */
    public static boolean bridgeActive() {
        return present() && ModList.get().isLoaded(GEMRENDER_MODID);
    }
}
