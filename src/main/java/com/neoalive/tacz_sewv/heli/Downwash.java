package com.neoalive.tacz_sewv.heli;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.neoalive.tacz_sewv.heli.physics.Airframe;
import com.neoalive.tacz_sewv.heli.physics.HeliState;

/**
 * Rotor downwash as a world effect (plan Phase 5), from the rotor model's own numbers.
 *
 * <p>Server: the wake leaves the disk at the far-field speed 2 v_i, v_i = sqrt(T / (2 rho A)), and
 * spreads along the ground as an outwash. Its speed at a point r from the rotor axis is taken as
 * u = 2 v_i (R / max(r, R)) exp(-h / (2R)), h the hub's height above the ground: radial spreading
 * beyond the disk edge, and a wake that has slowed and widened by the time it reaches ground far
 * below. Entities within 2R of the axis, on or near the ground and not riding anything, are eased
 * toward {@link #CARRY} of that velocity, capped at {@link #MAX_PUSH} (outward and slightly down),
 * at {@link #COUPLING} of the difference per tick, so a hovering hull clears the pad without
 * flinging anyone. Out of 3R of ground nothing
 * happens: that is where ground effect has also faded.
 *
 * <p>Client: dust kicked up in a ring under the hull, from the synced rotor speed alone (the client
 * has no airframe), using the hull's width as the disk radius.
 */
public final class Downwash {

    /** Fraction of the velocity difference applied per tick. */
    private static final double COUPLING = 0.15;
    /**
     * What an entity reaches in the wash, as a fraction of the wind speed, and its cap (blocks per
     * tick). A body is not carried at the wind's speed: at 20 m/s the drag on a standing human is
     * about 170 N, a stagger. ponytail: one fraction for every entity; per-type drag (items fly,
     * players brace) if it reads wrong in play.
     */
    private static final double CARRY = 0.25, MAX_PUSH = 0.3;
    /** Highest the effect reaches, in rotor radii of hub height. */
    private static final double REACH_RADII = 3.0;

    private Downwash() {}

    /** Server, once per flying tick. */
    static void push(VehicleEntity hull, Airframe af, HeliState s) {
        if (!(s.thrust > 0.0) || s.omega < 0.3 * af.omegaN) return;
        Level level = hull.level();
        double hubX = s.p.x, hubZ = s.p.z, hubY = s.p.y + af.cgHeight + af.hubHeight;
        int bx = (int) Math.floor(hubX), bz = (int) Math.floor(hubZ);
        if (!level.hasChunk(bx >> 4, bz >> 4)) return;
        double ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
        double h = hubY - ground, radius = af.radius;
        if (h > REACH_RADII * radius || h < 0.0) return;
        double vi = Math.sqrt(s.thrust / (2.0 * Airframe.RHO0 * af.area));
        double wake = 2.0 * vi * Math.exp(-h / (2.0 * radius));
        AABB box = new AABB(hubX - 2 * radius, ground - 1.0, hubZ - 2 * radius, hubX + 2 * radius, hubY, hubZ + 2 * radius);
        for (Entity e : level.getEntities(hull, box, Downwash::pushable)) {
            double dx = e.getX() - hubX, dz = e.getZ() - hubZ, r = Math.sqrt(dx * dx + dz * dz);
            if (r > 2.0 * radius) continue;
            double u = Math.min(MAX_PUSH, CARRY * wake * radius / Math.max(r, radius) / 20.0); // blocks per tick
            double ux = r > 1e-3 ? dx / r : 0.0, uz = r > 1e-3 ? dz / r : 0.0;
            Vec3 v = e.getDeltaMovement();
            double tx = ux * u, tz = uz * u, ty = -0.25 * u;
            e.setDeltaMovement(v.x + COUPLING * (tx - v.x), v.y + COUPLING * Math.min(0.0, ty - v.y), v.z + COUPLING * (tz - v.z));
            e.hasImpulse = true;
            if (e instanceof ServerPlayer p) p.hurtMarked = true; // players move client-side
        }
    }

    private static boolean pushable(Entity e) {
        if (e instanceof VehicleEntity || e.isPassenger() || e.isVehicle() || e.isSpectator() || e.noPhysics) return false;
        return !(e instanceof Player p && (p.isCreative() && p.getAbilities().flying));
    }

    /** Client, once per tick for an AI-flown hull: dust in a ring on the ground under the rotor. */
    static void dust(VehicleEntity hull) {
        float spin = hull.getSynchedPropellerRot() / 0.12F;
        if (spin < 0.5F) return;
        Level level = hull.level();
        double radius = Math.max(2.0, hull.getBbWidth());
        int bx = hull.getBlockX(), bz = hull.getBlockZ();
        double ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
        double h = hull.getY() - ground;
        if (h > REACH_RADII * radius || h < 0.0) return;
        double strength = spin * (1.0 - h / (REACH_RADII * radius));
        var rng = level.random;
        int n = (int) (strength * 4.0) + (rng.nextFloat() < strength * 4.0 % 1.0 ? 1 : 0);
        for (int i = 0; i < n; i++) {
            double a = rng.nextDouble() * 2.0 * Math.PI, r = radius * (0.6 + 0.8 * rng.nextDouble());
            double cx = Math.sin(a), cz = Math.cos(a), speed = 0.15 + 0.25 * strength;
            level.addParticle(ParticleTypes.CLOUD, hull.getX() + cx * r, ground + 0.2, hull.getZ() + cz * r,
                    cx * speed, 0.02, cz * speed);
        }
    }
}
