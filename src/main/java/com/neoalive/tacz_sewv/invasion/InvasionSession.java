package com.neoalive.tacz_sewv.invasion;

import com.neoalive.tacz_sewv.block.TeamBaseBlockEntity;
import com.neoalive.tacz_sewv.debug.SewvDiag;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-dimension invasion match state: validate → ticket → teleport → spawn → maintain → teardown.
 */
public final class InvasionSession {

    private static final Map<ResourceKey<Level>, InvasionSession> BY_DIM = new ConcurrentHashMap<>();
    private static final int RESPAWN_REASSERT_INTERVAL = 100; // 5s

    private boolean active;
    private int nextRespawnAssert = Integer.MIN_VALUE;
    @javax.annotation.Nullable
    private InvasionHud.Layout hudLayout;

    private InvasionSession() {}

    public static InvasionSession of(ServerLevel level) {
        return BY_DIM.computeIfAbsent(level.dimension(), k -> new InvasionSession());
    }

    public static boolean isActive(ServerLevel level) {
        InvasionSession session = BY_DIM.get(level.dimension());
        return session != null && session.active;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    @javax.annotation.Nullable
    public InvasionHud.Layout hudLayout() {
        return hudLayout;
    }

    public sealed interface StartResult {
        record Ok(InvasionSpawn.Result spawn, List<String> warnings) implements StartResult {}
        record Fail(List<String> errors) implements StartResult {}
    }

    /**
     * Full start: validate (no partial spawn on fail) → ticket nodes → teleport teams → spawn.
     */
    public static StartResult start(ServerLevel level) {
        InvasionSession session = of(level);
        if (session.active) {
            return new StartResult.Fail(List.of("already_active"));
        }

        InvasionValidate.Report report = InvasionValidate.validate(level);
        if (!report.ok()) {
            SewvDiag.invasion("start FAIL errors={}", report.errors());
            return new StartResult.Fail(List.copyOf(report.errors()));
        }
        for (String warn : report.warnings()) {
            SewvDiag.invasion("start WARN {}", warn);
        }

        InvasionHud.Layout layout = InvasionHud.buildLayout(report.bases(), report.points());
        if (layout == null) {
            return new StartResult.Fail(List.of("need_exactly_two_bases"));
        }

        InvasionTickets.ticketAll(level);
        teleportPlayersToBases(level, report.bases());

        session.active = true;
        session.nextRespawnAssert = Integer.MIN_VALUE;
        session.hudLayout = layout;
        InvasionLayout.get(level).setSessionActive(true);

        InvasionSpawn.Result spawn = InvasionSpawn.spawnAll(level);
        InvasionHudTracker.push(level);
        SewvDiag.invasion("session ACTIVE dim={} spawn={} hudSlots={}",
                level.dimension().location(), spawn, layout.slots().size());
        return new StartResult.Ok(spawn, List.copyOf(report.warnings()));
    }

    /** @deprecated use {@link #start(ServerLevel)} */
    @Deprecated
    public static InvasionSpawn.Result activate(ServerLevel level) {
        StartResult result = start(level);
        if (result instanceof StartResult.Ok ok) return ok.spawn();
        return new InvasionSpawn.Result(0, 0, 0, 0);
    }

    public static void deactivate(ServerLevel level) {
        InvasionSession session = of(level);
        boolean wasActive = session.active || InvasionLayout.get(level).isSessionActive();
        if (!wasActive) return;
        session.active = false;
        session.hudLayout = null;
        InvasionHudTracker.clear(level);
        InvasionSpawn.teardown(level);
        InvasionTickets.releaseAll(level);
        InvasionLayout.get(level).setSessionActive(false);
        SewvDiag.invasion("session INACTIVE dim={}", level.dimension().location());
    }

    /**
     * After a reload the in-memory session is gone but SavedData may still say active —
     * force-stop cleanup so orphaned spawns/tickets do not linger.
     */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        for (ServerLevel level : event.getServer().getAllLevels()) {
            InvasionLayout layout = InvasionLayout.get(level);
            if (!layout.isSessionActive()) continue;
            SewvDiag.invasion("orphan session on load — force stop dim={}", level.dimension().location());
            InvasionSession session = of(level);
            session.active = true; // so deactivate runs teardown
            deactivate(level);
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        int tick = event.getServer().getTickCount();
        for (ServerLevel level : event.getServer().getAllLevels()) {
            InvasionSession session = BY_DIM.get(level.dimension());
            if (session == null || !session.active) continue;
            if (tick < session.nextRespawnAssert) continue;
            session.nextRespawnAssert = tick + RESPAWN_REASSERT_INTERVAL;
            InvasionSpawn.maintain(level);
        }
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        InvasionSpawn.onPlayerRespawn(event);
    }

    public void onPointCaptured(ServerLevel level, CapturableBlockEntity point, String capturingTeam) {
        SewvDiag.invasion("pointCaptured pos={} team={}", point.getBlockPos(), capturingTeam);
    }

    public void onBaseCaptured(ServerLevel level, TeamBaseBlockEntity base, String capturingTeam) {
        SewvDiag.invasion("baseCaptured pos={} assigned={} playerOwned={} capturer={}",
                base.getBlockPos(), base.getAssignedTeam(), base.isPlayerOwned(), capturingTeam);
        if (!base.isPlayerOwned()) return;

        for (ServerPlayer player : level.players()) {
            player.displayClientMessage(Component.translatable(
                    "message.tacz_sewv.invasion.base_fallen",
                    base.getAssignedTeam(), capturingTeam), false);
        }
        deactivate(level);
    }

    private static void teleportPlayersToBases(ServerLevel level, List<TeamBaseBlockEntity> bases) {
        for (TeamBaseBlockEntity base : bases) {
            String teamName = base.getAssignedTeam();
            if (teamName.isEmpty()) continue;
            BlockPos origin = base.getBlockPos();
            int radius = Math.max(2, base.getRadiusInBlocks());
            int i = 0;
            for (ServerPlayer player : level.players()) {
                PlayerTeam team = level.getScoreboard().getPlayersTeam(player.getScoreboardName());
                if (team == null || !team.getName().equals(teamName)) continue;
                BlockPos at = scatter(level, origin, radius, i++);
                player.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
                SewvDiag.invasion("teleport player={} → {}", player.getGameProfile().getName(), at);
            }
        }
    }

    private static BlockPos scatter(ServerLevel level, BlockPos origin, int radius, int salt) {
        int span = radius * 2 + 1;
        int dx = Math.floorMod(salt * 7 + 3, span) - radius;
        int dz = Math.floorMod(salt * 13 + 5, span) - radius;
        int x = origin.getX() + dx;
        int z = origin.getZ() + dz;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return new BlockPos(x, y, z);
    }
}
