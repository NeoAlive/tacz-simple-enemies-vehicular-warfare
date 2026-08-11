package com.neoalive.tacz_sewv.item;

/**
 * Close-air-support weapon doctrine shown on the radio GUI.
 *
 * <p>Stored on the radio item for the player's next call; not yet wired to
 * {@link com.neoalive.tacz_sewv.entity.ai.plane.PlaneWeapons} — reserved for a future pass.
 */
public enum PlaneAttackMode {
    BOMB,
    CANNON,
    ATS,
    ATA;

    public PlaneAttackMode next() {
        PlaneAttackMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
