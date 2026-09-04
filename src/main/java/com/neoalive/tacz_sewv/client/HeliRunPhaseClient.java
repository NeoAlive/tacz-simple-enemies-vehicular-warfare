package com.neoalive.tacz_sewv.client;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.config.ClientConfig;
import com.neoalive.tacz_sewv.entity.ai.goal.DriveHelicopterGoal;

/**
 * Client cache of AI heli run phases (filled by {@link com.neoalive.tacz_sewv.network.PacketHeliRunPhase}).
 * RAPPEL rides the same channel as the firing-run phases — Stage 3 wire render reads
 * {@link #isRappelling(int)}.
 */
public final class HeliRunPhaseClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Int2ObjectOpenHashMap<DriveHelicopterGoal.RunPhase> PHASES =
            new Int2ObjectOpenHashMap<>();

    private HeliRunPhaseClient() {}

    public static void put(int entityId, int ordinal) {
        DriveHelicopterGoal.RunPhase[] values = DriveHelicopterGoal.RunPhase.values();
        DriveHelicopterGoal.RunPhase prev = PHASES.get(entityId);
        boolean wasRappel = prev == DriveHelicopterGoal.RunPhase.RAPPEL;

        DriveHelicopterGoal.RunPhase next;
        if (ordinal < 0 || ordinal >= values.length) {
            PHASES.remove(entityId);
            next = null;
        } else {
            next = values[ordinal];
            if (next == DriveHelicopterGoal.RunPhase.IDLE) {
                PHASES.remove(entityId);
                next = null;
            } else {
                PHASES.put(entityId, next);
            }
        }

        boolean nowRappel = next == DriveHelicopterGoal.RunPhase.RAPPEL;
        if (wasRappel != nowRappel && ClientConfig.flag(ClientConfig.HELI_COMBAT_DEBUG)) {
            LOGGER.info("[sewv heli] client #{} rappel={}", entityId, nowRappel);
        }
    }

    @Nullable
    public static DriveHelicopterGoal.RunPhase get(int entityId) {
        return PHASES.get(entityId);
    }

    /** True while the server last reported {@link DriveHelicopterGoal.RunPhase#RAPPEL} for this hull. */
    public static boolean isRappelling(int entityId) {
        return PHASES.get(entityId) == DriveHelicopterGoal.RunPhase.RAPPEL;
    }

    public static void clear(int entityId) {
        DriveHelicopterGoal.RunPhase prev = PHASES.remove(entityId);
        if (prev == DriveHelicopterGoal.RunPhase.RAPPEL && ClientConfig.flag(ClientConfig.HELI_COMBAT_DEBUG)) {
            LOGGER.info("[sewv heli] client #{} rappel={}", entityId, false);
        }
    }

    /** Logout / dimension wipe. */
    public static void clearAll() {
        PHASES.clear();
    }
}
