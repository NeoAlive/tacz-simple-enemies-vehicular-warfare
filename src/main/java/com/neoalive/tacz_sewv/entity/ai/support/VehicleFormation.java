package com.neoalive.tacz_sewv.entity.ai.support;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.IFormationMember;

/**
 * The shape of a Combined Arms formation and who stands where in it.
 *
 * <p>Geometry is SEM-inspired (column / wedge) plus LINE and echelons, laid out against a frozen
 * cardinal ({@link IFormationMember}) with hardcoded baseline spacing and WIDTH/LENGTH stretch.
 *
 * <p>Server-side only for terrain projection. Preview math ({@link #slotCenter}) is pure vector
 * work and is safe on the client.
 *
 * <p><b>Anchor mode</b> ({@link FormationAnchorMode}): {@link #resolveAnchor} is the one place
 * that turns PLAYER into the live commander position and POSITION into the frozen point stored
 * in {@link IFormationMember#sewv$getFormationAnchorPos}, so every call site — the
 * FORM_WEDGE/FORM_COLUMN case, FOLLOW_COMMANDER, MOVE_TO_POSITION, on-foot and mounted alike —
 * reads the same answer. {@link #formationSlotCenter}/{@link #formationSlotPos} bundle "is this
 * unit even formed up" with that anchor resolution so callers get one null check instead of five
 * field reads.
 */
public final class VehicleFormation {

    private VehicleFormation() {}

    /**
     * SEM's wedge widens 1.25x as fast as it deepens (FormationUtils: 2.5 lateral against 2.0
     * back). Keeping it as a ratio rather than a second spacing is what makes one baseline scale
     * the whole shape without changing its proportions.
     */
    private static final double LATERAL_RATIO = 2.5 / 2.0;

    /** A terrain probe further than this from the commander's own level is not believable. */
    private static final int MAX_SLOT_RISE = 16;

    /** LINE units-per-row fallback for a unit whose stored row size predates that field. */
    private static final int DEFAULT_ROW_SIZE = 4;

    /** The heading the formation points along. Cardinal step vectors are already unit length. */
    public static Vec3 forward(Direction axis) {
        return new Vec3(axis.getStepX(), 0.0, axis.getStepZ());
    }

    /**
     * Where slot {@code index} sits, relative to {@code anchor}, for a {@code shape} pointing along
     * {@code axis}. {@code rowSize} is the units-per-row cap and only a LINE reads it.
     *
     * <p>Note the sign: every slot lands at {@code anchor + forward * -back}, so the formation
     * trails BEHIND the anchor. {@code perp} points to the formation's right.
     */
    public static Vec3 slotCenter(Vec3 anchor, Direction axis, FormationShape shape, int index,
                                  int rowSize, double baselineSpacing,
                                  float widthStretch, float lengthStretch) {
        double spacing = baselineSpacing;
        double w = IFormationMember.clampStretch(widthStretch);
        double l = IFormationMember.clampStretch(lengthStretch);
        Vec3 forward = forward(axis);
        Vec3 perp = new Vec3(-forward.z, 0.0, forward.x);

        double back;
        double lateral;
        switch (shape) {
            case COLUMN -> {
                back = spacing * (1 + index) * l;
                lateral = 0.0;
            }
            case LINE -> {
                int cols = Math.max(1, rowSize);
                int row = index / cols;
                int col = index % cols;
                back = spacing * (1 + row) * l;
                lateral = (col - (cols - 1) / 2.0) * spacing * w;
            }
            case ECHELON_RIGHT -> {
                back = spacing * (1 + index) * l;
                lateral = spacing * index * w;
            }
            case ECHELON_LEFT -> {
                back = spacing * (1 + index) * l;
                lateral = -spacing * index * w;
            }
            default -> { // WEDGE
                if (index == 0) {
                    back = spacing * l;
                    lateral = 0.0;
                } else {
                    int rank = (index - 1) / 2 + 1;
                    back = spacing * (1 + rank) * l;
                    lateral = (index % 2 != 0 ? -1 : 1) * spacing * LATERAL_RATIO * rank * w;
                }
            }
        }
        return anchor.add(forward.scale(-back)).add(perp.scale(lateral));
    }

    /** Drive-to point for a slot: its centre, dropped onto the terrain underneath it. */
    public static BlockPos slotPos(Level level, Vec3 anchor, Direction axis, FormationShape shape,
                                   int index, int rowSize, double baselineSpacing,
                                   float widthStretch, float lengthStretch,
                                   FormationComposition.Kind kind) {
        Vec3 center = slotCenter(anchor, axis, shape, index, rowSize,
                baselineSpacing, widthStretch, lengthStretch);
        BlockPos xz = BlockPos.containing(center.x, anchor.y, center.z);
        if (kind == FormationComposition.Kind.SHIP) {
            BlockPos water = WaterSupport.projectToWater(level, xz);
            if (water != null) return water;
        }
        return BlockPos.containing(center.x, groundY(level, center.x, center.z, anchor.y), center.z);
    }

    /**
     * True when any pair of intended slot centres is closer than the overlap floor for the
     * current stretch. Call with the same numbering {@link #assign} will use (drivers then loose).
     */
    public static boolean slotsOverlap(Vec3 anchor, Direction axis, FormationShape shape,
                                       int slotCount, int rowSize, double baselineSpacing,
                                       float widthStretch, float lengthStretch) {
        if (slotCount < 2) return false;
        double minDist = Math.max(1.0,
                baselineSpacing * Math.min(widthStretch, lengthStretch) * 0.85);
        double minDistSq = minDist * minDist;
        Vec3[] centres = new Vec3[slotCount];
        for (int i = 0; i < slotCount; i++) {
            centres[i] = slotCenter(anchor, axis, shape, i, rowSize,
                    baselineSpacing, widthStretch, lengthStretch);
        }
        for (int i = 0; i < slotCount; i++) {
            for (int j = i + 1; j < slotCount; j++) {
                double dx = centres[i].x - centres[j].x;
                double dz = centres[i].z - centres[j].z;
                if (dx * dx + dz * dz < minDistSq) return true;
            }
        }
        return false;
    }

    /**
     * How many slots {@link #assign} will allocate for this selection (one per hull + each loose
     * infantry). Riders do not add slots.
     */
    public static int slotCountFor(List<PmcUnitEntity> units) {
        int hulls = 0;
        int loose = 0;
        for (PmcUnitEntity pmc : units) {
            Entity hull = pmc.getVehicle();
            if (hull instanceof VehicleEntity) {
                if (hull.getFirstPassenger() == pmc) hulls++;
            } else {
                loose++;
            }
        }
        return hulls + loose;
    }

    /**
     * The surface a hull would come to rest on. NO_LEAVES because a canopy is not ground.
     *
     * <p>SERVER ONLY: ClientLevel chunks carry only MOTION_BLOCKING and WORLD_SURFACE.
     */
    private static double groundY(Level level, double x, double z, double anchorY) {
        int probed = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(x), Mth.floor(z));
        return Math.abs(probed - anchorY) > MAX_SLOT_RISE ? anchorY : probed;
    }

    /**
     * Number a selection into formation slots and issue the order. Returns the count of HULLS
     * formed (infantry-only selections return the infantry count so feedback still works).
     *
     * <p>{@code anchorMode} is stored per-unit and, for POSITION, frozen at {@code commander}'s
     * current position right now — see {@link #resolveAnchor}.
     */
    public static int assign(Player commander, List<PmcUnitEntity> units, FormationShape shape,
                             Direction axis, int rowSize, float widthStretch, float lengthStretch,
                             FormationComposition.Kind kind, FormationAnchorMode anchorMode) {
        OrderType order = shape.semOrder();
        float width = IFormationMember.clampStretch(widthStretch);
        float length = IFormationMember.clampStretch(lengthStretch);
        List<PmcUnitEntity> drivers = new ArrayList<>();
        List<PmcUnitEntity> riders = new ArrayList<>();
        List<PmcUnitEntity> loose = new ArrayList<>();

        for (PmcUnitEntity pmc : units) {
            Entity hull = pmc.getVehicle();
            if (hull instanceof VehicleEntity) {
                if (hull.getFirstPassenger() == pmc) drivers.add(pmc); else riders.add(pmc);
            } else {
                loose.add(pmc);
            }
        }

        Comparator<PmcUnitEntity> byRange = Comparator
                .comparingDouble((PmcUnitEntity u) -> u.distanceToSqr(commander))
                .thenComparing(PmcUnitEntity::getUUID);
        drivers.sort(byRange);
        loose.sort(byRange);

        Vec3 frozenAnchor = anchorMode == FormationAnchorMode.POSITION ? commander.position() : null;

        int slot = 0;
        Map<Entity, Integer> hullSlots = new HashMap<>();
        for (PmcUnitEntity driver : drivers) {
            hullSlots.put(driver.getVehicle(), slot);
            apply(driver, order, axis, slot++, shape, rowSize, width, length, anchorMode, frozenAnchor);
        }
        for (PmcUnitEntity infantry : loose) {
            apply(infantry, order, axis, slot++, shape, rowSize, width, length, anchorMode, frozenAnchor);
        }

        for (PmcUnitEntity rider : riders) {
            Integer hullSlot = hullSlots.get(rider.getVehicle());
            if (hullSlot != null) {
                apply(rider, order, axis, hullSlot, shape, rowSize, width, length, anchorMode, frozenAnchor);
            }
        }
        // Infantry-only: report how many formed; otherwise hull count (legacy feedback keys).
        return kind == FormationComposition.Kind.INFANTRY ? loose.size() : hullSlots.size();
    }

    private static void apply(PmcUnitEntity pmc, OrderType order, Direction axis, int slot,
                              FormationShape shape, int rowSize, float width, float length,
                              FormationAnchorMode anchorMode, @Nullable Vec3 frozenAnchor) {
        PatrolSupport.clearSweepMembership(pmc, "VehicleFormation.dismiss");
        pmc.releaseMovementLock();
        pmc.setFormationIndex(slot);
        IFormationMember member = (IFormationMember) pmc;
        member.sewv$setFormationDirection(axis);
        member.sewv$setFormationShape(shape.id());
        member.sewv$setFormationRowSize(rowSize);
        member.sewv$setFormationWidth(width);
        member.sewv$setFormationLength(length);
        member.sewv$setFormationAnchorMode(anchorMode.id());
        // Re-forming always re-freezes (or drops) the anchor fresh rather than keeping a stale
        // point from whatever formation this unit was previously in.
        member.sewv$setFormationAnchorPos(frozenAnchor);
        pmc.resetCommanderGoalCooldown();
        pmc.setOrder(order);
    }

    /** Resolve stretch + baseline for a unit already under a formation order. */
    public static double baselineForUnit(PmcUnitEntity pmc) {
        FormationComposition.Kind kind = FormationComposition.classify(pmc);
        if (kind == null) return FormationComposition.SPACING_VEHICLE;
        return FormationComposition.baselineSpacing(kind);
    }

    @Nullable
    public static FormationComposition.Kind kindForUnit(PmcUnitEntity pmc) {
        return FormationComposition.classify(pmc);
    }

    /**
     * The anchor a formed-up unit's slot math should use: the live commander position for
     * PLAYER, or the frozen point for POSITION (falling back to {@code commanderPos} if nothing
     * has been frozen yet — should not normally happen, since {@link #assign} and
     * {@link #freezeAnchor} both set it whenever POSITION mode is engaged).
     */
    public static Vec3 resolveAnchor(PmcUnitEntity pmc, Vec3 commanderPos) {
        IFormationMember member = (IFormationMember) pmc;
        if (FormationAnchorMode.byId(member.sewv$getFormationAnchorMode()) != FormationAnchorMode.POSITION) {
            return commanderPos;
        }
        Vec3 frozen = member.sewv$getFormationAnchorPos();
        return frozen != null ? frozen : commanderPos;
    }

    /** Re-freezes a POSITION-anchored unit's slot point — MOVE_TO_POSITION moves the anchor. */
    public static void freezeAnchor(PmcUnitEntity pmc, Vec3 point) {
        ((IFormationMember) pmc).sewv$setFormationAnchorPos(point);
    }

    /** Formation parameters read off a unit, or null when it isn't formed up (no frozen axis). */
    @Nullable
    private static Layout layoutFor(PmcUnitEntity pmc) {
        IFormationMember member = (IFormationMember) pmc;
        Direction axis = member.sewv$getFormationDirection();
        int slot = pmc.getFormationIndex();
        if (axis == null || slot < 0) return null;
        int rowSize = member.sewv$getFormationRowSize();
        if (rowSize < 1) rowSize = DEFAULT_ROW_SIZE;
        return new Layout(axis, FormationShape.byId(member.sewv$getFormationShape()), slot, rowSize,
                member.sewv$getFormationWidth(), member.sewv$getFormationLength());
    }

    private record Layout(Direction axis, FormationShape shape, int slot, int rowSize,
                          float width, float length) {}

    /**
     * The slot centre a unit under FOLLOW_COMMANDER/MOVE_TO_POSITION/FORM_* should path to, or
     * null when it isn't in a formation — callers fall back to their own plain-order behaviour.
     * Infantry-scale: no terrain projection, matching how {@code MixinCommanderOrderGoal} already
     * fed SEM's own on-foot navigation.
     */
    @Nullable
    public static Vec3 formationSlotCenter(PmcUnitEntity pmc, Vec3 commanderPos) {
        Layout l = layoutFor(pmc);
        if (l == null) return null;
        return slotCenter(resolveAnchor(pmc, commanderPos), l.axis(), l.shape(), l.slot(), l.rowSize(),
                baselineForUnit(pmc), l.width(), l.length());
    }

    /** Hull-scale equivalent of {@link #formationSlotCenter}, terrain/water-projected. */
    @Nullable
    public static BlockPos formationSlotPos(Level level, PmcUnitEntity pmc, Vec3 commanderPos) {
        Layout l = layoutFor(pmc);
        if (l == null) return null;
        FormationComposition.Kind kind = kindForUnit(pmc);
        if (kind == null) kind = FormationComposition.Kind.GROUND;
        return slotPos(level, resolveAnchor(pmc, commanderPos), l.axis(), l.shape(), l.slot(), l.rowSize(),
                baselineForUnit(pmc), l.width(), l.length(), kind);
    }

    /** Drops every formation tag — DISMISS / Quick Wheel CANCEL, or a fresh non-formation order. */
    public static void clear(PmcUnitEntity pmc) {
        var data = pmc.getPersistentData();
        data.remove(IFormationMember.TAG_FORMATION_AXIS);
        data.remove(IFormationMember.TAG_FORMATION_SHAPE);
        data.remove(IFormationMember.TAG_FORMATION_ROWSIZE);
        data.remove(IFormationMember.TAG_FORMATION_WIDTH);
        data.remove(IFormationMember.TAG_FORMATION_LENGTH);
        data.remove(IFormationMember.TAG_FORMATION_ANCHOR_MODE);
        data.remove(IFormationMember.TAG_FORMATION_ANCHOR_SET);
        data.remove(IFormationMember.TAG_FORMATION_ANCHOR_X);
        data.remove(IFormationMember.TAG_FORMATION_ANCHOR_Y);
        data.remove(IFormationMember.TAG_FORMATION_ANCHOR_Z);
    }
}
