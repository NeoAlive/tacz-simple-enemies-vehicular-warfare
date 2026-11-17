package com.neoalive.tacz_sewv.bridge;

import java.util.UUID;

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
    /** Baseline spacing frozen at assignment, so mounting or dismounting mid-formation cannot rescale a slot. */
    String TAG_FORMATION_SPACING = "tacz_sewv_formation_spacing";
    /** {@code FormationComposition.Kind} ordinal + 1 at assignment; 0 = predates the field. */
    String TAG_FORMATION_KIND = "tacz_sewv_formation_kind";
    /** Which formation this unit was ordered into, so the survivors of one can close up after a loss. */
    String TAG_FORMATION_GROUP = "tacz_sewv_formation_group";
    /** Free heading in degrees (Minecraft yaw); absent means "face the axis", which old saves always do. */
    String TAG_FORMATION_YAW = "tacz_sewv_formation_yaw";
    /** A UNIT-subject follower's leader. A UUID is stable across sessions, so unlike a network id it persists. */
    String TAG_FORMATION_LEADER = "tacz_sewv_formation_leader";
    /** A UNIT-subject follower told to cease fire through its leader; its own SEM order stays the formation's. */
    String TAG_FORMATION_HOLD_FIRE = "tacz_sewv_formation_hold_fire";
    /** On the leader only: the group it leads. Deliberately not dropped by {@code VehicleFormation.clear}. */
    String TAG_FORMATION_LEADS = "tacz_sewv_formation_leads";

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

    // Spacing, kind, group and yaw are id-free and persist like the rest. Zero / absent means "not recorded":
    // a formation saved before these fields existed falls back to the live reads it always used.
    default void sewv$setFormationSpacing(double spacing) {
        ((Entity) this).getPersistentData().putDouble(TAG_FORMATION_SPACING, spacing);
    }

    default double sewv$getFormationSpacing() {
        return ((Entity) this).getPersistentData().getDouble(TAG_FORMATION_SPACING);
    }

    default void sewv$setFormationKind(int kindOrdinalPlusOne) {
        ((Entity) this).getPersistentData().putInt(TAG_FORMATION_KIND, kindOrdinalPlusOne);
    }

    default int sewv$getFormationKind() {
        return ((Entity) this).getPersistentData().getInt(TAG_FORMATION_KIND);
    }

    default void sewv$setFormationGroup(long group) {
        ((Entity) this).getPersistentData().putLong(TAG_FORMATION_GROUP, group);
    }

    default long sewv$getFormationGroup() {
        return ((Entity) this).getPersistentData().getLong(TAG_FORMATION_GROUP);
    }

    default void sewv$setFormationYaw(float yaw) {
        CompoundTag tag = ((Entity) this).getPersistentData();
        if (Float.isFinite(yaw)) tag.putFloat(TAG_FORMATION_YAW, yaw);
        else tag.remove(TAG_FORMATION_YAW);
    }

    /** NaN when no free heading was set — the unit faces its cardinal axis. */
    default float sewv$getFormationYaw() {
        CompoundTag tag = ((Entity) this).getPersistentData();
        return tag.contains(TAG_FORMATION_YAW) ? tag.getFloat(TAG_FORMATION_YAW) : Float.NaN;
    }

    // UNIT subject (FormationSubjects is the only writer). A follower carries its leader's UUID and its
    // hold-fire posture; the leader carries only the group id it leads, and has no slot of its own.
    default void sewv$setFormationLeader(@Nullable UUID leader) {
        CompoundTag tag = ((Entity) this).getPersistentData();
        if (leader != null) tag.putUUID(TAG_FORMATION_LEADER, leader);
        else tag.remove(TAG_FORMATION_LEADER);
    }

    @Nullable
    default UUID sewv$getFormationLeader() {
        CompoundTag tag = ((Entity) this).getPersistentData();
        return tag.hasUUID(TAG_FORMATION_LEADER) ? tag.getUUID(TAG_FORMATION_LEADER) : null;
    }

    default void sewv$setFormationHoldFire(boolean hold) {
        CompoundTag tag = ((Entity) this).getPersistentData();
        if (hold) tag.putBoolean(TAG_FORMATION_HOLD_FIRE, true);
        else tag.remove(TAG_FORMATION_HOLD_FIRE);
    }

    default boolean sewv$getFormationHoldFire() {
        return ((Entity) this).getPersistentData().getBoolean(TAG_FORMATION_HOLD_FIRE);
    }

    default void sewv$setFormationLeads(long group) {
        CompoundTag tag = ((Entity) this).getPersistentData();
        if (group != 0L) tag.putLong(TAG_FORMATION_LEADS, group);
        else tag.remove(TAG_FORMATION_LEADS);
    }

    /** 0 when this unit leads no formation. */
    default long sewv$getFormationLeads() {
        return ((Entity) this).getPersistentData().getLong(TAG_FORMATION_LEADS);
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
