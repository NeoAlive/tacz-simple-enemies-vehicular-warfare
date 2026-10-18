package com.neoalive.tacz_sewv.invasion;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.scores.PlayerTeam;

import com.neoalive.tacz_sewv.block.CapturePointBlockEntity;
import com.neoalive.tacz_sewv.block.TeamBaseBlockEntity;
import com.neoalive.tacz_sewv.debug.SewvDiag;
import com.neoalive.tacz_sewv.invasion.force.BaseForce;
import com.neoalive.tacz_sewv.invasion.force.ForceKind;
import com.neoalive.tacz_sewv.invasion.force.InfantrySpec;
import com.neoalive.tacz_sewv.util.VehicleClassify;

/**
 * Pre-start gates for {@code /sewv invasion start}. Failures block spawn; warnings still start.
 */
public final class InvasionValidate {

    private InvasionValidate() {}

    public record Report(List<String> errors, List<String> warnings,
                         List<TeamBaseBlockEntity> bases,
                         List<CapturePointBlockEntity> points) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    public static Report validate(ServerLevel level) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        InvasionLayout layout = InvasionLayout.get(level);

        // Force-load every registered node so validation sees unloaded map pieces.
        for (long packed : layout.teamBasePositions()) {
            InvasionTickets.ensureLoaded(level, BlockPos.of(packed));
        }
        for (long packed : layout.capturePointPositions()) {
            InvasionTickets.ensureLoaded(level, BlockPos.of(packed));
        }

        List<TeamBaseBlockEntity> bases = new ArrayList<>();
        for (long packed : layout.teamBasePositions()) {
            BlockPos pos = BlockPos.of(packed);
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof TeamBaseBlockEntity base) {
                bases.add(base);
            } else {
                errors.add("missing_team_base:" + pos.toShortString());
                SewvDiag.invasion("validate missing team_base at {}", pos);
            }
        }
        // Also pick up any loaded bases not yet in the registry (legacy worlds).
        for (TeamBaseBlockEntity base : InvasionSpawn.findTeamBases(level)) {
            if (!bases.contains(base)) {
                bases.add(base);
                layout.noteTeamBase(base.getBlockPos());
            }
        }

        List<CapturePointBlockEntity> points = new ArrayList<>();
        for (long packed : layout.capturePointPositions()) {
            BlockPos pos = BlockPos.of(packed);
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof CapturePointBlockEntity point) {
                points.add(point);
            } else {
                errors.add("missing_capture_point:" + pos.toShortString());
                SewvDiag.invasion("validate missing capture_point at {}", pos);
            }
        }
        for (CapturableBlockEntity zone : InvasionSpawn.findLoadedCapturables(level)) {
            if (zone instanceof CapturePointBlockEntity point && !points.contains(point)) {
                points.add(point);
                layout.noteCapturePoint(point.getBlockPos());
            }
        }

        if (bases.size() != 2) {
            errors.add("need_exactly_two_bases");
        }
        if (points.isEmpty()) {
            errors.add("need_points");
        }

        boolean anyPlayerOwned = false;
        Set<String> seenTeams = new HashSet<>();
        for (TeamBaseBlockEntity base : bases) {
            if (base.isPlayerOwned()) anyPlayerOwned = true;
            String team = base.getAssignedTeam();
            if (team == null || team.isEmpty()) {
                errors.add("empty_team:" + base.getBlockPos().toShortString());
                continue;
            }
            if (!seenTeams.add(team)) {
                errors.add("duplicate_team:" + team);
            }
            PlayerTeam scoreboard = level.getScoreboard().getPlayerTeam(team);
            if (scoreboard == null) {
                errors.add("unknown_team:" + team);
            }

            validateForce(level, base, team, errors, warnings);

            if (base.getEnemyTeams().contains(team)) {
                errors.add("self_enemy:" + team);
            }
            if (base.getEnemyTeams().isEmpty()) {
                errors.add("no_enemies:" + team);
            }

            if (base.isPlayerOwned()) {
                List<ServerPlayer> members = membersOnline(level, team);
                if (members.isEmpty()) {
                    warnings.add("no_online:" + team);
                }
            }
        }
        if (!anyPlayerOwned) {
            errors.add("need_player_base");
        }

        // Point IDs are builder-only (Stage G1 vicinity AI) — not validated.

        SewvDiag.invasion("validate bases={} points={} errors={} warnings={}",
                bases.size(), points.size(), errors.size(), warnings.size());
        return new Report(errors, warnings, bases, points);
    }

    private static List<ServerPlayer> membersOnline(ServerLevel level, String teamName) {
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            PlayerTeam team = level.getScoreboard().getPlayersTeam(player.getScoreboardName());
            if (team != null && team.getName().equals(teamName)) out.add(player);
        }
        return out;
    }

    /**
     * Per-kind force checks. A kind that fields AI must have at least one id of its own class
     * (a helicopter count backed only by tanks would spawn tanks that never take off); players need
     * something to deploy as; a pure-AI base must field something. A team that can never capture
     * (no ground, no capturing infantry, no players) is only warned about — a deliberately
     * defensive side is a legitimate scenario.
     */
    private static void validateForce(ServerLevel level, TeamBaseBlockEntity base, String team,
                                      List<String> errors, List<String> warnings) {
        BaseForce force = base.getForce();
        for (ForceKind kind : ForceKind.VEHICLES) {
            List<String> pool = force.pool(kind);
            String detail = team + " / " + kind.key();
            int fitting = 0;
            for (String id : pool) {
                if (VehicleClassify.sbwIdFits(kind.poolCategory(), id)) fitting++;
            }
            if (force.aiCount(kind) > 0) {
                if (pool.isEmpty()) {
                    errors.add("empty_kind_pool:" + detail);
                } else if (fitting == 0) {
                    errors.add("unfit_pool:" + detail);
                }
            }
            if (fitting < pool.size()) {
                warnings.add("misfit_ids:" + detail);
            }
        }

        InfantrySpec inf = force.infantry();
        if (inf.mode == InfantrySpec.Mode.RANDOM && inf.min > inf.max) {
            errors.add("infantry_range:" + team);
        }

        boolean playersHere = base.isPlayerOwned() && !membersOnline(level, team).isEmpty();
        if (base.isPlayerOwned()) {
            if (force.deployOptions().isEmpty()) {
                if (playersHere) errors.add("no_deploy:" + team);
                else warnings.add("no_deploy_offline:" + team);
            }
            for (ForceKind kind : force.playerDeploy()) {
                if (kind.isVehicle() && force.pool(kind).isEmpty()) {
                    warnings.add("deploy_empty_pool:" + team + " / " + kind.key());
                }
            }
        } else if (!force.fieldsAnyAi()) {
            errors.add("ai_force_empty:" + team);
        }

        boolean canCapture = playersHere
                || force.aiCount(ForceKind.GROUND) > 0
                || (inf.minimumCount() > 0 && inf.canCapture);
        if (!canCapture) {
            warnings.add("cannot_capture:" + team);
        }
    }
}
