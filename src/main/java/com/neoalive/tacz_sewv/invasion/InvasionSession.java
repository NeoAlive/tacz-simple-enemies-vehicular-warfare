package com.neoalive.tacz_sewv.invasion;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

import com.neoalive.tacz_sewv.block.TeamBaseBlockEntity;
import com.neoalive.tacz_sewv.debug.SewvDiag;

/**
 * Per-dimension invasion match state: validate → ticket → (optional delay at world spawn) →
 * teleport → spawn → maintain → teardown.
 * Mid-match deaths reuse the same spawn-delay: players wait at world spawn; AI fleets just wait.
 */
public final class InvasionSession {

    private static final Map<ResourceKey<Level>, InvasionSession> BY_DIM = new ConcurrentHashMap<>();
    private static final int RESPAWN_REASSERT_INTERVAL = 100; // 5s

    private boolean active;
    /** False until vehicles/crews have been fielded (after any spawn delay). */
    private boolean fielded;
    /** Absolute game time when delayed spawn fires; {@link Long#MIN_VALUE} when not waiting. */
    private long spawnAtGameTime = Long.MIN_VALUE;
    private int nextRespawnAssert = Integer.MIN_VALUE;
    /** Player UUID → game time when they may leave world spawn and receive a tank. */
    private final Map<UUID, Long> playerFieldAt = new ConcurrentHashMap<>();
    /** Team-base packed pos → game time when AI top-up may spawn replacements. */
    private final Map<Long, Long> aiTopUpAt = new ConcurrentHashMap<>();
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

    /** True once AI/player hulls have been spawned (false during the pre-spawn delay). */
    public static boolean hasFielded(ServerLevel level) {
        InvasionSession session = BY_DIM.get(level.dimension());
        return session != null && session.active && session.fielded;
    }

    /** True while a player is serving a mid-match (or start) spawn-delay at world spawn. */
    public static boolean isPlayerWaitingField(ServerLevel level, UUID playerId) {
        InvasionSession session = BY_DIM.get(level.dimension());
        return session != null && session.playerFieldAt.containsKey(playerId);
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
        record Ok(InvasionSpawn.Result spawn, List<String> warnings, int spawnDelaySeconds)
                implements StartResult {}
        record Fail(List<String> errors) implements StartResult {}
    }

    /**
     * Full start: validate → ticket → optional world-spawn wait → teleport teams → spawn.
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

        int delaySec = maxSpawnDelay(report.bases());

        InvasionTickets.ticketAll(level);

        session.active = true;
        session.fielded = false;
        session.nextRespawnAssert = Integer.MIN_VALUE;
        session.playerFieldAt.clear();
        session.aiTopUpAt.clear();
        session.hudLayout = layout;
        InvasionLayout.get(level).setSessionActive(true);

        InvasionHudTracker.push(level);

        if (delaySec <= 0) {
            session.spawnAtGameTime = Long.MIN_VALUE;
            teleportPlayersToBases(level, report.bases());
            InvasionSpawn.Result spawn = InvasionSpawn.spawnAll(level);
            session.fielded = true;
            SewvDiag.invasion("session ACTIVE dim={} spawn={} hudSlots={}",
                    level.dimension().location(), spawn, layout.slots().size());
            return new StartResult.Ok(spawn, List.copyOf(report.warnings()), 0);
        }

        session.spawnAtGameTime = level.getGameTime() + delaySec * 20L;
        teleportPlayersToWorldSpawn(level, report.bases());
        for (ServerPlayer player : level.players()) {
            if (!playerOnAssignedTeam(level, player, report.bases())) continue;
            session.playerFieldAt.put(player.getUUID(), session.spawnAtGameTime);
            player.displayClientMessage(Component.translatable(
                    "message.tacz_sewv.invasion.spawn_delay", delaySec), false);
        }
        SewvDiag.invasion("session ACTIVE dim={} spawnDelay={}s (waiting at world spawn)",
                level.dimension().location(), delaySec);
        return new StartResult.Ok(new InvasionSpawn.Result(report.bases().size(), 0, 0, 0),
                List.copyOf(report.warnings()), delaySec);
    }

    private static int maxSpawnDelay(List<TeamBaseBlockEntity> bases) {
        int max = 0;
        for (TeamBaseBlockEntity base : bases) {
            max = Math.max(max, base.getSpawnDelaySeconds());
        }
        return max;
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
        forceEnd(level);
    }

    /**
     * Always tear down invasion spawns/tickets/HUD and clear the persisted session flag —
     * even when memory already thinks the match is idle (orphaned SPAWN NBT after a crash).
     */
    public static void forceEnd(ServerLevel level) {
        InvasionSession session = of(level);
        session.active = false;
        session.fielded = false;
        session.spawnAtGameTime = Long.MIN_VALUE;
        session.playerFieldAt.clear();
        session.aiTopUpAt.clear();
        session.hudLayout = null;
        InvasionHudTracker.clear(level);
        InvasionSpawn.teardown(level);
        InvasionTickets.releaseAll(level);
        InvasionLayout.get(level).setSessionActive(false);
        SewvDiag.invasion("session FORCE_END dim={}", level.dimension().location());
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

            if (!session.fielded && session.spawnAtGameTime != Long.MIN_VALUE
                    && level.getGameTime() >= session.spawnAtGameTime) {
                finishDelayedSpawn(level, session);
            }

            if (!session.fielded) continue;
            tickPlayerFieldDelays(level, session);
            if (tick < session.nextRespawnAssert) continue;
            session.nextRespawnAssert = tick + RESPAWN_REASSERT_INTERVAL;
            InvasionSpawn.maintain(level);
        }
    }

    /** Schedule a mid-match player fielding after {@code delaySeconds} at world spawn. */
    public static void schedulePlayerField(ServerLevel level, ServerPlayer player, int delaySeconds) {
        InvasionSession session = of(level);
        long at = level.getGameTime() + Math.max(0, delaySeconds) * 20L;
        session.playerFieldAt.put(player.getUUID(), at);
    }

    public static void clearPlayerField(ServerLevel level, UUID playerId) {
        InvasionSession session = BY_DIM.get(level.dimension());
        if (session != null) session.playerFieldAt.remove(playerId);
    }

    /**
     * AI top-up gate: returns true when a shortfall may spawn now. First shortfall starts the
     * base's spawn-delay clock; clears when the fleet is full again.
     */
    public static boolean mayTopUpAi(ServerLevel level, TeamBaseBlockEntity base, boolean shortfall) {
        InvasionSession session = of(level);
        long key = base.getBlockPos().asLong();
        if (!shortfall) {
            session.aiTopUpAt.remove(key);
            return false;
        }
        int delaySec = base.getSpawnDelaySeconds();
        if (delaySec <= 0) return true;
        long now = level.getGameTime();
        Long ready = session.aiTopUpAt.get(key);
        if (ready == null) {
            session.aiTopUpAt.put(key, now + delaySec * 20L);
            SewvDiag.invasion("aiTopUpDelay base={} wait={}s", base.getBlockPos(), delaySec);
            return false;
        }
        if (now < ready) return false;
        return true;
    }

    /** After a delayed AI top-up attempt, restart the clock if still short. */
    public static void noteAiTopUpAttempt(ServerLevel level, TeamBaseBlockEntity base, boolean stillShort) {
        InvasionSession session = of(level);
        long key = base.getBlockPos().asLong();
        if (!stillShort) {
            session.aiTopUpAt.remove(key);
            return;
        }
        int delaySec = base.getSpawnDelaySeconds();
        if (delaySec <= 0) {
            session.aiTopUpAt.remove(key);
            return;
        }
        session.aiTopUpAt.put(key, level.getGameTime() + delaySec * 20L);
    }

    private static void tickPlayerFieldDelays(ServerLevel level, InvasionSession session) {
        if (session.playerFieldAt.isEmpty()) return;
        long now = level.getGameTime();
        for (Map.Entry<UUID, Long> entry : List.copyOf(session.playerFieldAt.entrySet())) {
            if (now < entry.getValue()) continue;
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null || player.level() != level) continue;
            if (!player.isAlive() || player.hasDisconnected()) continue;
            session.playerFieldAt.remove(entry.getKey());
            InvasionSpawn.fieldPlayerAfterDelay(level, player);
        }
    }

    private static void finishDelayedSpawn(ServerLevel level, InvasionSession session) {
        session.spawnAtGameTime = Long.MIN_VALUE;
        session.playerFieldAt.clear();
        List<TeamBaseBlockEntity> bases = InvasionSpawn.findTeamBases(level);
        teleportPlayersToBases(level, bases);
        InvasionSpawn.Result spawn = InvasionSpawn.spawnAll(level);
        session.fielded = true;
        InvasionHudTracker.push(level);
        for (ServerPlayer player : level.players()) {
            player.displayClientMessage(Component.translatable(
                    "message.tacz_sewv.invasion.spawn_go"), false);
        }
        SewvDiag.invasion("session FIELDING dim={} spawn={}", level.dimension().location(), spawn);
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        InvasionSpawn.onPlayerRespawn(event);
    }

    public void onPointCaptured(ServerLevel level, CapturableBlockEntity point, String capturingTeam) {
        SewvDiag.invasion("pointCaptured pos={} team={}", point.getBlockPos(), capturingTeam);
    }

    public void onBaseCaptured(ServerLevel level, TeamBaseBlockEntity base, String capturingTeam) {
        SewvDiag.invasion("baseCaptured pos={} assigned={} endOnCapture={} capturer={}",
                base.getBlockPos(), base.getAssignedTeam(), base.isEndInvasionOnCapture(), capturingTeam);
        if (!base.isEndInvasionOnCapture()) return;

        for (ServerPlayer player : level.players()) {
            player.displayClientMessage(Component.translatable(
                    "message.tacz_sewv.invasion.base_fallen",
                    base.getAssignedTeam(), capturingTeam), false);
        }
        deactivate(level);
    }

    private static void teleportPlayersToWorldSpawn(ServerLevel level, List<TeamBaseBlockEntity> bases) {
        BlockPos at = InvasionSpawn.worldSpawnPos(level);
        for (ServerPlayer player : level.players()) {
            if (!playerOnAssignedTeam(level, player, bases)) continue;
            InvasionSpawn.teleportToWorldSpawn(player, level, at);
        }
    }

    private static boolean playerOnAssignedTeam(ServerLevel level, ServerPlayer player,
                                                List<TeamBaseBlockEntity> bases) {
        PlayerTeam team = level.getScoreboard().getPlayersTeam(player.getScoreboardName());
        if (team == null) return false;
        String name = team.getName();
        for (TeamBaseBlockEntity base : bases) {
            if (name.equals(base.getAssignedTeam())) return true;
        }
        return false;
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
