package com.neoalive.tacz_sewv.entity.ai.formation;

import com.neoalive.tacz_sewv.entity.ai.support.FormationShape;

/**
 * What to lay out: a shape, its baseline spacing, the width/length stretch and (LINE only) the
 * units-per-row cap. Values are taken as given — clamping a player's stretch is the caller's job,
 * so an AI template is never silently bent by a player-facing limit.
 *
 * @param placement {@link Placement#TRAIL}: every slot trails the anchor, the anchor being whoever
 *                  is followed (the player's semantics). {@link Placement#CENTRED}: the anchor is
 *                  the centre of the slots, for a formation sent to hold a point.
 */
public record FormationSpec(FormationShape shape, double spacing, double width, double length,
                            int rowSize, Placement placement) {

    public enum Placement {
        TRAIL,
        CENTRED
    }
}
