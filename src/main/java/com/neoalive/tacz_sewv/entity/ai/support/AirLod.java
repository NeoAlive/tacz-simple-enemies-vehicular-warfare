package com.neoalive.tacz_sewv.entity.ai.support;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Distance band for airframe AI: full fidelity near players, cheaper work when ticketed far away.
 *
 * <p>Combat / takeoff / landing ignore FAR — only transit modes ({@code CRUISE}/{@code HOLD}/
 * {@code RTB}/{@code INGRESS}) cheapen terrain and ally scans. Stick inputs still fire every tick;
 * this only skips expensive probes.
 */
public final class AirLod {

    public static final int PLAYER_CACHE_TTL_TICKS = 10;
    public static final int FAR_ALLY_INTERVAL_TICKS = 40;
    public static final double FAR_LOOKAHEAD = 96.0;
    public static final int NEAR_CORRIDOR_TTL = 20;
    public static final int FAR_CORRIDOR_TTL = 40;
    public static final int NEAR_WHISKER_TTL = 8;
    public static final int FAR_WHISKER_TTL = 16;
    public static final int NEAR_GROUND_TTL = 10;
    public static final int FAR_GROUND_TTL = 20;

    private static final int CACHE_MAX = 64;

    private static final ThreadLocal<LinkedHashMap<Integer, PlayerNearSample>> NEAR_CACHE =
            ThreadLocal.withInitial(() -> new LinkedHashMap<>(16, 0.75F, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, PlayerNearSample> eldest) {
                    return size() > CACHE_MAX;
                }
            });

    private static final class PlayerNearSample {
        private long tick = Long.MIN_VALUE;
        private double blocksBucket;
        private boolean near;

        boolean matches(double blocks, long now) {
            return this.blocksBucket == blocks && now - this.tick < PLAYER_CACHE_TTL_TICKS;
        }

        void store(double blocks, long now, boolean value) {
            this.blocksBucket = blocks;
            this.tick = now;
            this.near = value;
        }
    }

    private AirLod() {}

    /** True when any player is within {@code blocks} of {@code entity} (cached ~10 ticks). */
    public static boolean nearPlayers(Entity entity, double blocks) {
        if (!(entity.level() instanceof ServerLevel level)) return true;
        long now = level.getGameTime();
        PlayerNearSample sample = NEAR_CACHE.get()
                .computeIfAbsent(entity.getId(), id -> new PlayerNearSample());
        if (sample.matches(blocks, now)) return sample.near;

        double r2 = blocks * blocks;
        boolean near = false;
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) continue;
            if (player.distanceToSqr(entity) <= r2) {
                near = true;
                break;
            }
        }
        sample.store(blocks, now, near);
        return near;
    }

    /**
     * FAR transit LOD: no player nearby and the mode is allowed to cheapen.
     * Combat / land / takeoff pass {@code transitMode = false}.
     */
    public static boolean farTransit(Entity entity, double lodBlocks, boolean transitMode) {
        return transitMode && !nearPlayers(entity, lodBlocks);
    }

    public static int corridorTtl(boolean far) {
        return far ? FAR_CORRIDOR_TTL : NEAR_CORRIDOR_TTL;
    }

    public static int whiskerTtl(boolean far) {
        return far ? FAR_WHISKER_TTL : NEAR_WHISKER_TTL;
    }

    public static int groundTtl(boolean far) {
        return far ? FAR_GROUND_TTL : NEAR_GROUND_TTL;
    }
}
