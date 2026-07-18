package com.neoalive.tacz_sewv.entity.ai;

import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;

/**
 * The geometry of a vehicle formation, carried per-unit alongside the axis. SEM only has two FORM
 * order types, so all of these ride one of them as a marker ({@link #semOrder}) — resolveDestination
 * and the follow-set gating key off that — while the actual slot layout is chosen here, in
 * {@link VehicleFormation#slotCenter}.
 *
 * <p>The ordinal is the wire/NBT id ({@link #id()}), so this enum is <b>append-only</b>: new
 * shapes go on the end, or every saved formation remaps.
 */
public enum FormationShape {
    WEDGE,
    COLUMN,
    LINE,
    ECHELON_RIGHT,
    ECHELON_LEFT;

    /** Wire/NBT id. */
    public int id() {
        return ordinal();
    }

    /** WEDGE for anything unrecognised — a malformed packet or NBT reads as the default shape. */
    public static FormationShape byId(int id) {
        FormationShape[] shapes = values();
        return id >= 0 && id < shapes.length ? shapes[id] : WEDGE;
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
