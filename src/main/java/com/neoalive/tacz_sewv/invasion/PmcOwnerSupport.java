package com.neoalive.tacz_sewv.invasion;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.scores.PlayerTeam;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.jetbrains.annotations.Nullable;

/**
 * Resolves team-base PMC Owner settings to spawn UUIDs and live order-auth membership.
 */
public final class PmcOwnerSupport {

    private PmcOwnerSupport() {}

    /**
     * Online players currently on {@code teamName}. Empty if the team is missing or nobody is on.
     */
    public static List<ServerPlayer> teamMembers(ServerLevel level, String teamName) {
        List<ServerPlayer> out = new ArrayList<>();
        if (teamName == null || teamName.isEmpty()) return out;
        PlayerTeam team = level.getScoreboard().getPlayerTeam(teamName);
        if (team == null) return out;
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (team.getPlayers().contains(player.getScoreboardName())) {
                out.add(player);
            }
        }
        return out;
    }

    /** UUID to pass {@code setOwner} at spawn, or null when none can be resolved. */
    @Nullable
    public static UUID resolveSpawnOwnerUuid(ServerLevel level, PmcOwnerKind kind, String value) {
        if (kind == null || kind == PmcOwnerKind.NONE || value == null || value.isEmpty()) {
            return null;
        }
        if (kind == PmcOwnerKind.PLAYER) {
            try {
                return UUID.fromString(value);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        List<ServerPlayer> members = teamMembers(level, value);
        return members.isEmpty() ? null : members.get(0).getUUID();
    }

    /** Stamp team-owner NBT on a spawned PMC when the base owner is a scoreboard team. */
    public static void applyOwnerTeamTag(Entity entity, PmcOwnerKind kind, String value) {
        if (kind != PmcOwnerKind.TEAM || value == null || value.isEmpty()) return;
        entity.getPersistentData().putString(InvasionTags.PMC_OWNER_TEAM, value);
    }

    /**
     * True if {@code player} may command {@code pmc}: SEM UUID owner, or scoreboard teammate of
     * a team-owned invasion PMC.
     */
    public static boolean isOwner(ServerPlayer player, PmcUnitEntity pmc) {
        if (pmc.isOwnedBy(player)) return true;
        String ownerTeam = ownerTeamOf(pmc);
        if (ownerTeam == null) return false;
        PlayerTeam team = player.server.getScoreboard().getPlayersTeam(player.getScoreboardName());
        return team != null && ownerTeam.equals(team.getName());
    }

    /** {@link InvasionTags#PMC_OWNER_TEAM} when set, else null. */
    @Nullable
    public static String ownerTeamOf(Entity entity) {
        if (entity == null) return null;
        String team = entity.getPersistentData().getString(InvasionTags.PMC_OWNER_TEAM);
        return team == null || team.isEmpty() ? null : team;
    }

    /** PMC the player (or their scoreboard team) can issue orders to. */
    public static boolean isPlayerCommandable(AbstractUnit unit) {
        if (!(unit instanceof PmcUnitEntity pmc)) return false;
        if (pmc.getOwnerUUID() != null) return true;
        return ownerTeamOf(unit) != null;
    }

    public static boolean playerOnTeam(ServerPlayer player, String teamName) {
        if (teamName == null || teamName.isEmpty()) return false;
        PlayerTeam team = player.server.getScoreboard().getPlayersTeam(player.getScoreboardName());
        return team != null && teamName.equals(team.getName());
    }
}
