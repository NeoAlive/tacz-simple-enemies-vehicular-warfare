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
     */
    public static int assign(Player commander, List<PmcUnitEntity> units, FormationShape shape,
                             Direction axis, int rowSize, float widthStretch, float lengthStretch,
                             FormationComposition.Kind kind) {
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

        int slot = 0;
        Map<Entity, Integer> hullSlots = new HashMap<>();
        for (PmcUnitEntity driver : drivers) {
            hullSlots.put(driver.getVehicle(), slot);
            apply(driver, order, axis, slot++, shape, rowSize, width, length);
        }
        for (PmcUnitEntity infantry : loose) {
            apply(infantry, order, axis, slot++, shape, rowSize, width, length);
        }

        for (PmcUnitEntity rider : riders) {
            Integer hullSlot = hullSlots.get(rider.getVehicle());
            if (hullSlot != null) apply(rider, order, axis, hullSlot, shape, rowSize, width, length);
        }
        // Infantry-only: report how many formed; otherwise hull count (legacy feedback keys).
        return kind == FormationComposition.Kind.INFANTRY ? loose.size() : hullSlots.size();
    }

    private static void apply(PmcUnitEntity pmc, OrderType order, Direction axis, int slot,
                              FormationShape shape, int rowSize, float width, float length) {
        PatrolSupport.clearSweepMembership(pmc, "VehicleFormation.dismiss");
        pmc.releaseMovementLock();
        pmc.setFormationIndex(slot);
        IFormationMember member = (IFormationMember) pmc;
        member.sewv$setFormationDirection(axis);
        member.sewv$setFormationShape(shape.id());
        member.sewv$setFormationRowSize(rowSize);
        member.sewv$setFormationWidth(width);
        member.sewv$setFormationLength(length);
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
}
