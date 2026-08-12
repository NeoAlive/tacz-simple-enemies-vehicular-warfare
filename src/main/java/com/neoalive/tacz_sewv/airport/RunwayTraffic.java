package com.neoalive.tacz_sewv.airport;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.entity.ai.goal.DrivePlaneGoal;
import com.neoalive.tacz_sewv.entity.ai.plane.PlaneNav;

/**
 * Who is standing where on a runway, and which slot an arrival may have.
 *
 * <p>Occupancy is <b>read off the world, not kept in a table</b>. A slot is taken if something is
 * parked in it or if an aircraft on its way there says so, and both of those answers die with the
 * entity — so a hull that is destroyed, flown away or unloaded releases its slot with no bookkeeping
 * and no lifecycle hook to forget. This is the same self-healing shape a mortar claim uses, and the
 * reason is the same: the alternative is a reservation table that leaks a slot every time something
 * disappears in a way nobody anticipated, and a runway that slowly fills up with ghosts.
 *
 * <p>The written reservation exists only to stop two aircraft landing in the same minute from
 * choosing the same empty slot; the parked-hull test is what actually keeps them apart afterwards.
 */
public final class RunwayTraffic {

    /** Index of the slot this hull holds. */
    public static final String TAG_SLOT = "sewv:runway_slot";
    /** Which runway that index belongs to — slot 2 of one strip is not slot 2 of another. */
    public static final String TAG_RUNWAY = "sewv:runway_key";

    private RunwayTraffic() {}

    /**
     * The first slot that is neither occupied nor spoken for, written onto {@code claimant}.
     *
     * @return the slot index, or -1 when the runway is full (or has no slots at all)
     */
    public static int claim(Level level, RunwaySlots slots, VehicleEntity claimant) {
        int held = heldSlot(claimant, slots);
        if (held >= 0) return held;

        List<VehicleEntity> present = level.getEntitiesOfClass(VehicleEntity.class, slots.area(),
                v -> v != claimant && v.isAlive());
        for (RunwaySlots.Slot slot : slots.slots()) {
            if (isTaken(slot, slots, present)) continue;
            CompoundTag tag = claimant.getPersistentData();
            tag.putInt(TAG_SLOT, slot.index());
            tag.putLong(TAG_RUNWAY, slots.threshold().asLong());
            return slot.index();
        }
        return -1;
    }

    private static boolean isTaken(RunwaySlots.Slot slot, RunwaySlots slots,
                                   List<VehicleEntity> present) {
        for (VehicleEntity other : present) {
            if (slot.bounds().contains(other.position())) return true;
            if (heldSlot(other, slots) == slot.index()) return true;
        }
        return false;
    }

    /** The slot this hull already holds on this runway, or -1. */
    public static int heldSlot(VehicleEntity v, RunwaySlots slots) {
        CompoundTag tag = v.getPersistentData();
        if (!tag.contains(TAG_SLOT)) return -1;
        if (tag.getLong(TAG_RUNWAY) != slots.threshold().asLong()) return -1;
        int index = tag.getInt(TAG_SLOT);
        return index < slots.capacity() ? index : -1;
    }

    /**
     * Stand every aircraft already on the strip in a slot of its own.
     *
     * <p>Run when a runway is cleared, which is the one moment the segmentation changes under
     * aircraft that are already there: whatever they were parked on a second ago is now a
     * different set of slots, or the takeoff run, and the whole point of the slots is that a
     * landing aircraft can be sent to one knowing it is empty. Marshalling them is the cheap
     * answer — the alternative is a landing that touches down past the parking and taxis into a
     * hull that never agreed to be anywhere in particular.
     *
     * <p>Only aircraft <b>on the ground</b> are moved. The strip's box has height, so one flying
     * over the numbers at the moment the owner presses the button is inside it, and yanking that
     * one out of the air would be a spectacular way to lose an aeroplane.
     *
     * @return how many were parked
     */
    public static int marshal(Level level, RunwaySlots slots) {
        List<VehicleEntity> planes = new ArrayList<>(level.getEntitiesOfClass(
                VehicleEntity.class, slots.area(),
                v -> v.isAlive() && v.onGround() && HullFacts.isPlaneHull(v)));
        if (planes.isEmpty()) return 0;

        // Nearest the threshold first, so the order they are standing in is the order they are
        // parked in and nothing is dragged past anything else.
        BlockPos t = slots.threshold();
        planes.sort(Comparator.comparingDouble(v -> v.distanceToSqr(t.getX() + 0.5, v.getY(),
                t.getZ() + 0.5)));

        float yaw = PlaneNav.yawFromBearingDeg(slots.headingDeg());
        int parked = 0;
        for (VehicleEntity plane : planes) {
            int index = claim(level, slots, plane);
            RunwaySlots.Slot slot = index < 0 ? null : slots.slot(index);
            if (slot == null) continue;
            plane.setDeltaMovement(Vec3.ZERO);
            plane.moveTo(slot.center().getX() + 0.5, slot.center().getY(),
                    slot.center().getZ() + 0.5, yaw, 0.0F);
            plane.setOldPosAndRot();
            DrivePlaneGoal.parkAt(plane, plane.getX(), plane.getY(), plane.getZ(), yaw);
            parked++;
        }
        return parked;
    }

    /** Give the slot back. Called when the aircraft leaves — a departure, not a parking move. */
    public static void release(VehicleEntity v) {
        CompoundTag tag = v.getPersistentData();
        tag.remove(TAG_SLOT);
        tag.remove(TAG_RUNWAY);
    }
}
