package com.neoalive.tacz_sewv.fob;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;

/**
 * Score-based threat evaluation inside the FOB master (buffer) AABB.
 */
public final class ThreatEvaluator {

    /** What a contact standing on the command post is worth against one at the buffer edge. */
    private static final double NEAR_WEIGHT = 2.5;
    /** Extra weight for a contact already shooting at the garrison. See {@link #urgency}. */
    private static final double ENGAGED_WEIGHT = 2.0;

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

        List<VehicleEntity> armor = level.getEntitiesOfClass(VehicleEntity.class, scan, v -> {
            if (!v.isAlive() || v.isWreck()) return false;
            if (!(v.getFirstPassenger() instanceof AbstractUnit passenger)) return false;
            return !VehicleTargeting.isNonHostile(perspective, passenger);
        });
        for (VehicleEntity vehicle : armor) {
            if (vehicle.getFirstPassenger() instanceof LivingEntity living && !hostiles.contains(living)) {
                hostiles.add(living);
            }
        }

        double score = 0;
        for (LivingEntity h : hostiles) {
            score += Math.ceil(h.getMaxHealth()) * urgency(fob, scan, h);
        }
        for (VehicleEntity hull : armor) {
            // Weighed by what the hull actually is, the same input infantry is weighed by. A flat
            // +20 per vehicle made one crewed tank (20 crew + 20 hull + 1) score 41 against a
            // threshold of 100 — the single most dangerous thing that can turn up outside a base
            // could not raise the alarm on its own. Scaled down by ten because SBW hull health is
            // on a completely different scale from a rifleman's.
            score += Math.max(20.0, Math.ceil(hull.getMaxHealth() / 10.0))
                    * urgency(fob, scan, hull);
        }
        score += hostiles.size();

        boolean wasScramble = fob.scrambleActive;
        int threshold = Math.max(1, SewvConfig.FOB_THREAT_THRESHOLD.get());
        fob.threatScore = (int) Math.round(score);
        if (fob.threatScore >= threshold) {
            fob.scrambleActive = true;
        } else if (fob.threatScore < threshold / 2) {
            // Plain hysteresis on the score. The old test also required the hostile list to be
            // empty, and an empty list scores zero — so the second half was unreachable and a
            // scramble only ever ended when the buffer was completely clear. Over a buffer this
            // wide that is close to "never".
            fob.scrambleActive = false;
        }

        if (fob.scrambleActive && !wasScramble) {
            playAlarm(level, fob, gameTime);
        }
    }

    /**
     * How much this contact's raw weight counts for. The old score was a flat headcount: a patrol
     * wandering past the far edge of the buffer read exactly the same as the same patrol at the
     * gate, so the alarm could not tell "something is out there" from "we are being attacked".
     *
     * <p>Two graded inputs, multiplied:
     * <ul>
     *   <li><b>Distance</b> — {@code NEAR_WEIGHT} at the command post falling to 1 at the buffer
     *       edge. Measured horizontally, because the buffer is a full-height column and a hostile
     *       forty blocks up a mountain is forty blocks away, not two hundred.</li>
     *   <li><b>Aggression</b> — {@code ENGAGED_WEIGHT} once it holds one of ours as a target.
     *       Something already shooting at the garrison is not a contact, it is an attack, and it
     *       should be able to trip the alarm well below the headcount that would otherwise be
     *       needed.</li>
     * </ul>
     */
    private static double urgency(FobInstance fob, AABB scan, Entity contact) {
        double half = Math.max(1.0, (scan.maxX - scan.minX) / 2.0);
        double dx = contact.getX() - (fob.commandPos.getX() + 0.5);
        double dz = contact.getZ() - (fob.commandPos.getZ() + 0.5);
        double closeness = Math.max(0.0, 1.0 - Math.sqrt(dx * dx + dz * dz) / half);
        double weight = 1.0 + (NEAR_WEIGHT - 1.0) * closeness;
        return engagingUs(contact, fob) ? weight * ENGAGED_WEIGHT : weight;
    }

    /** True when this contact — or its crew — is holding something of ours as a target. */
    private static boolean engagingUs(Entity contact, FobInstance fob) {
        LivingEntity actor = contact instanceof VehicleEntity hull
                ? (hull.getFirstPassenger() instanceof LivingEntity crew ? crew : null)
                : (contact instanceof LivingEntity living ? living : null);
        if (!(actor instanceof Mob mob)) return false;
        LivingEntity aim = mob.getTarget();
        if (aim == null) return false;
        if (aim.getUUID().equals(fob.owner)) return true;
        return aim instanceof PmcUnitEntity pmc && fob.owner.equals(pmc.getOwnerUUID());
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
