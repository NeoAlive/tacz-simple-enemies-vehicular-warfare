package com.neoalive.tacz_sewv.entity.ai.support;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.entity.LivingEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.bridge.IFireHesitation;
import com.neoalive.tacz_sewv.compat.FcpMortarCompat;
import com.neoalive.tacz_sewv.config.EasyMode;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;

/**
 * A crew that has just acquired a target pauses before its first shot. Runs inside the canShoot
 * verdict ({@code MixinVehicleFireCooldown}), so the steady state is one int and one long compare;
 * config, hull class and the delay are only read when the target changes.
 *
 * <p>State is per SHOOTER, not per hull: a turret and an MG on one hull engaging different targets
 * would otherwise keep resetting each other's clock. Aircraft (their own fire windows / seeker
 * lock) and indirect-fire hulls (they lay before firing) are exempt. The per-crew spread is a
 * hash of the shooter's entity id, so client, server and repeat runs agree.
 */
public final class FireHesitation {

    private FireHesitation() {}

    /** True while {@code shooter} is still hesitating on {@code target}. */
    public static boolean denies(AbstractUnit shooter, VehicleEntity hull, LivingEntity target, long now) {
        IFireHesitation h = (IFireHesitation) shooter;
        if (target.getId() != h.sewv$getHesTargetId()) {
            h.sewv$setHesTargetId(target.getId());
            h.sewv$setHesReadyAt(now + delayFor(shooter, hull));
        }
        return now < h.sewv$getHesReadyAt();
    }

    /** Target lost: the next acquisition hesitates again, even if it is the same entity. */
    public static void clear(AbstractUnit shooter) {
        ((IFireHesitation) shooter).sewv$setHesTargetId(-1);
    }

    private static int delayFor(AbstractUnit shooter, VehicleEntity hull) {
        int base;
        double jitter;
        try {
            base = EasyMode.aiFireHesitationTicks();
            jitter = SewvConfig.AI_FIRE_HESITATION_JITTER.get();
        } catch (Throwable ignored) {
            return 0; // unbaked config (client before sync): never block on it
        }
        if (base <= 0) return 0;
        EngineType type = HullFacts.engineType(hull);
        if (type == EngineType.HELICOPTER || type == EngineType.AIRCRAFT || type == EngineType.AIRSHIP) return 0;
        if (HullFacts.isArtilleryHull(hull) || FcpMortarCompat.isMortarHull(hull)) return 0;
        return base + (int) Math.round(base * jitter * spread(shooter.getId()));
    }

    /** Deterministic [0,1) from an entity id (Fibonacci hash, top 16 bits). */
    static double spread(int id) {
        return ((id * 0x9E3779B9) >>> 16) / 65536.0;
    }
}
