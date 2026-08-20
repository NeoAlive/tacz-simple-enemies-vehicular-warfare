package com.neoalive.tacz_sewv.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

/**
 * Soft-compat facade for <b>PlayerReviveMod</b> ({@code playerrevive}, CreativeMD).
 *
 * <p>This is the <b>only</b> SEWV class allowed to touch {@code team.creative.playerrevive.*}. Every
 * other call site goes through the neutral methods here. PlayerReviveMod types live exclusively in the
 * private {@link Access} nested class, which is classloaded only after {@link #isLoaded()} returns true.
 *
 * <p>{@code team.creative.playerrevive.server.PlayerReviveServer.isBleeding(Player)} /
 * {@code .revive(Player)} are plain static methods typed only on vanilla {@code Player} — no
 * CreativeCore types leak into either signature, no packets are involved, and no capability
 * boilerplate is needed on this side. PlayerReviveMod's own {@code revivingPlayers()} multi-helper
 * progress accumulator is {@code Player}-typed (its interact flow requires {@code Player instanceof}
 * on both sides), so an NPC cannot join it; {@code PlayerReviveGoal} does its own short channel and
 * calls {@link #revive} once instead.
 *
 * <p>Off the run classpath by default (opt in with {@code ./gradlew runClient -PwithPlayerRevive}),
 * same shape as {@code Extermination}/{@code Configured} — see build.gradle.
 */
public final class PlayerReviveCompat {

    public static final String MODID = "playerrevive";

    private static final Logger LOGGER = LogUtils.getLogger();

    private PlayerReviveCompat() {}

    public static boolean isLoaded() {
        return ModList.get().isLoaded(MODID);
    }

    public static void reportAvailability() {
        if (isLoaded()) {
            LOGGER.info("PlayerReviveMod soft-compat available (mod id {}) — PMC auto-revive enabled", MODID);
        } else {
            LOGGER.info("PlayerReviveMod absent — soft-compat facade idle; PMC auto-revive goal not installed");
        }
    }

    /** Whether {@code player} is currently downed/bleeding out. False (never revivable) when absent. */
    public static boolean isDowned(Player player) {
        if (!isLoaded() || player == null) return false;
        return Access.isBleeding(player);
    }

    /** Revives {@code player} — resets bleeding, applies configured effects, restores health. No-op when absent. */
    public static void revive(Player player) {
        if (!isLoaded() || player == null) return;
        Access.revive(player);
    }

    private static final class Access {

        private Access() {}

        static boolean isBleeding(Player player) {
            return team.creative.playerrevive.server.PlayerReviveServer.isBleeding(player);
        }

        static void revive(Player player) {
            team.creative.playerrevive.server.PlayerReviveServer.revive(player);
        }
    }
}
