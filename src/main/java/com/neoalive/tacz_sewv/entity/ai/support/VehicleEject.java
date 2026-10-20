package com.neoalive.tacz_sewv.entity.ai.support;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.init.ModMobEffects;
import com.atsuishio.superbwarfare.init.ModSounds;
import com.atsuishio.superbwarfare.tools.ParticleTool;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

/**
 * SuperbWarfare's seat ejection, for a unit. SBW only ever ejects a player, from
 * {@code PlayerStopRidingMessage.handler}; this is that handler with the player-only parts swapped
 * for their mob equivalents, read off the jar:
 * <ul>
 * <li>Movement and position are taken <b>while still seated</b> ({@code getEjectionMovement} /
 *     {@code getEjectionPosition}, keyed on {@code getTagSeatIndex}), then the rider dismounts.
 *     Movement is the hull's own velocity plus {@code EjectDirection x EjectForce}, so an ejected
 *     pilot keeps the aircraft's momentum.
 * <li>A player's motion is client-authoritative, so SBW sends it one tick later in a
 *     {@code ClientSetMotionMessage}. A mob's is the server's: it is written straight after
 *     {@code stopRiding()}, with {@code hurtMarked} so the velocity is synced.
 * <li>Rocket sound, an 8-point CLOUD/FLAME/smoke trail along the ejection vector and 10 ticks of
 *     {@code STRIKE_PROTECTION} — exactly SBW's.
 * </ul>
 *
 * <p><b>Data-gated, like SBW's.</b> {@code allowEjection(seat)} is the seat's
 * {@code DismountInfo.CanEject}; a seat without it gets a plain {@code stopRiding()}, i.e. SBW's
 * own seat dismount point — which is what SBW gives a player in that seat too. In the shipped data
 * only fixed-wing seats declare it ({@code kv_16}, {@code j_16}, {@code ju_87} x2, {@code a_10a});
 * neither helicopter does.
 */
public final class VehicleEject {

    private static final int TRAIL_POINTS = 8;
    private static final double TRAIL_STEP = 0.5;
    private static final int STRIKE_PROTECTION_TICKS = 10;

    private VehicleEject() {}

    /** Eject {@code unit} from {@code hull} if its seat allows it, else dismount it. True if ejected. */
    public static boolean eject(AbstractUnit unit, VehicleEntity hull) {
        Vec3 movement;
        Vec3 pos;
        try {
            int seat = hull.getTagSeatIndex(unit);
            if (seat < 0 || !hull.allowEjection(seat)) {
                unit.stopRiding();
                return false;
            }
            movement = hull.getEjectionMovement(unit, seat);
            pos = hull.getEjectionPosition(unit, seat);
        } catch (Throwable unreadable) {
            // An addon hull whose computed() misbehaves still has to let its crew out.
            if (unit.getVehicle() == hull) unit.stopRiding();
            return false;
        }

        unit.level().playSound(null, unit.getX(), unit.getY(), unit.getZ(),
                ModSounds.MEDIUM_ROCKET_FIRE.get(), SoundSource.PLAYERS, 4.0F, 1.0F);
        if (unit.level() instanceof ServerLevel server) {
            Vec3 from = unit.position();
            for (int i = 0; i < TRAIL_POINTS; i++) {
                Vec3 p = from.add(movement.scale(i * TRAIL_STEP));
                ParticleTool.sendParticle(server, ParticleTypes.CLOUD, p.x, p.y, p.z, 10, 0.5, 0.5, 0.5, 0.05, true);
                ParticleTool.sendParticle(server, ParticleTypes.FLAME, p.x, p.y, p.z, 20, 0.5, 0.5, 0.5, 0.05, true);
                ParticleTool.sendParticle(server, ParticleTypes.CAMPFIRE_COSY_SMOKE, p.x, p.y, p.z, 15,
                        0.5, 0.5, 0.5, 0.05, true);
            }
        }

        // EjectForce launches even a crewman on a parked aircraft well clear of a survivable drop.
        BailOutSupport.equipParachute(unit);
        unit.stopRiding();
        unit.setPos(pos.x, pos.y, pos.z);
        unit.setDeltaMovement(movement);
        unit.hurtMarked = true;
        unit.fallDistance = 0.0F;
        unit.addEffect(new MobEffectInstance(ModMobEffects.STRIKE_PROTECTION, STRIKE_PROTECTION_TICKS,
                0, false, false), unit);
        return true;
    }
}
