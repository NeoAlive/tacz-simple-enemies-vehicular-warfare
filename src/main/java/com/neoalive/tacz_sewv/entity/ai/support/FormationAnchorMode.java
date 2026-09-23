package com.neoalive.tacz_sewv.entity.ai.support;

/**
 * What a formation's slot geometry is centred on. PLAYER re-centres on the commander's live
 * position every tick (today's only behaviour, and still the default) — the formation moves as
 * the player does, under FORM_WEDGE/FORM_COLUMN, FOLLOW_COMMANDER or MOVE_TO_POSITION alike.
 * POSITION centres on a frozen world point instead ({@link IFormationMember#sewv$getFormationAnchorPos}
 * via {@link com.neoalive.tacz_sewv.bridge.IFormationMember}) — the formation holds its shape
 * where it was formed (or where a MOVE_TO_POSITION order last sent it) even while the commander
 * walks off.
 *
 * <p>The ordinal is the wire/NBT id, so this enum is append-only.
 */
public enum FormationAnchorMode {
    PLAYER,
    POSITION;

    public int id() {
        return ordinal();
    }

    /** PLAYER for anything unrecognised — a malformed packet or missing NBT reads as the default. */
    public static FormationAnchorMode byId(int id) {
        FormationAnchorMode[] modes = values();
        return id >= 0 && id < modes.length ? modes[id] : PLAYER;
    }
}
