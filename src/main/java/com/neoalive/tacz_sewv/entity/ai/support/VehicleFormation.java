package com.neoalive.tacz_sewv.entity.ai.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.IFormationMember;
import com.neoalive.tacz_sewv.entity.ai.formation.FormationFrame;
import com.neoalive.tacz_sewv.entity.ai.formation.FormationGeometry;
import com.neoalive.tacz_sewv.entity.ai.formation.FormationSpec;
import com.neoalive.tacz_sewv.entity.ai.formation.GroundSnap;
import com.neoalive.tacz_sewv.entity.ai.formation.SlotMatcher;

/**
 * The shape of a Combined Arms formation and who stands where in it.
 *
 * <p>Geometry is SEM-inspired (column / wedge) plus LINE and echelons, laid out against a frozen
 * cardinal ({@link IFormationMember}) with hardcoded baseline spacing and WIDTH/LENGTH stretch.
 * The slot maths itself lives in {@link FormationGeometry}, shared with the AI's formations; this
 * class is the player-order half — per-unit state, anchor resolution and numbering.
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
        FormationSpec spec = new FormationSpec(shape, baselineSpacing,
                IFormationMember.clampStretch(widthStretch), IFormationMember.clampStretch(lengthStretch),
                rowSize, FormationSpec.Placement.TRAIL);
        return FormationGeometry.slot(FormationFrame.ofCardinal(anchor, axis), spec, index, index + 1);
    }

    /** Drive-to point for a slot: its centre, dropped onto the terrain underneath it. */
    public static BlockPos slotPos(Level level, Vec3 anchor, Direction axis, FormationShape shape,
                                   int index, int rowSize, double baselineSpacing,
                                   float widthStretch, float lengthStretch,
                                   FormationComposition.Kind kind) {
        Vec3 center = slotCenter(anchor, axis, shape, index, rowSize,
                baselineSpacing, widthStretch, lengthStretch);
        return GroundSnap.snap(level, center, anchor.y, kind == FormationComposition.Kind.SHIP);
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
     * Slots for this selection under {@code mode}: a UNIT formation's leader stands on the anchor, not a slot,
     * so it has one fewer than {@link #slotCountFor(List)}.
     */
    public static int slotCountFor(List<PmcUnitEntity> units, FormationAnchorMode mode) {
        int slots = slotCountFor(units);
        return mode == FormationAnchorMode.UNIT ? Math.max(0, slots - 1) : slots;
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

    /** Minecraft yaw (degrees) to a horizontal forward: yaw 0 faces +Z (south), 90 faces -X (west). */
    public static Vec3 forwardOfYaw(float yaw) {
        double r = Math.toRadians(yaw);
        return new Vec3(-Math.sin(r), 0.0, Math.cos(r));
    }

    /** The heading a formed-up unit holds, or null when it is not in one. */
    @Nullable
    public static Vec3 forwardOf(PmcUnitEntity pmc) {
        Layout l = layoutFor(pmc);
        if (l == null) return null;
        return Float.isFinite(l.yaw()) ? forwardOfYaw(l.yaw()) : forward(l.axis());
    }

    private static final AtomicLong GROUP_SEQ = new AtomicLong();

    /**
     * Number a selection into formation slots and issue the order. Returns the count of HULLS
     * formed (infantry-only selections return the infantry count so feedback still works).
     *
     * <p>Members are matched to slots by least total squared travel ({@link SlotMatcher}) — the
     * nearest-to-commander-first numbering this replaced crossed paths whenever the selection stood
     * off to one side of the shape. Riders take their hull's slot and add none.
     *
     * <p>{@code yaw} is a free heading in degrees, or NaN to face {@code axis} exactly as before.
     * {@code anchorMode} is stored per-unit and, for POSITION, frozen at {@code commander}'s current
     * position right now — see {@link #resolveAnchor}.
     */
    public static int assign(Player commander, List<PmcUnitEntity> units, FormationShape shape,
                             Direction axis, float yaw, int rowSize, float widthStretch, float lengthStretch,
                             FormationComposition.Kind kind, FormationAnchorMode anchorMode) {
        Vec3 frozenAnchor = anchorMode == FormationAnchorMode.POSITION ? commander.position() : null;
        Vec3 anchor = frozenAnchor != null ? frozenAnchor : commander.position();
        return assign(units, shape, axis, yaw, rowSize, widthStretch, lengthStretch, kind, anchorMode,
                frozenAnchor, anchor, null, nextGroup(commander));
    }

    /**
     * {@link #assign} around a leader instead of the player: {@code units} are the followers only (the leader
     * and its own riders are not in it), laid on slots behind {@code leaderAnchor}. Returns the units formed;
     * {@link FormationSubjects#assignLed} owns the leader's side.
     */
    static int assignAroundLeader(List<PmcUnitEntity> units, FormationShape shape, Direction axis, float yaw,
                                  int rowSize, float widthStretch, float lengthStretch,
                                  FormationComposition.Kind kind, Vec3 leaderAnchor, UUID leader, long group) {
        return assign(units, shape, axis, yaw, rowSize, widthStretch, lengthStretch, kind, FormationAnchorMode.UNIT,
                null, leaderAnchor, leader, group);
    }

    private static int assign(List<PmcUnitEntity> units, FormationShape shape, Direction axis, float yaw,
                              int rowSize, float widthStretch, float lengthStretch, FormationComposition.Kind kind,
                              FormationAnchorMode anchorMode, @Nullable Vec3 frozenAnchor, Vec3 anchor,
                              @Nullable UUID leader, long group) {
        OrderType order = shape.semOrder();
        float width = IFormationMember.clampStretch(widthStretch);
        float length = IFormationMember.clampStretch(lengthStretch);
        List<PmcUnitEntity> holders = new ArrayList<>();
        List<PmcUnitEntity> riders = new ArrayList<>();
        int hulls = 0;
        for (PmcUnitEntity pmc : units) {
            Entity hull = pmc.getVehicle();
            if (hull instanceof VehicleEntity && hull.getFirstPassenger() != pmc) {
                riders.add(pmc);
                continue;
            }
            if (hull instanceof VehicleEntity) hulls++;
            holders.add(pmc);
        }
        // Id order in, so the matcher's tie-breaks (and therefore the whole numbering) are deterministic.
        holders.sort(Comparator.comparingInt(PmcUnitEntity::getId));

        double spacing = FormationComposition.baselineSpacing(kind);
        Layout layout = new Layout(axis, yaw, shape, rowSize, width, length, anchorMode, frozenAnchor, spacing, kind,
                0L, leader);
        int[] slotOf = match(layout, anchor, holders, null);

        Map<Entity, Integer> hullSlots = new HashMap<>();
        for (int i = 0; i < holders.size(); i++) {
            PmcUnitEntity pmc = holders.get(i);
            if (pmc.getVehicle() instanceof VehicleEntity hull) hullSlots.put(hull, slotOf[i]);
            apply(pmc, order, slotOf[i], layout, group);
        }
        for (PmcUnitEntity rider : riders) {
            Integer hullSlot = hullSlots.get(rider.getVehicle());
            if (hullSlot != null) apply(rider, order, hullSlot, layout, group);
        }
        // Infantry-only: report how many formed; otherwise hull count (legacy feedback keys).
        return kind == FormationComposition.Kind.INFANTRY ? holders.size() - hulls : hulls;
    }

    /**
     * Slot for each holder (a driver stands for its hull) by least squared travel, last slot defended for one
     * spacing squared when {@code prev} is given. Shared by {@link #assign} and {@link FormationReform}.
     */
    static int[] match(Layout layout, Vec3 anchor, List<PmcUnitEntity> holders, @Nullable int[] prev) {
        int n = holders.size();
        double[] mx = new double[n];
        double[] mz = new double[n];
        double[] sx = new double[n];
        double[] sz = new double[n];
        for (int i = 0; i < n; i++) {
            Entity body = holders.get(i).getRootVehicle();
            mx[i] = body.getX();
            mz[i] = body.getZ();
            Vec3 c = slotCenter(layout, anchor, i);
            sx[i] = c.x;
            sz[i] = c.z;
        }
        return SlotMatcher.match(mx, mz, sx, sz, prev, prev == null ? 0.0 : layout.spacing() * layout.spacing());
    }

    static long nextGroup(Entity commander) {
        // Game time keeps ids from a previous session distinct; the sequence separates orders in one tick.
        return commander.level().getGameTime() * 64L + (GROUP_SEQ.incrementAndGet() & 63L) + 1L;
    }

    private static void apply(PmcUnitEntity pmc, OrderType order, int slot, Layout layout, long group) {
        PatrolSupport.clearSweepMembership(pmc, "VehicleFormation.dismiss");
        pmc.releaseMovementLock();
        pmc.setFormationIndex(slot);
        IFormationMember member = (IFormationMember) pmc;
        member.sewv$setFormationDirection(layout.axis());
        member.sewv$setFormationYaw(layout.yaw());
        member.sewv$setFormationShape(layout.shape().id());
        member.sewv$setFormationRowSize(layout.rowSize());
        member.sewv$setFormationWidth(layout.width());
        member.sewv$setFormationLength(layout.length());
        member.sewv$setFormationAnchorMode(layout.anchorMode().id());
        // Re-forming always re-freezes (or drops) the anchor fresh rather than keeping a stale
        // point from whatever formation this unit was previously in.
        member.sewv$setFormationAnchorPos(layout.frozenAnchor());
        member.sewv$setFormationSpacing(layout.spacing());
        member.sewv$setFormationKind(layout.kind() == null ? 0 : layout.kind().ordinal() + 1);
        member.sewv$setFormationGroup(group);
        member.sewv$setFormationLeader(layout.leader());
        // A fresh formation fires freely until its leader is told otherwise.
        member.sewv$setFormationHoldFire(false);
        LAYOUTS.remove(pmc);
        FormationReform.track(group, pmc, layout.anchorMode() == FormationAnchorMode.UNIT);
        pmc.resetCommanderGoalCooldown();
        pmc.setOrder(order);
    }

    /**
     * Baseline spacing for a unit under a formation order: frozen at assignment, so a driver that dismounts
     * keeps its hull-scale slot instead of jumping to infantry spacing in the middle of the shape.
     */
    public static double baselineForUnit(PmcUnitEntity pmc) {
        Layout l = layoutFor(pmc);
        if (l != null) return l.spacing();
        FormationComposition.Kind kind = FormationComposition.classify(pmc);
        return kind == null ? FormationComposition.SPACING_VEHICLE : FormationComposition.baselineSpacing(kind);
    }

    /** The kind the formation was ordered as, falling back to the live classification. */
    @Nullable
    public static FormationComposition.Kind kindForUnit(PmcUnitEntity pmc) {
        Layout l = layoutFor(pmc);
        if (l != null && l.kind() != null) return l.kind();
        return FormationComposition.classify(pmc);
    }

    /**
     * The anchor a formed-up unit's slot math should use: the live commander position for
     * PLAYER, or the frozen point for POSITION (falling back to {@code commanderPos} if nothing
     * has been frozen yet — should not normally happen, since {@link #assign} and
     * {@link #freezeAnchor} both set it whenever POSITION mode is engaged), or the leader's live
     * position for UNIT — never the commander's. Null only for UNIT, when the leader is not loaded
     * in this level (or this is the client): the follower then holds where it stands.
     */
    @Nullable
    public static Vec3 resolveAnchor(PmcUnitEntity pmc, Vec3 commanderPos) {
        Layout l = layoutFor(pmc);
        if (l == null) return commanderPos;
        if (l.anchorMode() == FormationAnchorMode.UNIT) return FormationSubjects.leaderAnchor(pmc, l.leader());
        if (l.anchorMode() != FormationAnchorMode.POSITION) return commanderPos;
        return l.frozenAnchor() != null ? l.frozenAnchor() : commanderPos;
    }

    /** Whether this unit stands on a slot of a UNIT-anchored formation. */
    public static boolean isUnitFollower(PmcUnitEntity pmc) {
        Layout l = layoutFor(pmc);
        return l != null && l.anchorMode() == FormationAnchorMode.UNIT;
    }

    /** The anchor mode of the formation this unit stands in, or null when it stands in none. */
    @Nullable
    public static FormationAnchorMode anchorModeOf(PmcUnitEntity pmc) {
        Layout l = layoutFor(pmc);
        return l == null ? null : l.anchorMode();
    }

    /** Re-points a follower at another leader (succession). */
    static void setLeader(PmcUnitEntity pmc, UUID leader) {
        ((IFormationMember) pmc).sewv$setFormationLeader(leader);
        LAYOUTS.remove(pmc);
    }

    /** Turns a follower's formation onto a new heading; the caller re-matches slots afterwards. */
    static void setHeading(PmcUnitEntity pmc, float yaw) {
        IFormationMember member = (IFormationMember) pmc;
        member.sewv$setFormationYaw(yaw);
        member.sewv$setFormationDirection(Direction.fromYRot(yaw));
        LAYOUTS.remove(pmc);
    }

    /** The SEM order a formed-up unit stands under (its shape's FORM_*), or null when it is not formed up. */
    @Nullable
    public static OrderType formationOrderOf(PmcUnitEntity pmc) {
        Layout l = layoutFor(pmc);
        return l == null ? null : l.shape().semOrder();
    }

    /**
     * Re-deals {@code members}' slots around {@code anchor} by least travel, each defending its current slot
     * for one spacing — after a turn, so nobody crosses the formation to reach a slot a neighbour stands by.
     * Riders follow their hull's slot.
     */
    static void rematch(List<PmcUnitEntity> members, Vec3 anchor) {
        List<PmcUnitEntity> holders = new ArrayList<>();
        List<PmcUnitEntity> riders = new ArrayList<>();
        for (PmcUnitEntity pmc : members) {
            Entity hull = pmc.getVehicle();
            if (hull instanceof VehicleEntity && hull.getFirstPassenger() != pmc) riders.add(pmc);
            else holders.add(pmc);
        }
        if (holders.isEmpty()) return;
        holders.sort(Comparator.comparingInt(PmcUnitEntity::getId));
        Layout layout = layoutFor(holders.get(0));
        if (layout == null) return;
        int k = holders.size();
        int[] prev = new int[k];
        for (int i = 0; i < k; i++) {
            int slot = holders.get(i).getFormationIndex();
            prev[i] = slot >= 0 && slot < k ? slot : -1;
        }
        int[] slotOf = match(layout, anchor, holders, prev);
        Map<Entity, Integer> hullSlots = new HashMap<>();
        for (int i = 0; i < k; i++) {
            PmcUnitEntity pmc = holders.get(i);
            if (pmc.getVehicle() instanceof VehicleEntity hull) hullSlots.put(hull, slotOf[i]);
            reslot(pmc, slotOf[i]);
        }
        for (PmcUnitEntity rider : riders) {
            Integer slot = hullSlots.get(rider.getVehicle());
            if (slot != null) reslot(rider, slot);
        }
    }

    /** Moves a member to another slot of the same formation without SEM's setter wiping it. */
    static void reslot(PmcUnitEntity pmc, int slot) {
        if (pmc.getFormationIndex() == slot) return;
        ((IFormationMember) pmc).sewv$armFormationCarry();
        pmc.setFormationIndex(slot);
        pmc.resetCommanderGoalCooldown();
    }

    /** Re-freezes a POSITION-anchored unit's slot point — MOVE_TO_POSITION moves the anchor. */
    public static void freezeAnchor(PmcUnitEntity pmc, Vec3 point) {
        ((IFormationMember) pmc).sewv$setFormationAnchorPos(point);
        LAYOUTS.remove(pmc);
    }

    /**
     * A unit's decoded formation, cached per entity. The goals read it every tick (follow, move, SEM's own
     * formation goal), and decoding is ten persistent-NBT reads plus a hull classification. Every writer in
     * this class drops the entry; the slot index is SEM's own field and is read live, never cached.
     * Weak keys: an unloaded entity takes its entry with it. Synchronized because the integrated client and
     * server share this class.
     */
    private static final Map<PmcUnitEntity, Optional<Layout>> LAYOUTS = Collections.synchronizedMap(new WeakHashMap<>());

    /** Formation parameters read off a unit, or null when it isn't formed up (no frozen axis). */
    @Nullable
    static Layout layoutFor(PmcUnitEntity pmc) {
        if (pmc.getFormationIndex() < 0) return null;
        Optional<Layout> cached = LAYOUTS.get(pmc);
        if (cached == null) {
            cached = Optional.ofNullable(decode(pmc));
            LAYOUTS.put(pmc, cached);
            cached.ifPresent(l -> {
                if (l.group() != 0L && !pmc.level().isClientSide) {
                    FormationReform.track(l.group(), pmc, l.anchorMode() == FormationAnchorMode.UNIT);
                }
            });
        }
        return cached.orElse(null);
    }

    @Nullable
    private static Layout decode(PmcUnitEntity pmc) {
        IFormationMember member = (IFormationMember) pmc;
        Direction axis = member.sewv$getFormationDirection();
        if (axis == null) return null;
        int rowSize = member.sewv$getFormationRowSize();
        if (rowSize < 1) rowSize = DEFAULT_ROW_SIZE;
        int kindId = member.sewv$getFormationKind();
        FormationComposition.Kind kind = kindId > 0 ? FormationComposition.Kind.byOrdinal(kindId - 1)
                : FormationComposition.classify(pmc);
        double spacing = member.sewv$getFormationSpacing();
        if (spacing <= 0.0) {
            spacing = kind == null ? FormationComposition.SPACING_VEHICLE : FormationComposition.baselineSpacing(kind);
        }
        FormationAnchorMode mode = FormationAnchorMode.byId(member.sewv$getFormationAnchorMode());
        UUID leader = member.sewv$getFormationLeader();
        // A UNIT follower that lost its leader tag has nothing to stand on: it is not in a formation at all.
        if (mode == FormationAnchorMode.UNIT && leader == null) return null;
        return new Layout(axis, member.sewv$getFormationYaw(), FormationShape.byId(member.sewv$getFormationShape()),
                rowSize, member.sewv$getFormationWidth(), member.sewv$getFormationLength(),
                mode, member.sewv$getFormationAnchorPos(), spacing, kind, member.sewv$getFormationGroup(), leader);
    }

    /** {@code leader} is set for a UNIT formation only. */
    record Layout(Direction axis, float yaw, FormationShape shape, int rowSize, float width, float length,
                  FormationAnchorMode anchorMode, @Nullable Vec3 frozenAnchor, double spacing,
                  @Nullable FormationComposition.Kind kind, long group, @Nullable UUID leader) {}

    /**
     * Slot {@code index}'s centre for {@code layout} around {@code anchor}. A cardinal formation goes through
     * {@link #slotCenter} so it lands exactly where it always has; a free heading builds its frame from the yaw.
     */
    static Vec3 slotCenter(Layout layout, Vec3 anchor, int index) {
        if (!Float.isFinite(layout.yaw())) {
            return slotCenter(anchor, layout.axis(), layout.shape(), index, layout.rowSize(), layout.spacing(),
                    layout.width(), layout.length());
        }
        Vec3 f = forwardOfYaw(layout.yaw());
        FormationSpec spec = new FormationSpec(layout.shape(), layout.spacing(), layout.width(), layout.length(),
                layout.rowSize(), FormationSpec.Placement.TRAIL);
        return FormationGeometry.slot(FormationFrame.facing(anchor.x, anchor.y, anchor.z, f.x, f.z), spec, index,
                index + 1);
    }

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
        Vec3 anchor = resolveAnchor(pmc, commanderPos);
        return anchor == null ? null : slotCenter(l, anchor, pmc.getFormationIndex());
    }

    /** Hull-scale equivalent of {@link #formationSlotCenter}, terrain/water-projected. */
    @Nullable
    public static BlockPos formationSlotPos(Level level, PmcUnitEntity pmc, Vec3 commanderPos) {
        Layout l = layoutFor(pmc);
        if (l == null) return null;
        Vec3 anchor = resolveAnchor(pmc, commanderPos);
        if (anchor == null) return null;
        Vec3 center = slotCenter(l, anchor, pmc.getFormationIndex());
        return GroundSnap.snap(level, center, anchor.y, l.kind() == FormationComposition.Kind.SHIP);
    }

    /** Drops every formation tag — DISMISS / Quick Wheel CANCEL, or a fresh non-formation order. */
    public static void clear(PmcUnitEntity pmc) {
        var data = pmc.getPersistentData();
        long group = data.getLong(IFormationMember.TAG_FORMATION_GROUP);
        if (group != 0L && !pmc.level().isClientSide) FormationReform.departed(group, pmc.getId());
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
        data.remove(IFormationMember.TAG_FORMATION_SPACING);
        data.remove(IFormationMember.TAG_FORMATION_KIND);
        data.remove(IFormationMember.TAG_FORMATION_GROUP);
        data.remove(IFormationMember.TAG_FORMATION_YAW);
        data.remove(IFormationMember.TAG_FORMATION_LEADER);
        data.remove(IFormationMember.TAG_FORMATION_HOLD_FIRE);
        // TAG_FORMATION_LEADS is deliberately kept: SEM's setFormationIndex wipe lands here for every order a
        // leader takes, and leading survives every order. Only FormationSubjects.release/dissolve end it.
        LAYOUTS.remove(pmc);
    }
}
