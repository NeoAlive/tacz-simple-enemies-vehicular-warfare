package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.bridge.IFormationMember;
import com.neoalive.tacz_sewv.config.SewvConfig;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The shape of a vehicle formation and who stands where in it.
 *
 * <p>The geometry is SEM's own ({@code FormationUtils}), scaled up: a column files directly
 * astern, a wedge puts a point man on the axis and fans the rest to alternating flanks a rank
 * back each time. What changes for hulls is the basis and the scale. SEM lays out against the
 * commander's live yaw, which spins the whole formation when the player looks around — tolerable
 * at 2 blocks, hopeless at 12 — so a hull formation is laid out against a cardinal the player
 * designates once ({@link IFormationMember}).
 *
 * <p>The math and the slot assignment live together deliberately: {@link #assign} and
 * {@link VehicleTargeting#resolveDestination} have to agree on the geometry, and letting them
 * drift apart would put a hull's assigned slot somewhere other than where it drives.
 * {@link VehicleTargeting}'s job stays the narrower one — which order, and where does it point?
 *
 * <p>Server-side only. Nothing here is safe to call from the client (see {@link #groundY}).
 */
public final class VehicleFormation {

    private VehicleFormation() {}

    /**
     * SEM's wedge widens 1.25x as fast as it deepens (FormationUtils: 2.5 lateral against 2.0
     * back). Keeping it as a ratio rather than a second spacing is what makes one config value
     * scale the whole shape without changing its proportions.
     */
    private static final double LATERAL_RATIO = 2.5 / 2.0;

    /** A terrain probe further than this from the commander's own level is not believable. */
    private static final int MAX_SLOT_RISE = 16;

    /** The heading the formation points along. Cardinal step vectors are already unit length. */
    public static Vec3 forward(Direction axis) {
        return new Vec3(axis.getStepX(), 0.0, axis.getStepZ());
    }

    /**
     * Where slot {@code index} sits, relative to {@code anchor}, for a formation pointing along
     * {@code axis}. Reproduces FormationUtils exactly at a spacing of 2.0 — same wedge, bigger.
     *
     * <p>Note the sign: every slot lands at {@code anchor + forward * -back}, so the formation
     * trails BEHIND the anchor. "Wedge on +X" means face east and the wedge forms behind you,
     * pointing east.
     */
    public static Vec3 slotCenter(Vec3 anchor, Direction axis, OrderType order, int index) {
        double spacing = SewvConfig.VEHICLE_FORMATION_SPACING.get();
        Vec3 forward = forward(axis);
        Vec3 perp = new Vec3(-forward.z, 0.0, forward.x); // the same perpendicular SEM builds

        double back;
        double lateral = 0.0;
        if (order == OrderType.FORM_COLUMN) {
            back = spacing * (1 + index);
        } else if (index == 0) {
            back = spacing; // point man, centred on the axis
        } else {
            int rank = (index - 1) / 2 + 1;
            back = spacing * (1 + rank);
            lateral = (index % 2 != 0 ? -1 : 1) * spacing * LATERAL_RATIO * rank; // SEM's signs
        }
        return anchor.add(forward.scale(-back)).add(perp.scale(lateral));
    }

    /** Drive-to point for a slot: its centre, dropped onto the terrain underneath it. */
    public static BlockPos slotPos(Level level, Vec3 anchor, Direction axis, OrderType order, int index) {
        Vec3 center = slotCenter(anchor, axis, order, index);
        return BlockPos.containing(center.x, groundY(level, center.x, center.z, anchor.y), center.z);
    }

    /**
     * The surface a hull would come to rest on. NO_LEAVES because a canopy is not ground. It
     * counts fluids, so a slot over water resolves to the water surface — harmless, since
     * GroundVehicleNodeEvaluator rejects that node anyway and the hull holds short of it.
     *
     * <p>The clamp is not cosmetic. Level.getHeight answers getMinBuildHeight() for an UNLOADED
     * chunk, so a slot behind a commander standing at the edge of loaded terrain would resolve to
     * bedrock — a destination hundreds of blocks down that the hull can never arrive at. It is
     * also the right answer inside a cave or a building, where the probe reports the roof.
     * Falling back to the commander's own Y is what this did before there was a probe at all, and
     * it is the right answer whenever the probe is not.
     *
     * <p>SERVER ONLY: ClientLevel chunks carry only MOTION_BLOCKING and WORLD_SURFACE, so this
     * must never move client-side.
     */
    private static double groundY(Level level, double x, double z, double anchorY) {
        int probed = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(x), Mth.floor(z));
        return Math.abs(probed - anchorY) > MAX_SLOT_RISE ? anchorY : probed;
    }

    /**
     * Number a selection into formation slots and issue the order. Returns the count of HULLS
     * formed, which is what the player is told.
     *
     * <p>One hull takes one slot, held by its driver, because only the driver's DriveVehicleGoal
     * steers — SEM's per-unit numbering would spend three slots on a three-man tank and then fly
     * the hull to whichever of them the descending-id sort happened to hand the driver. Loose
     * infantry continue the same numbering rather than getting a sequence of their own, so a
     * mixed selection forms one coherent shape (MixinCommanderOrderGoal is what walks them to
     * vehicle-spaced slots instead of SEM's 2-block ones).
     */
    public static int assign(Player commander, List<PmcUnitEntity> units, OrderType order, Direction axis) {
        List<PmcUnitEntity> drivers = new ArrayList<>();
        List<PmcUnitEntity> riders = new ArrayList<>();
        List<PmcUnitEntity> loose = new ArrayList<>();

        for (PmcUnitEntity pmc : units) {
            Entity hull = pmc.getVehicle();
            if (hull instanceof VehicleEntity) {
                if (hull.getFirstPassenger() == pmc) drivers.add(pmc); else riders.add(pmc);
            } else {
                // A mortar crew has no seat to ride, so it lands here by construction — which is
                // right: it is infantry, working a tube.
                loose.add(pmc);
            }
        }

        // Nearest-first, so the formation builds with the least driving and the closest hull
        // leads. Ties break on UUID, never entity id: ids are session-scoped (see CLAUDE.md) and
        // the slot they would pick gets persisted to NBT.
        Comparator<PmcUnitEntity> byRange = Comparator
                .comparingDouble((PmcUnitEntity u) -> u.distanceToSqr(commander))
                .thenComparing(PmcUnitEntity::getUUID);
        drivers.sort(byRange);
        loose.sort(byRange);

        int slot = 0;
        Map<Entity, Integer> hullSlots = new HashMap<>();
        for (PmcUnitEntity driver : drivers) {
            hullSlots.put(driver.getVehicle(), slot);
            apply(driver, order, axis, slot++);
        }
        for (PmcUnitEntity infantry : loose) {
            apply(infantry, order, axis, slot++);
        }

        // Passengers inherit their driver's slot. Beyond keeping one hull to one slot, this is
        // what makes a driver dying mid-formation a non-event: the next passenger down becomes
        // getFirstPassenger(), its own DriveVehicleGoal takes over, and it already holds the
        // hull's place. A rider whose driver was not selected is skipped — that hull is not ours
        // to move.
        for (PmcUnitEntity rider : riders) {
            Integer hullSlot = hullSlots.get(rider.getVehicle());
            if (hullSlot != null) apply(rider, order, axis, hullSlot);
        }
        return hullSlots.size();
    }

    /**
     * Issue one unit its slot. Mirrors SEM's own PacketIssueOrder handler
     * (releaseMovementLock, setFormationIndex, resetCommanderGoalCooldown, setOrder) with the
     * axis write slotted in.
     *
     * <p>The axis MUST be written after the index: MixinPmcUnitEntity clears the axis on every
     * live setFormationIndex, which is what stops a stale one from hijacking a plain SEM
     * infantry wedge.
     */
    private static void apply(PmcUnitEntity pmc, OrderType order, Direction axis, int slot) {
        pmc.releaseMovementLock();
        pmc.setFormationIndex(slot);
        ((IFormationMember) pmc).sewv$setFormationDirection(axis);
        pmc.resetCommanderGoalCooldown();
        pmc.setOrder(order);
    }
}
