package com.neoalive.tacz_sewv.entity.ai.utility;

import javax.annotation.Nullable;

/**
 * The things a vehicle crew can decide to do.
 *
 * <p>Every constant here <b>must</b> have a case in the drive goal's dispatch. An action that
 * scores but cannot execute is worse than one that does not exist: it wins the vote and the hull
 * then sits releasing its steering inputs, which is exactly the parked-statue failure this whole
 * layer replaces. So the enum grows only alongside the dispatch, never ahead of it.
 */
public enum Action {

    /** Hold the standoff band for the target's type and work the gun. The default fight. */
    ATTACK("attack"),
    /** Close on the target. */
    ADVANCE("advance"),
    /** Stop where we are. Still fires — a hull is a turret that happens to have tracks. */
    HOLD("hold"),
    /** Open the distance, front armor and gun kept on the threat. */
    RETREAT("retreat"),
    /** Pop a smoke volley. Screens the hull and breaks the enemy's line of fire. */
    DEPLOY_SMOKE("deploySmoke"),
    /** Work around the ring anticlockwise for a better shot or a weaker facing. */
    FLANK_LEFT("flankLeft"),
    /** Work around the ring clockwise. */
    FLANK_RIGHT("flankRight"),

    /**
     * Call the target in to a mortar battery behind us.
     *
     * <p>This and the three below are <b>requests, not manoeuvres</b>: making the call takes no
     * time and moves nothing, so each one carries on holding the standoff band exactly as
     * {@link #ATTACK} would. That is deliberate — a crew that stopped driving to use its radio
     * would be a crew standing still in a tank battle.
     */
    CALL_MORTARS("callMortars"),
    /** Call the target in to a TOW launcher covering us. */
    CALL_TOW("callTow"),
    /** Call the target in to a friendly aircraft already airborne nearby. */
    CALL_CAS("callCas"),
    /** Hand our target to nearby friendlies who have none of their own. */
    DELEGATE_TARGET("delegateTarget");

    /** The key naming this action in the weights file. */
    public final String key;

    Action(String key) {
        this.key = key;
    }

    public static final Action[] VALUES = values();

    @Nullable
    public static Action byKey(String key) {
        for (Action action : VALUES) {
            if (action.key.equals(key)) return action;
        }
        return null;
    }

    /** True for the two flanks, which share every scoring rule but their direction. */
    public boolean isFlank() {
        return this == FLANK_LEFT || this == FLANK_RIGHT;
    }
}
