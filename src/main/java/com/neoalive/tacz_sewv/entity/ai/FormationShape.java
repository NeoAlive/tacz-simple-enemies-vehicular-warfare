package com.neoalive.tacz_sewv.entity.ai;

import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;

/**
 * The geometry of a vehicle formation, carried per-unit alongside the axis. SEM only has two FORM
 * order types, so all of these ride one of them as a marker ({@link #semOrder}) — resolveDestination
 * and the follow-set gating key off that — while the actual slot layout is chosen here, in
 * {@link VehicleFormation#slotCenter}.
 */
public enum FormationShape {
    WEDGE(0),
    COLUMN(1),
    LINE(2),
    ECHELON_RIGHT(3),
    ECHELON_LEFT(4);

    // Stable wire/NBT id, decoupled from ordinal so reordering the enum can't remap a saved formation.
    public final int id;

    FormationShape(int id) {
        this.id = id;
    }

    public static FormationShape byId(int id) {
        for (FormationShape shape : values()) {
            if (shape.id == id) return shape;
        }
        return WEDGE;
    }

    /**
     * The SEM order a formation of this shape rides. It only has to land the unit in SEM's FORM set
     * (so the follow-while-fighting leash and our resolveDestination both recognise it); the shape
     * itself drives the geometry. Column keeps FORM_COLUMN so SEM's own column handling still lines
     * up; everything else uses FORM_WEDGE.
     */
    public OrderType semOrder() {
        return this == COLUMN ? OrderType.FORM_COLUMN : OrderType.FORM_WEDGE;
    }
}
