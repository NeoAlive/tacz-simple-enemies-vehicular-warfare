package com.neoalive.tacz_sewv.invasion;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import com.neoalive.tacz_sewv.block.TeamBaseBlockEntity;
import com.neoalive.tacz_sewv.debug.SewvDiag;
import com.neoalive.tacz_sewv.diplomacy.DiplomacyData;
import com.neoalive.tacz_sewv.diplomacy.DiplomacyData.Entry;
import com.neoalive.tacz_sewv.diplomacy.DiplomacyData.Relation;
import com.neoalive.tacz_sewv.diplomacy.DiplomacyData.Subject;

/**
 * Turns a match's team bases into player diplomacy: every name on a base's scoreboard team is
 * {@code ALLY} with its teammates and {@code ENEMY} with every name on that base's enemy teams
 * (the base's explicit list — allies are only its own team, as for {@link InvasionHostility}).
 *
 * <p>The only writer of {@link DiplomacyData}'s transient invasion overlay. Written at start and
 * re-written on the session's 5 s maintenance pass, so a player who joins a {@code /team} mid-match
 * is picked up; cleared by {@code InvasionSession.forceEnd}, which every end path runs through.
 * Names come straight from the scoreboard, offline members included — the overlay is keyed by
 * name, which is what {@code /team} holds.
 */
public final class InvasionDiplomacy {

    private InvasionDiplomacy() {}

    public static void sync(ServerLevel level) {
        sync(level, InvasionSpawn.findTeamBases(level));
    }

    public static void sync(ServerLevel level, Collection<TeamBaseBlockEntity> bases) {
        Scoreboard board = level.getScoreboard();
        // team → its enemy teams, merged across bases that share a team.
        Map<String, Set<String>> enemiesOf = new LinkedHashMap<>();
        for (TeamBaseBlockEntity base : bases) {
            String team = base.getAssignedTeam();
            if (team == null || team.isEmpty()) continue;
            enemiesOf.computeIfAbsent(team, t -> new LinkedHashSet<>()).addAll(base.getEnemyTeams());
        }

        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : enemiesOf.entrySet()) {
            List<String> members = members(board, e.getKey());
            for (int i = 0; i < members.size(); i++) {
                for (int j = i + 1; j < members.size(); j++) {
                    entries.add(pair(members.get(i), members.get(j), Relation.ALLY));
                }
            }
            for (String enemyTeam : e.getValue()) {
                if (enemyTeam.equals(e.getKey())) continue;
                for (String enemy : members(board, enemyTeam)) {
                    for (String member : members) {
                        entries.add(pair(member, enemy, Relation.ENEMY));
                    }
                }
            }
        }
        DiplomacyData.get(level).setInvasion(entries);
        SewvDiag.diplomacy("invasion sync teams={} entries={}", enemiesOf.keySet(), entries.size());
    }

    public static void clear(ServerLevel level) {
        DiplomacyData.get(level).clearInvasion();
    }

    private static List<String> members(Scoreboard board, String teamName) {
        PlayerTeam team = board.getPlayerTeam(teamName);
        return team == null ? List.of() : List.copyOf(team.getPlayers());
    }

    private static Entry pair(String a, String b, Relation relation) {
        return new Entry(Subject.player(a), Subject.player(b), relation, true);
    }
}
