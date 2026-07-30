package com.neoalive.tacz_sewv.invasion;

import com.neoalive.tacz_sewv.block.TeamBaseBlockEntity;
import com.neoalive.tacz_sewv.debug.SewvDiag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-dimension invasion match state.
 * Stage E: spawn/teardown + respawn pin. Stage G adds full start validation.
 */
public final class InvasionSession {

    private static final Map<ResourceKey<Level>, InvasionSession> BY_DIM = new ConcurrentHashMap<>();
    private static final int RESPAWN_REASSERT_INTERVAL = 100; // 5s

    private boolean active;
    private int nextRespawnAssert = Integer.MIN_VALUE;

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

    /**
     * Activates capture ticking, spawns from placed team_bases, pins player respawns.
     *
     * @return spawn summary for command feedback
     */
    public static InvasionSpawn.Result activate(ServerLevel level) {
        InvasionSession session = of(level);
        session.active = true;
        session.nextRespawnAssert = Integer.MIN_VALUE;
        SewvDiag.invasion("session ACTIVE dim={}", level.dimension().location());
        return InvasionSpawn.spawnAll(level);
    }

    public static void deactivate(ServerLevel level) {
        InvasionSession session = of(level);
        if (!session.active) return;
        session.active = false;
        InvasionSpawn.teardown(level);
        SewvDiag.invasion("session INACTIVE dim={}", level.dimension().location());
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

    /**
     * Enemy capture of a player-owned base ends the match immediately (locked win rule).
     * AI bases are map control only.
     */
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
}
