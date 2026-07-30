package com.neoalive.tacz_sewv.invasion;

import com.neoalive.tacz_sewv.block.CapturePointBlockEntity;
import com.neoalive.tacz_sewv.block.TeamBaseBlockEntity;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketInvasionBillboards;
import net.minecraft.ChatFormatting;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * Syncs invasion billboards beyond entity render distance. Same cadence habit as
 * {@link com.neoalive.tacz_sewv.util.OwnedVehicleTracker}: server tick counter, ~1s.
 *
 * <p>Setup preview: capture_points with {@code showBillboard}. During an active session,
 * also every team_base (and any capture_point still flagged to show).
 */
public final class InvasionBillboardTracker {

    private static final int INTERVAL_TICKS = 20;
    private static int nextSend = Integer.MIN_VALUE;

    private InvasionBillboardTracker() {}

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        nextSend = Integer.MIN_VALUE;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        int tick = server.getTickCount();
        if (tick < nextSend) return;
        nextSend = tick + INTERVAL_TICKS;

        for (ServerLevel level : server.getAllLevels()) {
            List<InvasionBillboard> snaps = collect(level);
            List<ServerPlayer> players = level.players();
            if (players.isEmpty()) continue;
            PacketInvasionBillboards packet = new PacketInvasionBillboards(snaps);
            for (ServerPlayer player : players) {
                NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
            }
        }
    }

    private static List<InvasionBillboard> collect(ServerLevel level) {
        boolean sessionActive = InvasionSession.isActive(level);
        List<InvasionBillboard> out = new ArrayList<>();

        int view = level.getServer().getPlayerList().getViewDistance() + 8;
        // Loaded chunks only — billboards past loaded chunks still sync once the chunk is held
        // (Stage H tickets). For setup, builders stand near their blocks.
        for (ServerPlayer player : level.players()) {
            int pcx = player.chunkPosition().x;
            int pcz = player.chunkPosition().z;
            for (int cx = pcx - view; cx <= pcx + view; cx++) {
                for (int cz = pcz - view; cz <= pcz + view; cz++) {
                    if (!level.hasChunk(cx, cz)) continue;
                    LevelChunk chunk = level.getChunk(cx, cz);
                    for (var be : chunk.getBlockEntities().values()) {
                        if (be instanceof CapturePointBlockEntity point) {
                            if (!point.isShowBillboard()) continue;
                            out.add(fromCapturePoint(level, point));
                        } else if (be instanceof TeamBaseBlockEntity base && sessionActive) {
                            out.add(fromTeamBase(level, base));
                        }
                    }
                }
            }
        }
        // Dedup by block pos (multiple players overlapping the same chunk scan).
        if (out.size() <= 1) return out;
        List<InvasionBillboard> deduped = new ArrayList<>(out.size());
        for (InvasionBillboard snap : out) {
            boolean seen = false;
            for (InvasionBillboard existing : deduped) {
                if (existing.pos().equals(snap.pos())) {
                    seen = true;
                    break;
                }
            }
            if (!seen) deduped.add(snap);
        }
        return deduped;
    }

    private static InvasionBillboard fromCapturePoint(ServerLevel level, CapturePointBlockEntity point) {
        String owned = point.getOwnedTeam();
        String label = "CP #" + point.getPointId()
                + (owned.isEmpty() ? "" : " · " + owned);
        return new InvasionBillboard(
                level.dimension(),
                point.getBlockPos(),
                point.getBillboardYOffset(),
                label,
                teamColor(level, owned),
                point.getProgress(),
                showProgress(point),
                point.isContested());
    }

    private static InvasionBillboard fromTeamBase(ServerLevel level, TeamBaseBlockEntity base) {
        String assigned = base.getAssignedTeam();
        String owned = base.getOwnedTeam();
        String label = (assigned.isEmpty() ? "Base" : assigned)
                + (base.isPlayerOwned() ? " ★" : "")
                + (owned.isEmpty() || owned.equals(assigned) ? "" : " · held by " + owned);
        return new InvasionBillboard(
                level.dimension(),
                base.getBlockPos(),
                CapturePointBlockEntity.DEFAULT_BILLBOARD_Y_OFFSET,
                label,
                teamColor(level, owned.isEmpty() ? assigned : owned),
                base.getProgress(),
                showProgress(base),
                base.isContested());
    }

    private static boolean showProgress(CapturableBlockEntity be) {
        return be.isContested() || be.getProgress() > 0.001f;
    }

    private static int teamColor(ServerLevel level, String teamName) {
        if (teamName == null || teamName.isEmpty()) return 0xFFFFFF;
        PlayerTeam team = level.getScoreboard().getPlayerTeam(teamName);
        if (team == null) return 0xFFFFFF;
        ChatFormatting formatting = team.getColor();
        Integer rgb = formatting.getColor();
        return rgb == null ? 0xFFFFFF : rgb;
    }
}
