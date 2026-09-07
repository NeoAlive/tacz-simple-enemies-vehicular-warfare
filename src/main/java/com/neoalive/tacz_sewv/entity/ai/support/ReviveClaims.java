package com.neoalive.tacz_sewv.entity.ai.support;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.IPmcDowned;

/**
 * One reviver per downed patient (player or PMC). Without this every nearby unit's
 * {@code PlayerReviveGoal}/{@code PmcReviveGoal} locks the same bleed-out and they clump.
 * Keys are entity network ids — transient, same rule as escort/board orders.
 *
 * <p>Only on-foot infantry may revive — a seated crew cannot leave the hull to channel aid,
 * and a claim held by a passenger would permanently block infantry helpers.
 */
public final class ReviveClaims {

    private static final Map<Integer, Integer> PATIENT_TO_REVIVER = new HashMap<>();

    private ReviveClaims() {}

    /**
     * Alive, on foot, not themselves downed, not mortar-committed.
     * Seated vehicle crew are never eligible — vehicles cannot revive.
     */
    public static boolean isEligibleReviver(Entity entity) {
        if (!(entity instanceof PmcUnitEntity unit) || !unit.isAlive()) return false;
        if (unit.isPassenger()) return false;
        if (unit instanceof IPmcDowned downed && downed.sewv$isDowned()) return false;
        return !MortarSupport.hasMortarClaim(unit);
    }

    /** True when nobody holds the patient, or {@code reviverId} already does. */
    public static boolean isFreeOrMine(Level level, int patientId, int reviverId) {
        Integer holder = PATIENT_TO_REVIVER.get(patientId);
        if (holder == null || holder == reviverId) return true;
        return !isEligibleReviver(level.getEntity(holder));
    }

    /** Claim if free or held by an ineligible/missing unit. False if another eligible unit owns it. */
    public static boolean tryClaim(Level level, int patientId, int reviverId) {
        Integer holder = PATIENT_TO_REVIVER.get(patientId);
        if (holder != null && holder != reviverId && isEligibleReviver(level.getEntity(holder))) {
            return false;
        }
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

    /** Drop any claim this reviver holds — call when it leaves the level without its goal's stop() running. */
    public static void forgetReviver(int reviverId) {
        PATIENT_TO_REVIVER.values().removeIf(v -> v == reviverId);
    }
}
