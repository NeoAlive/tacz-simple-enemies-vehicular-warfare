package com.neoalive.tacz_sewv.client.invasion;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.PlayerTeam;

import com.neoalive.tacz_sewv.invasion.InvasionHud;
import com.neoalive.tacz_sewv.invasion.InvasionTags;

/**
 * Client store for the invasion match HUD. Entrance animation state lives here;
 * drawing is {@link InvasionHudOverlay}.
 */
public final class InvasionHudClient {

    private static final float SETTLE_EPS = 0.5f;

    @Nullable
    private static InvasionHud.Snapshot snapshot;
    @Nullable
    private static float[] currentX;
    @Nullable
    private static float[] targetX;
    private static boolean entranceDone;
    private static long lastFrameNanos;
    private static final Map<Integer, Byte> vehicleSides = new HashMap<>();

    private InvasionHudClient() {}

    public static boolean isActive() {
        return snapshot != null;
    }

    public static void accept(InvasionHud.Snapshot incoming, List<int[]> sides) {
        boolean first = snapshot == null;
        snapshot = incoming;
        vehicleSides.clear();
        for (int[] pair : sides) {
            vehicleSides.put(pair[0], (byte) pair[1]);
        }
        int n = incoming.slots().size();
        if (first || currentX == null || currentX.length != n) {
            currentX = new float[n];
            targetX = new float[n];
            entranceDone = false;
            lastFrameNanos = 0L;
            Arrays.fill(currentX, Float.NaN);
        }
    }

    public static void clear() {
        snapshot = null;
        currentX = null;
        targetX = null;
        entranceDone = false;
        lastFrameNanos = 0L;
        vehicleSides.clear();
    }

    /**
     * HUD team colour for a looked-at hull during an active invasion, or null to keep the
     * normal faction overlay colour.
     */
    @Nullable
    public static Integer overlayColor(VehicleEntity vehicle) {
        InvasionHud.Snapshot snap = snapshot;
        if (snap == null) return null;

        Byte side = vehicleSides.get(vehicle.getId());
        if (side != null) {
            Integer c = snap.colorForSide(side);
            if (c != null) return c;
        }

        // Player seat-0: scoreboard team → A/B (SPAWN tag is never on the player).
        Entity driver = vehicle.getFirstPassenger();
        if (driver instanceof Player player) {
            PlayerTeam team = player.level().getScoreboard().getPlayersTeam(player.getScoreboardName());
            if (team != null) {
                Integer c = snap.colorForTeam(team.getName());
                if (c != null) return c;
            }
        }

        // Fallback: passenger NBT if the client ever sees it (integrated / rare sync paths).
        for (Entity passenger : vehicle.getPassengers()) {
            String team = passenger.getPersistentData().getString(InvasionTags.TEAM);
            Integer c = snap.colorForTeam(team);
            if (c != null) return c;
        }
        return null;
    }

    @Nullable
    public static InvasionHud.Snapshot snapshot() {
        return snapshot;
    }

    @Nullable
    public static float[] currentX() {
        return currentX;
    }

    @Nullable
    public static float[] targetX() {
        return targetX;
    }

    public static boolean entranceDone() {
        return entranceDone;
    }

    public static void markEntranceDone() {
        entranceDone = true;
    }

    public static float deltaSeconds() {
        long now = System.nanoTime();
        if (lastFrameNanos == 0L) {
            lastFrameNanos = now;
            return 1f / 60f;
        }
        float dt = (now - lastFrameNanos) / 1_000_000_000f;
        lastFrameNanos = now;
        return Math.min(0.1f, Math.max(0.001f, dt));
    }

    public static boolean settled(float[] cur, float[] tgt) {
        for (int i = 0; i < cur.length; i++) {
            if (Math.abs(cur[i] - tgt[i]) > SETTLE_EPS) return false;
        }
        return true;
    }
}
