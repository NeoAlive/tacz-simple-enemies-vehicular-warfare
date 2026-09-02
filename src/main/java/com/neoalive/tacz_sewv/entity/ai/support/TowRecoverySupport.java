package com.neoalive.tacz_sewv.entity.ai.support;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.atsuishio.superbwarfare.config.server.VehicleConfig;
import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
import com.atsuishio.superbwarfare.entity.vehicle.Type63Entity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import com.neoalive.tacz_sewv.bridge.ITowRecovery;
import com.neoalive.tacz_sewv.compat.NpcVehicleOverrides;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.crew.CrewFacts;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleDriver;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.navigation.GroundMobility;

/**
 * Vehicle recovery towing: SBW towline physics without the item, AI steering, and hull flags for
 * autonomous RU/US response.
 */
public final class TowRecoverySupport {

    public static final String TAG_NEEDS_TOW = "sewv:needs_tow";
    public static final String TAG_TOW_REASON = "sewv:tow_reason";
    public static final String TAG_SUBMERGED_ACCUM = "sewv:tow_submerged_ticks";
    public static final String TAG_STUCK_CYCLES = "sewv:tow_stuck_cycles";
    public static final String TAG_HEALTHY_TICKS = "sewv:tow_healthy_ticks";
    public static final String TAG_STUCK_ANCHOR_X = "sewv:tow_stuck_anchor_x";
    public static final String TAG_STUCK_ANCHOR_Y = "sewv:tow_stuck_anchor_y";
    public static final String TAG_STUCK_ANCHOR_Z = "sewv:tow_stuck_anchor_z";

    public static final String REASON_SUBMERGED = "submerged";
    public static final String REASON_STUCK = "stuck";
    public static final String REASON_BEACHED = "beached";
    public static final String REASON_BANK_LIP = "bank_lip";
    public static final String REASON_ROLLED = "rolled";
    public static final String REASON_ENERGYLESS_WATER = "energyless_water";
    public static final String REASON_SUBMERGED_EXHAUSTED = "submerged_exhausted";

    private static final double FACING_DEADBAND_RAD = Math.toRadians(8.0);
    private static final double REVERSE_TOW_CONE_RAD = Math.toRadians(75.0);
    private static final int AUTO_RELEASE_HEALTHY_TICKS = 40;
    private static final int VICTIM_RECOVERED_TICKS = 40;
    private static final double RECOVERED_MOVE_SQ = 4.0;
    /** Chunk-unload / transient lookup grace before a tower drops its victim id. */
    private static final int VICTIM_GRACE_TICKS = 200;
    /** How long a tower holds still when pathing to the victim is pinned, before retrying. */
    private static final int APPROACH_STUCK_HOLD_TICKS = 40;
    private static final int GROUND_PROJECT_RADIUS = 24;
    private static final double ROLLED_PITCH_DEG = 45.0;

    private TowRecoverySupport() {}

    public static boolean hasTowOrder(AbstractUnit unit) {
        return unit instanceof ITowRecovery tow && tow.tacz_sewv$getTowVictimId() != -1;
    }

    public static boolean isTowering(AbstractUnit unit, VehicleEntity vehicle) {
        return hasTowOrder(unit)
                && vehicle != null
                && vehicle.getFirstPassenger() == unit;
    }

    public static void clearIfTowering(AbstractUnit unit) {
        if (!hasTowOrder(unit)) return;
        VehicleEntity vehicle = unit.getVehicle() instanceof VehicleEntity v ? v : null;
        clearOrder(unit, vehicle);
    }

    public static void clearOrder(AbstractUnit unit, @Nullable VehicleEntity vehicle) {
        if (unit instanceof ITowRecovery tow) {
            tow.tacz_sewv$setTowVictimId(-1);
            tow.tacz_sewv$setTowVictimGraceTicks(0);
        }
        if (vehicle != null && !vehicle.level().isClientSide) {
            vehicle.clearTowingInfo();
            vehicle.getPersistentData().remove(TAG_HEALTHY_TICKS);
        }
    }

    public static void assignVictim(AbstractUnit driver, int victimEntityId) {
        if (driver instanceof ITowRecovery tow) {
            tow.tacz_sewv$setTowVictimId(victimEntityId);
            tow.tacz_sewv$setTowVictimGraceTicks(0);
        }
    }

    // --- needs_tow detection (victim hull NBT) ---

    public static boolean needsTow(VehicleEntity hull) {
        return hull.getPersistentData().getBoolean(TAG_NEEDS_TOW);
    }

    public static void setNeedsTow(VehicleEntity hull, String reason) {
        CompoundTag tag = hull.getPersistentData();
        tag.putBoolean(TAG_NEEDS_TOW, true);
        tag.putString(TAG_TOW_REASON, reason);
        tag.putInt(TAG_HEALTHY_TICKS, 0);
    }

    public static void clearNeedsTow(VehicleEntity hull) {
        CompoundTag tag = hull.getPersistentData();
        tag.remove(TAG_NEEDS_TOW);
        tag.remove(TAG_TOW_REASON);
        tag.remove(TAG_SUBMERGED_ACCUM);
        tag.remove(TAG_STUCK_CYCLES);
        tag.remove(TAG_HEALTHY_TICKS);
        tag.remove(TAG_STUCK_ANCHOR_X);
        tag.remove(TAG_STUCK_ANCHOR_Y);
        tag.remove(TAG_STUCK_ANCHOR_Z);
    }

    /** Called from {@link VehicleDriver} when fully submerged with no dry cell in range. */
    public static void tickSubmergedStranded(VehicleEntity hull) {
        if (hull.level().isClientSide || !isTowVictimCandidate(hull)) return;
        if (GroundMobility.isAmphibious(hull)) return;
        CompoundTag tag = hull.getPersistentData();
        int accum = tag.getInt(TAG_SUBMERGED_ACCUM) + 1;
        tag.putInt(TAG_SUBMERGED_ACCUM, accum);
        int threshold = SewvConfig.TOW_SUBMERGED_REQUEST_TICKS.get();
        if (accum >= threshold && !tag.getBoolean(TAG_NEEDS_TOW)) {
            setNeedsTow(hull, REASON_SUBMERGED);
        }
    }

    /** Submerged failsafe found no escape cell — broadcast after a short dwell. */
    public static void onSubmergedFailsafeExhausted(VehicleEntity hull) {
        if (hull.level().isClientSide || !isTowVictimCandidate(hull)) return;
        if (GroundMobility.isAmphibious(hull) || !isFullySubmerged(hull)) return;
        CompoundTag tag = hull.getPersistentData();
        int accum = tag.getInt(TAG_SUBMERGED_ACCUM) + 1;
        tag.putInt(TAG_SUBMERGED_ACCUM, accum);
        int threshold = SewvConfig.TOW_SUBMERGED_REQUEST_TICKS.get();
        if (accum >= threshold && !tag.getBoolean(TAG_NEEDS_TOW)) {
            setNeedsTow(hull, REASON_SUBMERGED_EXHAUSTED);
        }
    }

    /** Submerged failsafe is running but the hull is not making progress toward dry land. */
    public static void onSubmergedNoProgress(VehicleEntity hull) {
        onSubmergedFailsafeExhausted(hull);
    }

    /**
     * Driver tick hook — stranded detection must not depend on {@link VehicleDriver#navigateTo}
     * having run. An idle submerged hull with no destination would otherwise never broadcast
     * {@code needs_tow}, and a hull near a bank that cannot actually reach it would have its
     * counter cleared every failsafe tick.
     */
    public static void tickDriverStrandedBroadcast(VehicleEntity hull) {
        if (hull.level().isClientSide || !isTowVictimCandidate(hull)) return;
        if (GroundMobility.isAmphibious(hull)) {
            if (!isFullySubmerged(hull)) {
                hull.getPersistentData().remove(TAG_SUBMERGED_ACCUM);
                tickVictimRecovered(hull);
            }
            return;
        }
        if (isRolled(hull) && !needsTow(hull)) {
            setNeedsTow(hull, REASON_ROLLED);
        }
        if (isEnergylessInWater(hull) && !needsTow(hull)) {
            setNeedsTow(hull, REASON_ENERGYLESS_WATER);
        }
        if (isFullySubmerged(hull)) {
            tickSubmergedStranded(hull);
        } else {
            hull.getPersistentData().remove(TAG_SUBMERGED_ACCUM);
            tickVictimRecovered(hull);
        }
    }

    public static void clearSubmergedAccum(VehicleEntity hull) {
        hull.getPersistentData().remove(TAG_SUBMERGED_ACCUM);
    }

    /** Called when ground stuck recovery arms a reverse unstick. */
    public static void onStuckThresholdReached(VehicleEntity hull) {
        if (hull.level().isClientSide || !isTowVictimCandidate(hull)) return;
        CompoundTag tag = hull.getPersistentData();
        Vec3 pos = hull.position();
        if (!tag.contains(TAG_STUCK_ANCHOR_X)) {
            tag.putDouble(TAG_STUCK_ANCHOR_X, pos.x);
            tag.putDouble(TAG_STUCK_ANCHOR_Y, pos.y);
            tag.putDouble(TAG_STUCK_ANCHOR_Z, pos.z);
        }
        int cycles = tag.getInt(TAG_STUCK_CYCLES) + 1;
        tag.putInt(TAG_STUCK_CYCLES, cycles);
        int required = SewvConfig.TOW_STUCK_REQUEST_CYCLES.get();
        if (cycles >= required && !tag.getBoolean(TAG_NEEDS_TOW)) {
            setNeedsTow(hull, REASON_STUCK);
        }
    }

    /** Bank-lip reverse recovery armed while the hull is still in water — cannot self-extract. */
    public static void onBankLipStuck(VehicleEntity hull) {
        if (hull.level().isClientSide || !isTowVictimCandidate(hull)) return;
        if (!hull.isInWater() && !isFullySubmerged(hull)) return;
        if (!tagOrFalse(hull, TAG_NEEDS_TOW)) {
            setNeedsTow(hull, REASON_BANK_LIP);
        }
    }

    /** Called from {@link com.neoalive.tacz_sewv.entity.ai.goal.DriveShipGoal} when beached and wedged. */
    public static void onBeachedStuck(VehicleEntity hull) {
        if (hull.level().isClientSide || !isTowVictimCandidate(hull)) return;
        if (!tagOrFalse(hull, TAG_NEEDS_TOW)) {
            setNeedsTow(hull, REASON_BEACHED);
        }
    }

    private static boolean tagOrFalse(VehicleEntity hull, String key) {
        return hull.getPersistentData().getBoolean(key);
    }

    /** Clear {@code needs_tow} when the hull has moved away from its stuck anchor or is no longer stranded. */
    public static void tickVictimRecovered(VehicleEntity hull) {
        if (hull.level().isClientSide || !needsTow(hull)) return;
        if (isStillStranded(hull)) {
            hull.getPersistentData().putInt(TAG_HEALTHY_TICKS, 0);
            return;
        }
        CompoundTag tag = hull.getPersistentData();
        int healthy = tag.getInt(TAG_HEALTHY_TICKS) + 1;
        tag.putInt(TAG_HEALTHY_TICKS, healthy);
        if (healthy >= VICTIM_RECOVERED_TICKS) {
            clearNeedsTow(hull);
        }
    }

    private static boolean isStillStranded(VehicleEntity hull) {
        String reason = hull.getPersistentData().getString(TAG_TOW_REASON);
        if (REASON_SUBMERGED.equals(reason) || REASON_SUBMERGED_EXHAUSTED.equals(reason)) {
            return isFullySubmerged(hull);
        }
        if (REASON_BEACHED.equals(reason)) {
            return hull.onGround() && !hull.isInWater() && !hull.isUnderWater();
        }
        if (REASON_BANK_LIP.equals(reason)) {
            return hull.isInWater() || isFullySubmerged(hull);
        }
        if (REASON_ROLLED.equals(reason)) {
            return isRolled(hull);
        }
        if (REASON_ENERGYLESS_WATER.equals(reason)) {
            return isEnergylessInWater(hull);
        }
        CompoundTag tag = hull.getPersistentData();
        if (!tag.contains(TAG_STUCK_ANCHOR_X)) return false;
        Vec3 anchor = new Vec3(
                tag.getDouble(TAG_STUCK_ANCHOR_X),
                tag.getDouble(TAG_STUCK_ANCHOR_Y),
                tag.getDouble(TAG_STUCK_ANCHOR_Z));
        return hull.position().distanceToSqr(anchor) < RECOVERED_MOVE_SQ;
    }

    private static boolean isRolled(VehicleEntity hull) {
        return Math.abs(Mth.wrapDegrees(hull.getXRot())) > ROLLED_PITCH_DEG
                && !hull.isInWater()
                && hull.onGround();
    }

    private static boolean isEnergylessInWater(VehicleEntity hull) {
        return hull.isInWater()
                && hull.getMaxEnergy() > 0
                && hull.getEnergy() <= 0;
    }

    private static boolean isFullySubmerged(VehicleEntity vehicle) {
        AABB box = vehicle.getBoundingBox();
        Level level = vehicle.level();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minX = Mth.floor(box.minX), maxX = Mth.floor(box.maxX);
        int minY = Mth.floor(box.minY), maxY = Mth.floor(box.maxY);
        int minZ = Mth.floor(box.minZ), maxZ = Mth.floor(box.maxZ);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (!level.getFluidState(pos.set(x, y, z)).is(FluidTags.WATER)) return false;
                }
            }
        }
        return true;
    }

    // --- victim resolution (tower) ---

    /**
     * Resolve the tower's assigned victim. Returns null while grace ticks remain for a missing
     * entity (chunk unload). Dead/wreck victims clear immediately.
     */
    @Nullable
    public static VehicleEntity resolveTowVictim(AbstractUnit tower, ITowRecovery tow) {
        int victimId = tow.tacz_sewv$getTowVictimId();
        if (victimId == -1) return null;

        Entity entity = tower.level().getEntity(victimId);
        if (entity instanceof VehicleEntity victim && victim.isAlive() && !victim.isWreck()) {
            tow.tacz_sewv$setTowVictimGraceTicks(0);
            return victim;
        }

        if (entity instanceof VehicleEntity dead && (!dead.isAlive() || dead.isWreck())) {
            return null;
        }

        int grace = tow.tacz_sewv$getTowVictimGraceTicks();
        if (grace < VICTIM_GRACE_TICKS) {
            tow.tacz_sewv$setTowVictimGraceTicks(grace + 1);
            return null;
        }
        return null;
    }

    /** True when the tower should keep its order despite a temporarily missing victim. */
    public static boolean towVictimGraceActive(ITowRecovery tow) {
        return tow.tacz_sewv$getTowVictimId() != -1
                && tow.tacz_sewv$getTowVictimGraceTicks() > 0
                && tow.tacz_sewv$getTowVictimGraceTicks() < VICTIM_GRACE_TICKS;
    }

    // --- link / unlink ---

    public static boolean tryLink(VehicleEntity tower, VehicleEntity victim) {
        if (tower.level().isClientSide) return false;
        if (tower == victim) return false;
        if (!tower.getTowedByUUID().isBlank()) return false;
        if (!victim.getTowedByUUID().isBlank()) return false;
        if (tower.isTowing(victim)) return true;

        double maxDist = VehicleConfig.TOW_MAX_DISTANCE.get();
        if (tower.distanceTo(victim) > maxDist) return false;
        if (!isTowVictimCandidate(victim)) return false;
        if (!isTowTowerCandidate(tower)) return false;

        List<String> list = new ArrayList<>(tower.getTowingUUIDs());
        list.add(victim.getUUID().toString());
        tower.setTowingUUIDs(list);
        victim.setTowedByUUID(tower.getUUID().toString());
        return true;
    }

    // --- tower steering ---

    public static void steerTower(AbstractUnit driver, VehicleEntity tower, VehicleEntity victim, VehicleDriver vehicleDriver) {
        double linkDist = VehicleConfig.TOW_MAX_DISTANCE.get();
        double dist = tower.distanceTo(victim);
        boolean linked = tower.isTowing(victim);

        if (!linked) {
            BlockPos approach = approachDestination(tower, victim);
            if (approach == null) {
                vehicleDriver.stop();
                return;
            }
            double approachDistSq = horizontalDistSq(tower, approach);
            if (dist > linkDist * 0.85) {
                vehicleDriver.navigateTo(approach, approachDistSq);
                tickApproachStuck(tower, vehicleDriver);
                return;
            }
            vehicleDriver.navigateTo(approach, approachDistSq);
            tryLink(tower, victim);
            linked = tower.isTowing(victim);
        }

        if (linked) {
            tower.getPersistentData().remove("sewv:tow_approach_stuck");
            steerPullAway(tower, victim, vehicleDriver);
        } else if (dist <= linkDist) {
            tickApproachStuck(tower, vehicleDriver);
        }
    }

    private static void tickApproachStuck(VehicleEntity tower, VehicleDriver vehicleDriver) {
        CompoundTag tag = tower.getPersistentData();
        if (vehicleDriver.isPinned()) {
            int stuck = tag.getInt("sewv:tow_approach_stuck") + 1;
            tag.putInt("sewv:tow_approach_stuck", stuck);
            if (stuck >= APPROACH_STUCK_HOLD_TICKS) {
                vehicleDriver.stop();
                tag.putInt("sewv:tow_approach_stuck", 0);
            }
        } else {
            tag.putInt("sewv:tow_approach_stuck", 0);
        }
    }

    @Nullable
    public static BlockPos approachDestination(VehicleEntity tower, VehicleEntity victim) {
        BlockPos target = victim.blockPosition();
        BlockPos direct = PatrolSupport.drivableColumn(tower.level(), target.getX(), target.getZ());
        if (direct != null) return direct;
        return projectToGround(tower.level(), target);
    }

    @Nullable
    private static BlockPos projectToGround(Level level, BlockPos target) {
        BlockPos best = null;
        long bestDistSq = Long.MAX_VALUE;
        for (int r = 1; r <= GROUND_PROJECT_RADIUS; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    BlockPos pos = PatrolSupport.drivableColumn(level, target.getX() + dx, target.getZ() + dz);
                    if (pos == null) continue;
                    long distSq = (long) dx * dx + (long) dz * dz;
                    if (distSq < bestDistSq) {
                        bestDistSq = distSq;
                        best = pos;
                    }
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    private static double horizontalDistSq(VehicleEntity vehicle, BlockPos pos) {
        double dx = pos.getX() + 0.5 - vehicle.getX();
        double dz = pos.getZ() + 0.5 - vehicle.getZ();
        return dx * dx + dz * dz;
    }

    private static void steerPullAway(VehicleEntity tower, Entity victim, VehicleDriver vehicleDriver) {
        Vec3 toVictim = victim.position().subtract(tower.position());
        toVictim = new Vec3(toVictim.x, 0, toVictim.z);
        if (toVictim.lengthSqr() < 1.0E-8) {
            vehicleDriver.stop();
            return;
        }
        Vec3 away = toVictim.normalize().scale(-1.0);
        Vector3f forward = tower.getForwardDirection().normalize();
        double angle = VehicleTargeting.signedAngleTo(forward, away);

        if (Math.abs(angle) > REVERSE_TOW_CONE_RAD) {
            tower.setForwardInputDown(false);
            tower.setBackInputDown(true);
            tower.setLeftInputDown(angle > 0);
            tower.setRightInputDown(angle < 0);
            return;
        }

        if (Math.abs(angle) < FACING_DEADBAND_RAD) {
            if (vehicleDriver.headingBlocked(away)) {
                tower.setForwardInputDown(false);
                tower.setBackInputDown(true);
                tower.setLeftInputDown(false);
                tower.setRightInputDown(false);
                return;
            }
            tower.setForwardInputDown(true);
            tower.setBackInputDown(false);
            tower.setLeftInputDown(false);
            tower.setRightInputDown(false);
        } else {
            tower.setForwardInputDown(false);
            tower.setBackInputDown(false);
            tower.setLeftInputDown(angle > 0);
            tower.setRightInputDown(angle < 0);
        }
    }

    /** Auto-release when victim has recovered for long enough and is no longer stranded. */
    public static void tickCompletion(AbstractUnit driver, VehicleEntity tower, VehicleEntity victim) {
        if (tower.level().isClientSide) return;
        tickVictimRecovered(victim);
        if (needsTow(victim) || isStillStranded(victim)) {
            tower.getPersistentData().putInt(TAG_HEALTHY_TICKS, 0);
            return;
        }
        CompoundTag tag = tower.getPersistentData();
        int healthy = tag.getInt(TAG_HEALTHY_TICKS) + 1;
        tag.putInt(TAG_HEALTHY_TICKS, healthy);
        if (healthy >= AUTO_RELEASE_HEALTHY_TICKS) {
            clearOrder(driver, tower);
        }
    }

    // --- RU/US scan ---

    public static VehicleEntity findNearestNeedsTow(AbstractUnit towerUnit, VehicleEntity tower) {
        double radius = Math.min(SewvConfig.TOW_AUTO_SCAN_RADIUS.get(), 128.0);
        List<VehicleEntity> candidates = tower.level().getEntitiesOfClass(
                VehicleEntity.class,
                tower.getBoundingBox().inflate(radius),
                hull -> hull != tower && needsTow(hull) && isFriendlyVictim(towerUnit, tower, hull));

        return candidates.stream()
                .min(Comparator.comparingDouble(tower::distanceToSqr))
                .orElse(null);
    }

    private static boolean isFriendlyVictim(AbstractUnit towerUnit, VehicleEntity tower, VehicleEntity victim) {
        if (!victim.getTowedByUUID().isBlank()) return false;
        CrewFacts.Faction towerFaction = CrewFacts.factionOf(tower);
        if (towerFaction != null) {
            CrewFacts.Faction victimFaction = CrewFacts.factionOf(victim);
            if (victimFaction != null) return victimFaction == towerFaction;
        }
        CrewFacts.Faction crewFaction = CrewFacts.factionOfCrew(towerUnit);
        if (crewFaction == null) return false;
        if (victim.getPassengers().isEmpty()) {
            LivingEntity last = lastDriverOf(victim);
            if (last instanceof Player && !SewvConfig.AUTO_BOARD_STEALS_PLAYER_VEHICLES.get()) {
                return false;
            }
            if (last instanceof RUunitEntity && crewFaction == CrewFacts.Faction.RU) return true;
            if (last instanceof USunitEntity && crewFaction == CrewFacts.Faction.US) return true;
            if (last instanceof PmcUnitEntity pmc && crewFaction == CrewFacts.Faction.PMC) {
                return towerUnit instanceof PmcUnitEntity driver
                        && pmc.getOwnerUUID() != null
                        && pmc.getOwnerUUID().equals(driver.getOwnerUUID());
            }
            return victim.getPassengers().isEmpty() && crewFaction != CrewFacts.Faction.PMC;
        }
        return CrewFacts.factionOf(victim) == crewFaction;
    }

    private static LivingEntity lastDriverOf(VehicleEntity hull) {
        try {
            return hull.getLastDriver() instanceof LivingEntity living ? living : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isTowTowerCandidate(VehicleEntity hull) {
        if (!hull.isAlive() || hull.isWreck()) return false;
        HullFacts facts = new HullFacts();
        facts.attach(hull);
        if (facts.isHelicopter() || facts.isPlane() || facts.isShip()) return false;
        if (hull instanceof MortarEntity || hull instanceof Type63Entity) return false;
        if (!hull.getTowedByUUID().isBlank()) return false;
        return true;
    }

    public static boolean isTowVictimCandidate(VehicleEntity hull) {
        if (!hull.isAlive() || hull.isWreck()) return false;
        if (hull instanceof MortarEntity || hull instanceof Type63Entity) return false;
        if (NpcVehicleOverrides.refusesNpcRiders(hull)) return false;
        HullFacts facts = new HullFacts();
        facts.attach(hull);
        if (facts.isHelicopter() || facts.isPlane()) return false;
        if (!hull.getTowedByUUID().isBlank()) return false;
        return true;
    }

    public static boolean isOwnedPmcHull(VehicleEntity hull, Player player) {
        java.util.UUID owner = CrewFacts.pmcOwner(hull);
        return owner != null && owner.equals(player.getUUID());
    }
}
