package com.neoalive.tacz_sewv.invasion;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.block.TeamBaseBlockEntity;
import com.neoalive.tacz_sewv.debug.SewvDiag;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.scores.PlayerTeam;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Sole-team radius/time capture shared by capture_point and team_base.
 * Empty resets progress; contested freezes; sole non-owner advances until capture.
 * A team_base's {@code assignedTeam} is the implicit owner when {@code ownedTeam} is empty —
 * that team cannot capture its own base.
 */
public final class CaptureSupport {

    /** Presence scan cadence (game ticks). Progress uses the real delta between scans. */
    private static final int SCAN_INTERVAL_TICKS = 20;

    private CaptureSupport() {}

    public static void tick(CapturableBlockEntity be) {
        if (!(be.getLevel() instanceof ServerLevel level)) return;
        if (!InvasionSession.isActive(level)) return;

        long now = level.getGameTime();
        long last = be.getLastScanGameTime();
        if (last != Long.MIN_VALUE && now - last < SCAN_INTERVAL_TICKS) return;

        long delta = last == Long.MIN_VALUE ? SCAN_INTERVAL_TICKS : now - last;
        be.setLastScanGameTime(now);

        AABB box = captureBox(be.getBlockPos(), be.getRadiusInBlocks());
        List<ServerPlayer> playersHere = level.getEntitiesOfClass(ServerPlayer.class, box);
        Set<String> present = teamsPresent(level, box, playersHere);

        // Silent empty scans were the Stage C failure mode — log when someone is in the
        // radius but scored as no team (wrong gamemode / not on a /team / etc.).
        if (present.isEmpty() && !playersHere.isEmpty() && now % 100 < SCAN_INTERVAL_TICKS) {
            for (ServerPlayer p : playersHere) {
                PlayerTeam team = level.getScoreboard().getPlayersTeam(p.getScoreboardName());
                SewvDiag.invasion(
                        "presenceMiss pos={} player={} spectator={} gameMode={} scoreboardTeam={}",
                        be.getBlockPos(), p.getGameProfile().getName(), p.isSpectator(),
                        p.gameMode.getGameModeForPlayer(),
                        team == null ? "none" : team.getName());
            }
        }

        boolean dirty = false;

        if (present.isEmpty()) {
            // No one conquering or holding — drop partial progress.
            dirty |= be.setContestedIfChanged(false);
            dirty |= be.setProgressIfChanged(0f);
            be.setAdvancingTeam("");
            if (dirty) sync(level, be);
            return;
        }

        if (present.size() >= 2) {
            if (be.setContestedIfChanged(true)) {
                SewvDiag.invasion("contested pos={} teams={}", be.getBlockPos(), present);
                sync(level, be);
            }
            return;
        }

        dirty |= be.setContestedIfChanged(false);
        String sole = present.iterator().next();
        String holder = holdingTeam(be);

        // Own point / own base (including team_base with empty ownedTeam → assignedTeam).
        if (!holder.isEmpty() && sole.equals(holder)) {
            dirty |= be.setProgressIfChanged(0f);
            be.setAdvancingTeam("");
            if (dirty) sync(level, be);
            return;
        }

        String advancing = be.getAdvancingTeam();
        if (!advancing.isEmpty() && !sole.equals(advancing)) {
            dirty |= be.setProgressIfChanged(0f);
        }
        be.setAdvancingTeam(sole);

        float add = (float) delta / (be.getTimeToCaptureSeconds() * 20.0f);
        float next = Math.min(1f, be.getProgress() + add);
        if (next >= 1f) {
            completeCapture(level, be, sole);
            return;
        }
        if (be.setProgressIfChanged(next)) {
            SewvDiag.invasion("capturing pos={} team={} progress={}",
                    be.getBlockPos(), sole, String.format("%.2f", next));
            sync(level, be);
        } else if (dirty) {
            sync(level, be);
        }
    }

    /**
     * Who currently holds the zone for capture purposes.
     * Capture points: {@code ownedTeam} only.
     * Team bases: {@code ownedTeam}, or {@code assignedTeam} when still uncaptured —
     * so the home team cannot "capture" its own empty base.
     */
    static String holdingTeam(CapturableBlockEntity be) {
        if (!be.getOwnedTeam().isEmpty()) return be.getOwnedTeam();
        if (be instanceof TeamBaseBlockEntity base && !base.getAssignedTeam().isEmpty()) {
            return base.getAssignedTeam();
        }
        return "";
    }

    private static void completeCapture(ServerLevel level, CapturableBlockEntity be, String team) {
        // Belt-and-braces: never award a team_base to its own assigned team while they already hold it.
        if (be instanceof TeamBaseBlockEntity base
                && team.equals(holdingTeam(base))) {
            SewvDiag.invasion("captureRejected selfBase pos={} team={}", be.getBlockPos(), team);
            be.setProgress(0f);
            be.setAdvancingTeam("");
            sync(level, be);
            return;
        }

        be.setOwnedTeam(team);
        be.setProgress(0f);
        be.setContested(false);
        be.setAdvancingTeam("");
        sync(level, be);

        SewvDiag.invasion("captured pos={} team={} kind={}",
                be.getBlockPos(), team,
                be instanceof TeamBaseBlockEntity ? "team_base" : "capture_point");

        InvasionSession session = InvasionSession.of(level);
        if (be instanceof TeamBaseBlockEntity base) {
            session.onBaseCaptured(level, base, team);
        } else {
            session.onPointCaptured(level, be, team);
        }
    }

    /**
     * Distinct invasion teams inside the radius.
     * Players: scoreboard team (creative counted — mapmakers test in creative; spectators ignored).
     * NPCs/vehicles: {@link InvasionTags#TEAM}.
     */
    public static Set<String> teamsPresent(ServerLevel level, BlockPos pos, int radius) {
        AABB box = captureBox(pos, radius);
        return teamsPresent(level, box, level.getEntitiesOfClass(ServerPlayer.class, box));
    }

    private static Set<String> teamsPresent(ServerLevel level, AABB box, List<ServerPlayer> playersHere) {
        Set<String> teams = new HashSet<>();

        for (ServerPlayer player : playersHere) {
            addPlayerTeam(level, teams, player);
        }

        for (LivingEntity living : level.getEntitiesOfClass(LivingEntity.class, box,
                e -> !(e instanceof Player))) {
            addTaggedTeam(teams, living);
        }

        for (VehicleEntity vehicle : level.getEntitiesOfClass(VehicleEntity.class, box)) {
            for (Entity passenger : vehicle.getPassengers()) {
                if (passenger instanceof Player player) {
                    addPlayerTeam(level, teams, player);
                } else {
                    addTaggedTeam(teams, passenger);
                }
            }
            addTaggedTeam(teams, vehicle);
        }

        return teams;
    }

    public static AABB captureBox(BlockPos pos, int radius) {
        double cx = pos.getX() + 0.5;
        double cy = pos.getY() + 0.5;
        double cz = pos.getZ() + 0.5;
        return new AABB(cx - radius, cy - radius, cz - radius,
                cx + radius, cy + radius, cz + radius);
    }

    private static void addPlayerTeam(ServerLevel level, Set<String> teams, Player player) {
        // Spectators are not on the field. Creative IS counted — capture testing and
        // admin presence must work; targeting doctrine (don't shoot creatives) is separate.
        if (player.isSpectator()) return;
        PlayerTeam team = level.getScoreboard().getPlayersTeam(player.getScoreboardName());
        if (team != null) {
            teams.add(team.getName());
        }
    }

    private static void addTaggedTeam(Set<String> teams, Entity entity) {
        String team = entity.getPersistentData().getString(InvasionTags.TEAM);
        if (!team.isEmpty()) {
            teams.add(team);
        }
    }

    private static void sync(ServerLevel level, CapturableBlockEntity be) {
        be.setChanged();
        BlockPos pos = be.getBlockPos();
        BlockState state = level.getBlockState(pos);
        level.sendBlockUpdated(pos, state, state, 3);
    }

    /** One-line snapshot for {@code /sewv invasion status}. */
    public static String describe(CapturableBlockEntity be) {
        if (!(be.getLevel() instanceof ServerLevel level)) {
            return be.getBlockPos() + " (no level)";
        }
        Set<String> present = teamsPresent(level, be.getBlockPos(), be.getRadiusInBlocks());
        return String.format("%s owned=%s progress=%.2f contested=%s present=%s advancing=%s r=%d t=%ds",
                be.getBlockPos(),
                be.getOwnedTeam().isEmpty() ? "-" : be.getOwnedTeam(),
                be.getProgress(),
                be.isContested(),
                present.isEmpty() ? "[]" : present.toString(),
                be.getAdvancingTeam().isEmpty() ? "-" : be.getAdvancingTeam(),
                be.getRadiusInBlocks(),
                be.getTimeToCaptureSeconds());
    }
}
