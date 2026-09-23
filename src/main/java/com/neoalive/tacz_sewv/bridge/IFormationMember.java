package com.neoalive.tacz_sewv.bridge;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The frozen cardinal a vehicle formation is laid out along, carried on a unit entity and
 * read by {@link com.neoalive.tacz_sewv.entity.ai.support.VehicleFormation}. Set server-side by
 * {@link com.neoalive.tacz_sewv.network.PacketVehicleFormation}, which is its only writer.
 *
 * <p>SEM's own formation basis is the commander's live yaw, so the shape spins as the player
 * looks around. That is survivable for infantry at 2-block spacing and hopeless for hulls at
 * 12 — hence a cardinal the player designates once and the formation then holds, whatever the
 * camera does afterwards. NONE is the resting state: a unit under a FORM_* order with no axis
 * is in a plain SEM infantry formation, and no hull should drive anywhere for it.
 *
 * <p>Stored in the entity's Forge persistent data rather than a mixin field, per the rule in
 * CLAUDE.md — an axis is id-free, so nothing stops it persisting for the session. Reload
 * coherence is not required for Combined Arms formations.
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
    String TAG_FORMATION_WIDTH = "tacz_sewv_formation_width";
    String TAG_FORMATION_LENGTH = "tacz_sewv_formation_length";
    String TAG_FORMATION_ANCHOR_MODE = "tacz_sewv_formation_anchor_mode";
    String TAG_FORMATION_ANCHOR_SET = "tacz_sewv_formation_anchor_set";
    String TAG_FORMATION_ANCHOR_X = "tacz_sewv_formation_anchor_x";
    String TAG_FORMATION_ANCHOR_Y = "tacz_sewv_formation_anchor_y";
    String TAG_FORMATION_ANCHOR_Z = "tacz_sewv_formation_anchor_z";
    /** One-shot: armed by {@code MixinPacketIssueOrder}, consumed by {@code MixinPmcUnitEntity}. */
    String TAG_FORMATION_CARRY = "tacz_sewv_formation_carry";

    float DEFAULT_STRETCH = 1.0f;

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

    default void sewv$setFormationWidth(float width) {
        ((Entity) this).getPersistentData().putFloat(TAG_FORMATION_WIDTH, width);
    }

    default float sewv$getFormationWidth() {
        float w = ((Entity) this).getPersistentData().getFloat(TAG_FORMATION_WIDTH);
        return w <= 0.0f ? DEFAULT_STRETCH : w;
    }

    default void sewv$setFormationLength(float length) {
        ((Entity) this).getPersistentData().putFloat(TAG_FORMATION_LENGTH, length);
    }

    default float sewv$getFormationLength() {
        float l = ((Entity) this).getPersistentData().getFloat(TAG_FORMATION_LENGTH);
        return l <= 0.0f ? DEFAULT_STRETCH : l;
    }

    // Anchor mode (id from FormationAnchorMode — kept as a raw int here the same way the shape
    // id is, so this interface stays independent of the entity.ai.support package) plus the
    // frozen world point POSITION mode holds. A missing/absent anchor pos means "not frozen yet";
    // resolveAnchor's caller supplies the live commander position to fall back on.
    default void sewv$setFormationAnchorMode(int mode) {
        ((Entity) this).getPersistentData().putInt(TAG_FORMATION_ANCHOR_MODE, mode);
    }

    default int sewv$getFormationAnchorMode() {
        return ((Entity) this).getPersistentData().getInt(TAG_FORMATION_ANCHOR_MODE);
    }

    default void sewv$setFormationAnchorPos(@Nullable Vec3 pos) {
        CompoundTag tag = ((Entity) this).getPersistentData();
        tag.putBoolean(TAG_FORMATION_ANCHOR_SET, pos != null);
        if (pos != null) {
            tag.putDouble(TAG_FORMATION_ANCHOR_X, pos.x);
            tag.putDouble(TAG_FORMATION_ANCHOR_Y, pos.y);
            tag.putDouble(TAG_FORMATION_ANCHOR_Z, pos.z);
        }
    }

    @Nullable
    default Vec3 sewv$getFormationAnchorPos() {
        CompoundTag tag = ((Entity) this).getPersistentData();
        if (!tag.getBoolean(TAG_FORMATION_ANCHOR_SET)) return null;
        return new Vec3(tag.getDouble(TAG_FORMATION_ANCHOR_X), tag.getDouble(TAG_FORMATION_ANCHOR_Y),
                tag.getDouble(TAG_FORMATION_ANCHOR_Z));
    }

    /**
     * Arms a one-shot skip so the very next native {@code setFormationIndex} call — SEM sends one
     * with every order packet — does not wipe this unit's formation. Set by
     * {@code MixinPacketIssueOrder} right before a FOLLOW_COMMANDER/MOVE_TO_POSITION order it wants
     * to carry the standing formation across; consumed by {@code MixinPmcUnitEntity}'s wipe guard.
     */
    default void sewv$armFormationCarry() {
        ((Entity) this).getPersistentData().putBoolean(TAG_FORMATION_CARRY, true);
    }

    /** Consumes (clears) the one-shot flag and reports whether it was armed. */
    default boolean sewv$consumeFormationCarry() {
        CompoundTag tag = ((Entity) this).getPersistentData();
        boolean armed = tag.getBoolean(TAG_FORMATION_CARRY);
        tag.remove(TAG_FORMATION_CARRY);
        return armed;
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

    static float clampStretch(float stretch) {
        return Mth.clamp(stretch, 0.5f, 3.0f);
    }
}
