package com.neoalive.tacz_sewv.fob;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;

/**
 * Score-based threat evaluation inside the FOB master (buffer) AABB.
 */
public final class ThreatEvaluator {

    private ThreatEvaluator() {}

    public static void evaluate(ServerLevel level, FobInstance fob, long gameTime) {
        int interval = Math.max(1, SewvConfig.FOB_THREAT_EVAL_INTERVAL_TICKS.get());
        if (gameTime - fob.lastThreatEvalTime < interval) return;
        fob.lastThreatEvalTime = gameTime;

        AABB scan = fob.cachedBufferAabb != null ? fob.cachedBufferAabb : fob.cachedMasterAabb;
        if (scan == null) {
            FobSupport.refreshCachedAabbs(fob, level);
            scan = fob.cachedBufferAabb;
        }
        if (scan == null) return;

        AbstractUnit perspective = FobSupport.ownerPerspectiveAny(level, fob.owner);
        if (perspective == null) {
            fob.threatScore = 0;
            fob.scrambleActive = false;
            return;
        }

        List<LivingEntity> hostiles = new ArrayList<>();
        level.getEntitiesOfClass(AbstractUnit.class, scan, unit -> {
            if (!unit.isAlive() || unit == perspective) return false;
            return !VehicleTargeting.isNonHostile(perspective, unit);
        }).forEach(hostiles::add);

        int vehicleCount = 0;
        for (VehicleEntity vehicle : level.getEntitiesOfClass(VehicleEntity.class, scan, v -> {
            if (!v.isAlive() || v.isWreck()) return false;
            if (!(v.getFirstPassenger() instanceof AbstractUnit passenger)) return false;
            return !VehicleTargeting.isNonHostile(perspective, passenger);
        })) {
            vehicleCount++;
            if (vehicle.getFirstPassenger() instanceof LivingEntity living && !hostiles.contains(living)) {
                hostiles.add(living);
            }
        }

        int score = 0;
        for (LivingEntity h : hostiles) {
            score += (int) Math.ceil(h.getMaxHealth());
        }
        score += vehicleCount * 20;
        score += hostiles.size();

        boolean wasScramble = fob.scrambleActive;
        int threshold = Math.max(1, SewvConfig.FOB_THREAT_THRESHOLD.get());
        fob.threatScore = score;
        if (score >= threshold) {
            fob.scrambleActive = true;
        } else if (hostiles.isEmpty() && score < threshold / 2) {
            fob.scrambleActive = false;
        }

        if (fob.scrambleActive && !wasScramble) {
            playAlarm(level, fob, gameTime);
        }
    }

    @Nullable
    public static LivingEntity nearestHostile(ServerLevel level, FobInstance fob, BlockPos from) {
        AABB scan = fob.cachedBufferAabb != null ? fob.cachedBufferAabb : fob.cachedMasterAabb;
        if (scan == null) return null;
        AbstractUnit perspective = FobSupport.ownerPerspectiveAny(level, fob.owner);
        if (perspective == null) return null;

        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (AbstractUnit unit : level.getEntitiesOfClass(AbstractUnit.class, scan,
                u -> u.isAlive() && !VehicleTargeting.isNonHostile(perspective, u))) {
            double d = unit.blockPosition().distSqr(from);
            if (d < bestDist) {
                bestDist = d;
                best = unit;
            }
        }
        for (VehicleEntity vehicle : level.getEntitiesOfClass(VehicleEntity.class, scan, v -> {
            if (!v.isAlive() || v.isWreck()) return false;
            if (!(v.getFirstPassenger() instanceof AbstractUnit passenger)) return false;
            return !VehicleTargeting.isNonHostile(perspective, passenger);
        })) {
            double d = vehicle.blockPosition().distSqr(from);
            if (d < bestDist && vehicle.getFirstPassenger() instanceof LivingEntity living) {
                bestDist = d;
                best = living;
            }
        }
        return best;
    }

    public static void playAlarm(ServerLevel level, FobInstance fob, long gameTime) {
        long cooldown = Math.max(1, SewvConfig.FOB_ALARM_COOLDOWN_TICKS.get());
        if (gameTime - fob.lastAlarmTime < cooldown) return;
        fob.lastAlarmTime = gameTime;
        level.playSound(null, fob.commandPos, SoundEvents.BELL_BLOCK, net.minecraft.sounds.SoundSource.BLOCKS, 2.0f, 0.8f);
    }
}
