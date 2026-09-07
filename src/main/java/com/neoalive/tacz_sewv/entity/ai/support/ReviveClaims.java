package com.neoalive.tacz_sewv.entity.ai.support;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * One reviver per downed patient (player or PMC). Without this every nearby unit's
 * {@code PlayerReviveGoal}/{@code PmcReviveGoal} locks the same bleed-out and they clump.
 * Keys are entity network ids — transient, same rule as escort/board orders.
 */
public final class ReviveClaims {

    private static final Map<Integer, Integer> PATIENT_TO_REVIVER = new HashMap<>();

    private ReviveClaims() {}

    /** True when nobody holds the patient, or {@code reviverId} already does. */
    public static boolean isFreeOrMine(Level level, int patientId, int reviverId) {
        Integer holder = PATIENT_TO_REVIVER.get(patientId);
        if (holder == null || holder == reviverId) return true;
        return !alive(level, holder);
    }

    /** Claim if free or held by a dead/missing unit. False if another live unit already owns it. */
    public static boolean tryClaim(Level level, int patientId, int reviverId) {
        Integer holder = PATIENT_TO_REVIVER.get(patientId);
        if (holder != null && holder != reviverId && alive(level, holder)) return false;
        PATIENT_TO_REVIVER.put(patientId, reviverId);
        return true;
    }

    /** Overwrite any existing claim — used by Revive Call. */
    public static void forceClaim(int patientId, int reviverId) {
        PATIENT_TO_REVIVER.put(patientId, reviverId);
    }

    public static boolean isMine(int patientId, int reviverId) {
        Integer holder = PATIENT_TO_REVIVER.get(patientId);
        return holder != null && holder == reviverId;
    }

    /** Patient id this reviver currently holds, or {@code -1}. */
    public static int patientOf(int reviverId) {
        for (Map.Entry<Integer, Integer> e : PATIENT_TO_REVIVER.entrySet()) {
            if (e.getValue() == reviverId) return e.getKey();
        }
        return -1;
    }

    public static void release(int patientId, int reviverId) {
        Integer holder = PATIENT_TO_REVIVER.get(patientId);
        if (holder != null && holder == reviverId) {
            PATIENT_TO_REVIVER.remove(patientId);
        }
    }

    private static boolean alive(Level level, int entityId) {
        Entity e = level.getEntity(entityId);
        return e != null && e.isAlive();
    }
}
