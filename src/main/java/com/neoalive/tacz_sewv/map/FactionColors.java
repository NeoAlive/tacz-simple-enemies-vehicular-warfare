package com.neoalive.tacz_sewv.map;

import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.server.MinecraftServer;

import com.neoalive.tacz_sewv.compat.OpenPacCompat;
import com.neoalive.tacz_sewv.config.ClientConfig;
import com.neoalive.tacz_sewv.crew.CrewFacts;

/**
 * Null-safe overlay/map colour chain (Stage 2):
 * <ol>
 *   <li>OpenPAC claim/party colour when present</li>
 *   <li>config default when OpenPAC is present but has no colour</li>
 *   <li>config default when OpenPAC is absent</li>
 * </ol>
 */
public final class FactionColors {

    private static volatile int ruArgb = 0xFFFF5555;
    private static volatile int usArgb = 0xFF5555FF;
    private static volatile int pmcArgb = 0xFF55FF55;

    private FactionColors() {}

    /** ARGB from client config for a SEM faction. */
    public static int configArgb(CrewFacts.Faction faction) {
        return switch (faction) {
            case RU -> ruArgb;
            case US -> usArgb;
            case PMC -> pmcArgb;
        };
    }

    /** Refresh client-config colours after Forge has baked or reloaded the config. */
    public static void refreshConfigArgb() {
        ruArgb = readConfigColor(ClientConfig.COLOR_RU, 0xFFFF5555);
        usArgb = readConfigColor(ClientConfig.COLOR_US, 0xFF5555FF);
        pmcArgb = readConfigColor(ClientConfig.COLOR_PMC, 0xFF55FF55);
    }

    private static int readConfigColor(net.minecraftforge.common.ForgeConfigSpec.ConfigValue<String> value, int fallback) {
        try {
            return ClientConfig.parseColor(value.get(), fallback);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    /**
     * Server-side resolve: OpenPAC RGB (no alpha) or {@code null} to mean “caller should use config”.
     */
    @Nullable
    public static Integer openPacRgb(@Nullable MinecraftServer server, @Nullable UUID playerId) {
        if (server == null || playerId == null) return null;
        return OpenPacCompat.partyColor(server, playerId);
    }

    /**
     * Wire tint for a marker: packed {@code 0xRRGGBB} when OpenPAC supplies one, else {@code 0}
     * (client falls back to {@link #configArgb}).
     */
    public static int wireTint(@Nullable MinecraftServer server, @Nullable UUID playerId) {
        Integer rgb = openPacRgb(server, playerId);
        return rgb == null ? 0 : (rgb & 0xFFFFFF);
    }

    /** Client: prefer server tint when non-zero, else config. */
    public static int displayArgb(CrewFacts.Faction faction, int wireTint) {
        if (wireTint != 0) return 0xFF000000 | (wireTint & 0xFFFFFF);
        return configArgb(faction);
    }
}
