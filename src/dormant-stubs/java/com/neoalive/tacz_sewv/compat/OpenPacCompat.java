// NeoForge port branch: DORMANT-INTEGRATION STUB. The real class (same path under src/main/java)
// is excluded from compilation by dormantSources in build.gradle. Same public surface, constant
// "absent" answers; selfCheckDormantStubs keeps the two in step.
package com.neoalive.tacz_sewv.compat;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

public final class OpenPacCompat {

    public static final String MODID = "openpartiesandclaims";

    private static final Logger LOGGER = LogUtils.getLogger();

    private OpenPacCompat() {}

    public static boolean isLoaded() {
        return false;
    }

    public static void reportAvailability() {
        LOGGER.info("Open Parties and Claims soft-compat dormant on this build (not ported to NeoForge yet)");
    }

    public static UUID partyOwnerId(MinecraftServer server, UUID playerId) {
        return null;
    }

    public static boolean allied(MinecraftServer server, UUID playerA, UUID playerB) {
        return false;
    }

    public static UUID claimOwnerId(ServerLevel level, BlockPos pos) {
        return null;
    }

    public static UUID claimOwnerId(ServerLevel level, int chunkX, int chunkZ) {
        return null;
    }

    public static Integer partyColor(MinecraftServer server, UUID playerId) {
        return null;
    }

    public static String factionName(MinecraftServer server, UUID playerId) {
        return null;
    }

    public static List<String> factionNames(MinecraftServer server) {
        return List.of();
    }

    public static UUID partyOwnerIdByFactionName(MinecraftServer server, String factionName) {
        return null;
    }

    public static Set<Long> selfClaimedChunks(ServerLevel level, UUID playerId) {
        return Set.of();
    }

    /** What a limit-respecting claim attempt came to. Neutral, so callers never see an OpenPAC type. */
    public enum ClaimOutcome {
        CLAIMED,
        ALREADY_OURS,
        LIMIT,
        NOT_CLAIMABLE,
        TAKEN,
        OTHER
    }

    public static ClaimOutcome tryClaim(ServerLevel level, UUID ownerId, int chunkX, int chunkZ) {
        return ClaimOutcome.OTHER;
    }

    public static int claimHeadroom(MinecraftServer server, UUID ownerId) {
        return 0;
    }

    public static boolean claimable(ServerLevel level) {
        return false;
    }

    public static boolean claim(ServerLevel level, UUID ownerId, int chunkX, int chunkZ) {
        return false;
    }

    public static void unclaim(ServerLevel level, int chunkX, int chunkZ) {
    }
}
