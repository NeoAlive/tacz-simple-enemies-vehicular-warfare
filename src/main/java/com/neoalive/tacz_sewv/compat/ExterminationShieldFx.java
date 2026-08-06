package com.neoalive.tacz_sewv.compat;

import com.neoalive.tacz_sewv.config.SewvConfig;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

/**
 * Cosmetic pod shield flare — fully decoupled from damage cancel.
 *
 * <p>Projects an impact estimate onto a prolate Y-axis spheroid matching the pod hitbox
 * (tripod 3.5×24 → 1.75/12; uber/emperor 5×30 → 2.5/15), then emits a particle patch at that
 * surface point. Debug always-on reuses {@link #emitAtSurface}.
 */
public final class ExterminationShieldFx {

    /** Debounce Attack+Hurt (or gun Pre + hurt) double-emitting on the same tick. */
    private static int lastFlareEntityId = -1;
    private static long lastFlareGameTime = Long.MIN_VALUE;

    private static final Vector3f FLARE_COLOR = new Vector3f(0.55f, 0.85f, 1.0f);
    /** ~4× a default dust particle (scale 1). */
    private static final float DUST_SCALE = 4.0f;

    private ExterminationShieldFx() {}

    /** After a blocked ranged hit — best-effort only. */
    public static void onBlocked(LivingEntity pod, DamageSource source) {
        onBlockedAt(pod, estimateImpact(pod, source));
    }

    /** Same path with an explicit world hit (e.g. TACZ bullet position on the AABB). */
    public static void onBlockedAt(LivingEntity pod, @Nullable Vec3 worldHit) {
        if (!(pod.level() instanceof ServerLevel level)) return;
        Vec3 impact = worldHit != null ? worldHit : spheroidCenter(pod).add(0.0, 0.0, semiXz(pod));
        Vec3 surface = projectToSpheroid(pod, impact);
        emitAtSurface(level, pod, surface);
    }

    /** Same emitter as blocks; fixed local impact on the +Z equator for tuning. */
    public static void emitDebug(LivingEntity pod) {
        if (!(pod.level() instanceof ServerLevel level)) return;
        Vec3 center = spheroidCenter(pod);
        Vec3 impact = center.add(0.0, 0.0, semiXz(pod));
        Vec3 surface = projectToSpheroid(pod, impact);
        emitAtSurface(level, pod, surface);
    }

    public static Vec3 spheroidCenter(LivingEntity pod) {
        return new Vec3(pod.getX(), pod.getY() + semiY(pod), pod.getZ());
    }

    /** Half-width from the live hitbox (covers tripod 3.5 and uber/emperor 5.0). */
    public static double semiXz(LivingEntity pod) {
        return pod.getBbWidth() * 0.5 * SewvConfig.TRIPOD_SHIELD_AXIS_SCALE.get();
    }

    /** Half-height from the live hitbox (covers tripod 24 and uber/emperor 30). */
    public static double semiY(LivingEntity pod) {
        return pod.getBbHeight() * 0.5 * SewvConfig.TRIPOD_SHIELD_AXIS_SCALE.get();
    }

    /**
     * Affine map to unit sphere, normalize, map back — closest surface point along the ray from
     * center through {@code worldPoint} (or a fallback direction if coincident with center).
     */
    public static Vec3 projectToSpheroid(LivingEntity pod, Vec3 worldPoint) {
        Vec3 center = spheroidCenter(pod);
        double a = semiXz(pod);
        double c = semiY(pod);
        Vec3 d = worldPoint.subtract(center);
        double sx = d.x / a;
        double sy = d.y / c;
        double sz = d.z / a;
        double lenSq = sx * sx + sy * sy + sz * sz;
        if (lenSq < 1.0e-8) {
            sx = 0.0;
            sy = 0.0;
            sz = 1.0;
            lenSq = 1.0;
        }
        double inv = 1.0 / Math.sqrt(lenSq);
        return center.add(sx * inv * a, sy * inv * c, sz * inv * a);
    }

    /**
     * Prefer the projectile's contact on the hitbox over {@link DamageSource#getSourcePosition()},
     * which is often the shooter and would only give a facing-side equator point.
     */
    static Vec3 estimateImpact(LivingEntity pod, DamageSource source) {
        Entity direct = source.getDirectEntity();
        if (direct != null && !(direct instanceof LivingEntity)) {
            return ExterminationCompat.closestOnAabb(pod.getBoundingBox(), direct.position());
        }

        Vec3 fromSource = source.getSourcePosition();
        if (fromSource != null) {
            return ExterminationCompat.closestOnAabb(pod.getBoundingBox(), fromSource);
        }

        Entity causing = source.getEntity();
        if (causing instanceof LivingEntity living) {
            return ExterminationCompat.closestOnAabb(pod.getBoundingBox(), living.getEyePosition());
        }
        if (causing != null) {
            return ExterminationCompat.closestOnAabb(pod.getBoundingBox(), causing.position());
        }

        Vec3 center = spheroidCenter(pod);
        Vec3 look = Vec3.directionFromRotation(0.0f, pod.getYRot());
        return center.add(look.x * semiXz(pod), 0.0, look.z * semiXz(pod));
    }

    /**
     * Hit patch at the surface — dust at ~4× default scale plus a local spark crackle.
     * {@link SewvConfig#TRIPOD_SHIELD_FLARE_TICKS} only scales count slightly.
     */
    static void emitAtSurface(ServerLevel level, LivingEntity pod, Vec3 surface) {
        long gameTime = level.getGameTime();
        int id = pod.getId();
        if (id == lastFlareEntityId && gameTime == lastFlareGameTime) return;
        lastFlareEntityId = id;
        lastFlareGameTime = gameTime;

        Vec3 center = spheroidCenter(pod);
        Vec3 normal = surface.subtract(center);
        double nLen = normal.length();
        if (nLen > 1.0e-6) {
            normal = normal.scale(1.0 / nLen);
        } else {
            normal = new Vec3(0.0, 0.0, 1.0);
        }

        int ticks = Math.max(1, SewvConfig.TRIPOD_SHIELD_FLARE_TICKS.get());
        int sparks = Math.min(10, 4 + ticks / 2);
        double x = surface.x;
        double y = surface.y;
        double z = surface.z;
        // Was 0.05; ~4× for a readable patch next to the larger dust.
        double spread = 0.20;

        level.sendParticles(
                new DustParticleOptions(FLARE_COLOR, DUST_SCALE),
                x,
                y,
                z,
                sparks,
                spread,
                spread,
                spread,
                0.0);
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, x, y, z, sparks, spread, spread, spread, 0.0);
        level.sendParticles(
                ParticleTypes.CRIT,
                x + normal.x * 0.20,
                y + normal.y * 0.20,
                z + normal.z * 0.20,
                Math.max(2, sparks / 2),
                spread * 0.5,
                spread * 0.5,
                spread * 0.5,
                0.02);
        level.sendParticles(
                ParticleTypes.END_ROD,
                x + normal.x * 0.28,
                y + normal.y * 0.28,
                z + normal.z * 0.28,
                3,
                0.08,
                0.08,
                0.08,
                0.01);
    }
}
