package com.neoalive.tacz_sewv.bridge;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;

/**
 * The frozen cardinal a vehicle formation is laid out along, carried on a unit entity and
 * read by {@link com.neoalive.tacz_sewv.entity.ai.VehicleFormation}. Set server-side by
 * {@link com.neoalive.tacz_sewv.network.PacketVehicleFormation}, which is its only writer.
 *
 * <p>SEM's own formation basis is the commander's live yaw, so the shape spins as the player
 * looks around. That is survivable for infantry at 2-block spacing and hopeless for hulls at
 * 12 — hence a cardinal the player designates once and the formation then holds, whatever the
 * camera does afterwards. NONE is the resting state: a unit under a FORM_* order with no axis
 * is in a plain SEM infantry formation, and no hull should drive anywhere for it.
 *
 * <p>Stored in the entity's Forge persistent data rather than a mixin field, per the rule in
 * CLAUDE.md — an axis is id-free, so nothing stops it persisting, and it <em>must</em> persist:
 * SEM writes both {@code CurrentOrder} and {@code FormationIndex} to NBT, so a hull comes back
 * from a reload already believing it holds slot 3 of a wedge. An axis that reset to NONE there
 * would leave that wedge silently inert with nothing on screen to explain why. All three parts
 * of the order survive together or none of them do.
 *
 * <p>Only {@link net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity} needs this: formations
 * arrive through SEM's order queue, and only PMC units have one. The unit mixin needs no method
 * bodies — these defaults are the whole implementation ({@code getInt} on a missing key returns
 * 0 == AXIS_NONE, which is exactly the right default).
 */
public interface IFormationMember {
    int AXIS_NONE = 0;
    int AXIS_NORTH = 1; // -Z
    int AXIS_SOUTH = 2; // +Z
    int AXIS_WEST = 3;  // -X
    int AXIS_EAST = 4;  // +X

    String TAG_FORMATION_AXIS = "tacz_sewv_formation_axis";
    String TAG_FORMATION_SHAPE = "tacz_sewv_formation_shape";
    String TAG_FORMATION_ROWSIZE = "tacz_sewv_formation_rowsize";

    default void sewv$setFormationDirection(Direction axis) {
        ((Entity) this).getPersistentData().putInt(TAG_FORMATION_AXIS, axisOf(axis));
    }

    default Direction sewv$getFormationDirection() {
        return directionOf(((Entity) this).getPersistentData().getInt(TAG_FORMATION_AXIS));
    }

    // The FormationShape id (see entity.ai.FormationShape) and, for a LINE, its units-per-row.
    // Both persist for the same reason the axis does — a formation must come back whole from a
    // reload. A missing shape reads as 0 (WEDGE); the axis null-gate keeps a stale shape from ever
    // being applied to a plain SEM infantry formation.
    default void sewv$setFormationShape(int shapeId) {
        ((Entity) this).getPersistentData().putInt(TAG_FORMATION_SHAPE, shapeId);
    }

    default int sewv$getFormationShape() {
        return ((Entity) this).getPersistentData().getInt(TAG_FORMATION_SHAPE);
    }

    default void sewv$setFormationRowSize(int rowSize) {
        ((Entity) this).getPersistentData().putInt(TAG_FORMATION_ROWSIZE, rowSize);
    }

    default int sewv$getFormationRowSize() {
        return ((Entity) this).getPersistentData().getInt(TAG_FORMATION_ROWSIZE);
    }

    /** Null and the two vertical faces both mean "no axis" — a formation is a horizontal thing. */
    static int axisOf(Direction axis) {
        if (axis == null) return AXIS_NONE;
        return switch (axis) {
            case NORTH -> AXIS_NORTH;
            case SOUTH -> AXIS_SOUTH;
            case WEST -> AXIS_WEST;
            case EAST -> AXIS_EAST;
            default -> AXIS_NONE;
        };
    }

    /** Null for AXIS_NONE and for anything unrecognised — a malformed packet reads as "no axis". */
    static Direction directionOf(int axis) {
        return switch (axis) {
            case AXIS_NORTH -> Direction.NORTH;
            case AXIS_SOUTH -> Direction.SOUTH;
            case AXIS_WEST -> Direction.WEST;
            case AXIS_EAST -> Direction.EAST;
            default -> null;
        };
    }
}
