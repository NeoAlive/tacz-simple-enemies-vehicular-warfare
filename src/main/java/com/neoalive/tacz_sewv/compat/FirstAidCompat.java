package com.neoalive.tacz_sewv.compat;

import java.lang.reflect.Method;

import com.mojang.logging.LogUtils;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

/**
 * Soft compat for First Aid ({@code firstaid}). Reflective, no compile dependency.
 *
 * <p>First Aid swaps a player's {@code SynchedEntityData} for a wrapper that turns every
 * {@code setHealth} into damage or healing on its body parts. Its own PlayerRevive handler sets the
 * wrapper's {@code beingRevived} flag at knockout so PlayerRevive's revive {@code setHealth} is ignored,
 * then heals the critical parts on {@code PlayerRevivedEvent}. That handler only knocks out when the
 * server is published, so in singleplayer with {@code bleedInSingleplayer} the flag is never set:
 * the revive {@code setHealth(healthAfter)} reads as damage (bleedingHealth 10 down to 2), lands on a
 * critical part still at 0 and kills the player.
 */
public final class FirstAidCompat {

    public static final String MODID = "firstaid";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String WRAPPER_CLASS = "ichttt.mods.firstaid.common.SynchedEntityDataWrapper";

    private static volatile Method toggleBeingRevived;
    private static volatile boolean broken;

    private FirstAidCompat() {}

    public static boolean isLoaded() {
        return ModList.get().isLoaded(MODID);
    }

    /** Runs {@code action} with First Aid ignoring health writes on {@code player}. */
    public static void withHealthWritesIgnored(Player player, Runnable action) {
        boolean toggled = isLoaded() && setBeingRevived(player, true);
        try {
            action.run();
        } finally {
            if (toggled) setBeingRevived(player, false);
        }
    }

    private static boolean setBeingRevived(Player player, boolean status) {
        if (broken) return false;
        SynchedEntityData data = player.getEntityData();
        if (!WRAPPER_CLASS.equals(data.getClass().getName())) return false;
        try {
            Method m = toggleBeingRevived;
            if (m == null) {
                m = data.getClass().getMethod("toggleBeingRevived", boolean.class);
                toggleBeingRevived = m;
            }
            m.invoke(data, status);
            return true;
        } catch (Throwable t) {
            broken = true;
            LOGGER.warn("First Aid revive compat disabled, wrapper API changed", t);
            return false;
        }
    }
}
