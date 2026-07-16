package com.neoalive.tacz_sewv.bridge;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

/**
 * A unit's claim on a Superb Warfare mortar, read by
 * {@link com.neoalive.tacz_sewv.entity.ai.ManMortarGoal}. Set server-side by
 * {@link com.neoalive.tacz_sewv.network.PacketManMortar}; not synced to the client
 * (the goal runs server-side, same as the boarding flag on {@link IVehicleBoarder}).
 *
 * <p>The claim doubles as the mortar's occupancy record. A MortarEntity has no owner
 * or seats of its own, so there is nothing on the mortar to mark — instead
 * {@link com.neoalive.tacz_sewv.entity.ai.MortarSupport#isMortarClaimed} answers
 * "is this mortar taken?" by looking for a unit pointing at it. That keeps the claim
 * self-healing: a crew that dies or unloads stops pointing at its mortar and thereby
 * releases it, with no registry to keep in sync.
 *
 * <p>The claim is stored in transient mixin fields rather than persistent NBT (unlike
 * {@link IHelicopterPilot}) because it is an entity network id, which is not
 * stable across sessions — persisting it would resolve to an unrelated entity on
 * reload. A pending mortar order is simply dropped when the world reloads.
 *
 * <p>The <b>fire mission</b> below goes the other way, for the same reason read the other
 * way round: a BlockPos carries no id, so it survives a reload meaning exactly what it
 * meant when it was set. That is what makes it the right shape for a standing order to
 * shell a place — an RU battery told to shell your base must still be shelling it after a
 * restart, not quietly forget. Default methods are the whole implementation; unit mixins
 * only need {@code implements}.
 */
public interface IMortarCrew {

    /** No mortar claimed. */
    int NO_MORTAR = -1;

    String TAG_FIRE_MISSION = "tacz_sewv_mortar_fire_mission";
    String TAG_FIRE_MISSION_EXPIRY = "tacz_sewv_mortar_fire_mission_expiry";

    void sewv$setMortarTargetId(int id);

    int sewv$getMortarTargetId();

    /** Null clears the mission. */
    default void sewv$setFireMission(@Nullable FireMission mission) {
        CompoundTag tag = ((Entity) this).getPersistentData();
        if (mission == null) {
            tag.remove(TAG_FIRE_MISSION);
            tag.remove(TAG_FIRE_MISSION_EXPIRY);
            return;
        }
        tag.putLong(TAG_FIRE_MISSION, mission.pos().asLong());
        tag.putLong(TAG_FIRE_MISSION_EXPIRY, mission.expiresAt());
    }

    /**
     * A standing order to shell a fixed position, or null for none.
     *
     * <p>This exists because a mortar's reach and its crew's eyesight are barely in the
     * same units: the tube shoots ~770 blocks, SEM's FOLLOW_RANGE sees 96. A crew can
     * therefore never acquire anything at the range that makes a mortar a mortar. The
     * radio ({@link com.neoalive.tacz_sewv.entity.ai.FireMissionSupport}) solves that for
     * a live enemy; this solves it for a <em>place</em>, which is what artillery actually
     * shoots at and what an attacker with a map has.
     *
     * <p>A live target the crew can see still wins — see
     * {@link com.neoalive.tacz_sewv.entity.ai.ManMortarGoal}. This is the fallback the
     * crew works when nothing is in sight, not an override.
     *
     * <p>When the mission expires the crew leaves the tube for good ({@code ManMortarGoal}
     * releases the claim), so a deadline is a stand-down order, not a pause.
     */
    @Nullable
    default FireMission sewv$getFireMission() {
        CompoundTag tag = ((Entity) this).getPersistentData();
        if (!tag.contains(TAG_FIRE_MISSION)) return null;

        // A mission written before expiries existed, or one deliberately open-ended, has no
        // deadline tag and simply stands.
        long expiresAt = tag.contains(TAG_FIRE_MISSION_EXPIRY)
                ? tag.getLong(TAG_FIRE_MISSION_EXPIRY)
                : FireMission.NEVER;
        return new FireMission(BlockPos.of(tag.getLong(TAG_FIRE_MISSION)), expiresAt);
    }
}
