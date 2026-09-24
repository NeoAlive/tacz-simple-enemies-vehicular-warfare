package com.neoalive.tacz_sewv.compat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.debug.SewvDiag;

/**
 * Soft-compat facade for <b>Open Parties and Claims</b> ({@code openpartiesandclaims}).
 *
 * <p>This is the <b>only</b> SEWV class allowed to touch {@code xaero.pac.*}. Every other call site
 * goes through the neutral methods here. OpenPAC types live exclusively in the private
 * {@link Access} nested class, which is classloaded only after {@link #isLoaded()} returns true.
 *
 * <h2>Stage 0 API freeze (SOFTCOMPAT-verified)</h2>
 * <ul>
 *   <li>{@code OpenPACServerAPI.get(server)}</li>
 *   <li>{@code IPartyManagerAPI.getPartyByMember(UUID)} / {@code getAllStream()} / {@code isAlly}</li>
 *   <li>{@code IServerClaimsManagerAPI.get}/{@code claim}/{@code unclaim}</li>
 *   <li>Colour: {@code IServerPlayerClaimInfoAPI.getClaimsColor()} (party {@code getColor} may be -1)</li>
 *   <li>OpenPAC has ally/non-ally only — SEWV diplomacy (Stage 4) owns war/peace</li>
 * </ul>
 *
 * <p>Absent-path verification: {@code ./gradlew runServerDev -PnoOpenPac}.
 */
public final class OpenPacCompat {

    public static final String MODID = "openpartiesandclaims";

    private static final Logger LOGGER = LogUtils.getLogger();

    /** OpenPAC main-config sub-index for direct {@code claim(...)} — {@code PlayerConfig.getSubIndex()} is -1. */
    private static final int MAIN_SUB_CONFIG = -1;

    private OpenPacCompat() {}

    public static boolean isLoaded() {
        return ModList.get().isLoaded(MODID);
    }

    public static void reportAvailability() {
        if (isLoaded()) {
            LOGGER.info("OpenPAC soft-compat available (mod id {})", MODID);
            SewvDiag.claim("boot MAIN_SUB_CONFIG={} (must be -1 for OpenPAC main config)", MAIN_SUB_CONFIG);
        } else {
            LOGGER.info("OpenPAC absent — soft-compat facade idle; SEWV uses config-only faction behaviour");
        }
    }

    @Nullable
    public static UUID partyOwnerId(MinecraftServer server, UUID playerId) {
        if (!isLoaded() || server == null || playerId == null) return null;
        return Access.partyOwnerId(server, playerId);
    }

    public static boolean allied(MinecraftServer server, UUID playerA, UUID playerB) {
        if (!isLoaded() || server == null || playerA == null || playerB == null) return false;
        if (playerA.equals(playerB)) return true;
        return Access.allied(server, playerA, playerB);
    }

    @Nullable
    public static UUID claimOwnerId(ServerLevel level, BlockPos pos) {
        if (!isLoaded() || level == null || pos == null) return null;
        return Access.claimOwnerId(level, pos.getX() >> 4, pos.getZ() >> 4);
    }

    /** Chunk-coord form for perimeter walks (no fake Y=64 BlockPos). */
    @Nullable
    public static UUID claimOwnerId(ServerLevel level, int chunkX, int chunkZ) {
        if (!isLoaded() || level == null) return null;
        return Access.claimOwnerId(level, chunkX, chunkZ);
    }

    @Nullable
    public static Integer partyColor(MinecraftServer server, UUID playerId) {
        if (!isLoaded() || server == null || playerId == null) return null;
        return Access.partyColor(server, playerId);
    }

    /**
     * Display name of the OpenPAC party {@code playerId} belongs to, or {@code null}.
     */
    @Nullable
    public static String factionName(MinecraftServer server, UUID playerId) {
        if (!isLoaded() || server == null || playerId == null) return null;
        return Access.factionName(server, playerId);
    }

    public static List<String> factionNames(MinecraftServer server) {
        if (!isLoaded() || server == null) return List.of();
        return Access.factionNames(server);
    }

    /**
     * Party owner UUID for a display name (custom PARTY_NAME or default), case-insensitive.
     */
    @Nullable
    public static UUID partyOwnerIdByFactionName(MinecraftServer server, String factionName) {
        if (!isLoaded() || server == null || factionName == null || factionName.isBlank()) return null;
        return Access.partyOwnerIdByFactionName(server, factionName);
    }

    /**
     * Chunks in {@code level} claimed by {@code playerId} or any member of their party, packed like
     * {@code ChunkPos.asLong}. Allies are deliberately excluded (Territory Mode V1: ally = not self).
     * Empty when OpenPAC is absent. Walks every claim, so callers cache it per sync, not per tick.
     */
    public static Set<Long> selfClaimedChunks(ServerLevel level, UUID playerId) {
        if (!isLoaded() || level == null || playerId == null) return Set.of();
        return Access.selfClaimedChunks(level, playerId);
    }

    /** What a limit-respecting claim attempt came to. Neutral, so callers never see an OpenPAC type. */
    public enum ClaimOutcome {
        /** The chunk was free and is now claimed. */
        CLAIMED,
        /** The chunk was already the owner's. */
        ALREADY_OURS,
        /** The owner is at their claim limit (or over it). */
        LIMIT,
        /** Claims are disabled, or the dimension cannot be claimed. */
        NOT_CLAIMABLE,
        /** Someone else holds the chunk: unclaim it first. */
        TAKEN,
        /** Anything else OpenPAC refused (a replacement in progress, no permission, ...). */
        OTHER
    }

    /**
     * Claims a chunk THROUGH OpenPAC's limits ({@code tryToClaim}, not the raw {@code claim} that Sweep &amp; Advance
     * uses, which bypasses them). {@code replace} is false, so another owner's chunk answers {@link ClaimOutcome#TAKEN}
     * rather than being taken over. The result type is meaningful (unlike the raw call's return), so callers can tell a
     * limit hit from a refusal. {@link ClaimOutcome#OTHER} when OpenPAC is absent.
     */
    public static ClaimOutcome tryClaim(ServerLevel level, UUID ownerId, int chunkX, int chunkZ) {
        if (!isLoaded() || level == null || ownerId == null) return ClaimOutcome.OTHER;
        return Access.tryClaim(level, ownerId, chunkX, chunkZ);
    }

    /**
     * How many more chunks {@code ownerId} may claim: their full claim limit minus what they hold (the count OpenPAC
     * itself enforces, per owner and not party-wide). Negative when over the limit; 0 when OpenPAC is absent.
     */
    public static int claimHeadroom(MinecraftServer server, UUID ownerId) {
        if (!isLoaded() || server == null || ownerId == null) return 0;
        return Access.claimHeadroom(server, ownerId);
    }

    /** Whether claims are possible in this dimension at all. */
    public static boolean claimable(ServerLevel level) {
        return isLoaded() && level != null && Access.claimable(level);
    }

    /** Stage 5: claim chunk for {@code ownerId}. No-op when OpenPAC absent. */
    public static boolean claim(ServerLevel level, UUID ownerId, int chunkX, int chunkZ) {
        if (!isLoaded() || level == null || ownerId == null) {
            SewvDiag.claim("claim SKIPPED early isLoaded={} levelNull={} ownerNull={} chunk={},{} MAIN_SUB_CONFIG={}",
                    isLoaded(), level == null, ownerId == null, chunkX, chunkZ, MAIN_SUB_CONFIG);
            return false;
        }
        return Access.claim(level, ownerId, chunkX, chunkZ);
    }

    /** Stage 5: clear claim. No-op when OpenPAC absent. */
    public static void unclaim(ServerLevel level, int chunkX, int chunkZ) {
        if (!isLoaded() || level == null) {
            SewvDiag.claim("unclaim SKIPPED early isLoaded={} levelNull={} chunk={},{}",
                    isLoaded(), level == null, chunkX, chunkZ);
            return;
        }
        Access.unclaim(level, chunkX, chunkZ);
    }

    private static final class Access {

        private Access() {}

        @Nullable
        static UUID partyOwnerId(MinecraftServer server, UUID playerId) {
            var party = xaero.pac.common.server.api.OpenPACServerAPI.get(server)
                    .getPartyManager()
                    .getPartyByMember(playerId);
            if (party == null) return null;
            var owner = party.getOwner();
            return owner != null ? owner.getUUID() : null;
        }

        static boolean allied(MinecraftServer server, UUID playerA, UUID playerB) {
            var parties = xaero.pac.common.server.api.OpenPACServerAPI.get(server).getPartyManager();
            var partyA = parties.getPartyByMember(playerA);
            var partyB = parties.getPartyByMember(playerB);
            if (partyA == null || partyB == null) return false;
            if (partyA.getId().equals(partyB.getId())) return true;
            return partyA.isAlly(partyB.getId());
        }

        @Nullable
        static UUID claimOwnerId(ServerLevel level, int chunkX, int chunkZ) {
            ResourceLocation dimension = level.dimension().location();
            var claim = xaero.pac.common.server.api.OpenPACServerAPI.get(level.getServer())
                    .getServerClaimsManager()
                    .get(dimension, chunkX, chunkZ);
            return claim != null ? claim.getPlayerId() : null;
        }

        static ClaimOutcome tryClaim(ServerLevel level, UUID ownerId, int chunkX, int chunkZ) {
            var mgr = xaero.pac.common.server.api.OpenPACServerAPI.get(level.getServer()).getServerClaimsManager();
            // from = to = the chunk: OpenPAC's TOO_FAR check compares them, so a single chunk can never trip it.
            var result = mgr.tryToClaim(level.dimension().location(), ownerId, MAIN_SUB_CONFIG,
                    chunkX, chunkZ, chunkX, chunkZ, false);
            var type = result.getResultType();
            if (type == xaero.pac.common.claims.result.api.ClaimResult.Type.SUCCESSFUL_CLAIM) return ClaimOutcome.CLAIMED;
            if (type == xaero.pac.common.claims.result.api.ClaimResult.Type.ALREADY_CLAIMED) {
                var existing = result.getClaimResult();
                return existing != null && ownerId.equals(existing.getPlayerId()) ? ClaimOutcome.ALREADY_OURS : ClaimOutcome.TAKEN;
            }
            if (type == xaero.pac.common.claims.result.api.ClaimResult.Type.CLAIM_LIMIT_REACHED
                    || type == xaero.pac.common.claims.result.api.ClaimResult.Type.OVER_CLAIM_LIMIT) return ClaimOutcome.LIMIT;
            if (type == xaero.pac.common.claims.result.api.ClaimResult.Type.UNCLAIMABLE_DIMENSION
                    || type == xaero.pac.common.claims.result.api.ClaimResult.Type.CLAIMS_ARE_DISABLED) return ClaimOutcome.NOT_CLAIMABLE;
            return ClaimOutcome.OTHER;
        }

        static int claimHeadroom(MinecraftServer server, UUID ownerId) {
            var mgr = xaero.pac.common.server.api.OpenPACServerAPI.get(server).getServerClaimsManager();
            // hasPlayerInfo first: getPlayerInfo creates an empty record for an owner who has never claimed.
            int held = mgr.hasPlayerInfo(ownerId) ? mgr.getPlayerInfo(ownerId).getClaimCount() : 0;
            return mgr.getPlayerFullClaimLimit(ownerId) - held;
        }

        static boolean claimable(ServerLevel level) {
            return xaero.pac.common.server.api.OpenPACServerAPI.get(level.getServer()).getServerClaimsManager()
                    .isClaimable(level.dimension().location());
        }

        static Set<Long> selfClaimedChunks(ServerLevel level, UUID playerId) {
            var api = xaero.pac.common.server.api.OpenPACServerAPI.get(level.getServer());
            var claims = api.getServerClaimsManager();
            ResourceLocation dim = level.dimension().location();
            Set<UUID> ids = new LinkedHashSet<>();
            ids.add(playerId);
            var party = api.getPartyManager().getPartyByMember(playerId);
            if (party != null) party.getMemberInfoStream().forEach(m -> ids.add(m.getUUID()));

            Set<Long> out = new java.util.HashSet<>();
            for (UUID id : ids) {
                if (!claims.hasPlayerInfo(id)) continue;
                var perDim = claims.getPlayerInfo(id).getDimension(dim);
                if (perDim == null) continue;
                perDim.getStream().forEach(list -> list.getStream().forEach(p -> out.add(p.toLong())));
            }
            return out;
        }

        @Nullable
        static Integer partyColor(MinecraftServer server, UUID playerId) {
            UUID ownerId = partyOwnerId(server, playerId);
            if (ownerId == null) return null;
            var info = xaero.pac.common.server.api.OpenPACServerAPI.get(server)
                    .getServerClaimsManager()
                    .getPlayerInfo(ownerId);
            int color = info.getClaimsColor();
            if (color == 0) return null;
            return color & 0xFFFFFF;
        }

        @Nullable
        static String factionName(MinecraftServer server, UUID playerId) {
            var api = xaero.pac.common.server.api.OpenPACServerAPI.get(server);
            var party = api.getPartyManager().getPartyByMember(playerId);
            if (party == null) return null;
            return displayName(api, party);
        }

        static List<String> factionNames(MinecraftServer server) {
            var api = xaero.pac.common.server.api.OpenPACServerAPI.get(server);
            Set<String> names = new LinkedHashSet<>();
            api.getPartyManager().getAllStream().forEach(party -> {
                String name = displayName(api, party);
                if (name != null) names.add(name);
            });
            return names.isEmpty() ? List.of() : Collections.unmodifiableList(new ArrayList<>(names));
        }

        @Nullable
        static UUID partyOwnerIdByFactionName(MinecraftServer server, String factionName) {
            var api = xaero.pac.common.server.api.OpenPACServerAPI.get(server);
            var match = api.getPartyManager().getAllStream()
                    .filter(party -> factionName.equalsIgnoreCase(displayName(api, party)))
                    .findFirst()
                    .orElse(null);
            if (match == null || match.getOwner() == null) return null;
            return match.getOwner().getUUID();
        }

        static boolean claim(ServerLevel level, UUID ownerId, int chunkX, int chunkZ) {
            ResourceLocation dim = level.dimension().location();
            var mgr = xaero.pac.common.server.api.OpenPACServerAPI.get(level.getServer())
                    .getServerClaimsManager();
            boolean claimable = mgr.isClaimable(dim);
            BlockPos sample = new BlockPos(chunkX << 4, 64, chunkZ << 4);
            UUID beforeOwner = null;
            var beforeClaim = mgr.get(dim, sample);
            if (beforeClaim != null) beforeOwner = beforeClaim.getPlayerId();

            SewvDiag.claim(
                    "claim CALL dim={} chunk={},{} ownerUuid={} subConfigIndex={} (MAIN_SUB_CONFIG const) claimable={} beforeOwner={}",
                    dim, chunkX, chunkZ, ownerId, MAIN_SUB_CONFIG, claimable, beforeOwner);

            if (!claimable) {
                SewvDiag.claim("claim RESULT=NOOP_NOT_CLAIMABLE dim={} chunk={},{}", dim, chunkX, chunkZ);
                return false;
            }

            Object apiReturn;
            try {
                apiReturn = mgr.claim(dim, ownerId, MAIN_SUB_CONFIG, chunkX, chunkZ, false);
            } catch (Throwable t) {
                SewvDiag.claim("claim RESULT=EXCEPTION dim={} chunk={},{} owner={} sub={} err={}",
                        dim, chunkX, chunkZ, ownerId, MAIN_SUB_CONFIG, t.toString());
                throw t;
            }

            UUID afterOwner = null;
            Integer afterSub = null;
            var afterClaim = mgr.get(dim, sample);
            if (afterClaim != null) {
                afterOwner = afterClaim.getPlayerId();
                afterSub = afterClaim.getSubConfigIndex();
            }
            boolean readbackOk = ownerId.equals(afterOwner);
            SewvDiag.claim(
                    "claim RESULT apiReturn={} afterOwner={} afterSubConfigIndex={} readbackMatchesOwner={} dim={} chunk={},{}",
                    apiReturn, afterOwner, afterSub, readbackOk, dim, chunkX, chunkZ);
            return true;
        }

        static void unclaim(ServerLevel level, int chunkX, int chunkZ) {
            ResourceLocation dim = level.dimension().location();
            var mgr = xaero.pac.common.server.api.OpenPACServerAPI.get(level.getServer())
                    .getServerClaimsManager();
            BlockPos sample = new BlockPos(chunkX << 4, 64, chunkZ << 4);
            UUID beforeOwner = null;
            var beforeClaim = mgr.get(dim, sample);
            if (beforeClaim != null) beforeOwner = beforeClaim.getPlayerId();

            SewvDiag.claim("unclaim CALL dim={} chunk={},{} beforeOwner={}", dim, chunkX, chunkZ, beforeOwner);
            try {
                mgr.unclaim(dim, chunkX, chunkZ);
            } catch (Throwable t) {
                SewvDiag.claim("unclaim RESULT=EXCEPTION dim={} chunk={},{} err={}", dim, chunkX, chunkZ, t.toString());
                throw t;
            }
            var afterClaim = mgr.get(dim, sample);
            UUID afterOwner = afterClaim != null ? afterClaim.getPlayerId() : null;
            SewvDiag.claim("unclaim RESULT afterOwner={} (null=wilderness) dim={} chunk={},{}",
                    afterOwner, dim, chunkX, chunkZ);
        }

        @Nullable
        private static String displayName(
                xaero.pac.common.server.api.OpenPACServerAPI api,
                xaero.pac.common.server.parties.party.api.IServerPartyAPI party) {
            if (party.getOwner() == null) return null;
            String custom = api.getPlayerConfigManager()
                    .getLoadedConfig(party.getOwner().getUUID())
                    .getEffective(xaero.pac.common.server.player.config.api.v2.PlayerConfigOptions.PARTY_NAME);
            if (custom != null && !custom.isEmpty()) return custom;
            String fallback = party.getDefaultName();
            return (fallback != null && !fallback.isEmpty()) ? fallback : null;
        }
    }
}
