package com.neoalive.tacz_sewv.compat;

import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.jetbrains.annotations.Nullable;

import com.neoalive.tacz_sewv.config.SewvConfig;

/**
 * Extermination's {@code TripodAngerProcedure} only heat-rays {@code instanceof Player} (and a
 * few vanilla classes). SEM units are acquired as targets via our hunt goal but never get a
 * volley. This mirrors the player branch: DIG_SLOWDOWN cooldown, look-at, delayed dual heat rays
 * from the same muzzle offsets — registry-id only, no Extermination class refs.
 */
public final class ExterminationPodFire {

    private static final ResourceLocation HEAT_RAY_ID =
            ResourceLocation.tryParse("extermination:projectile_heat_ray_projectile");
    private static final ResourceLocation SHOOT_SOUND_ID =
            ResourceLocation.tryParse("extermination:entity.tripod.shoot");

    /** Same effect Extermination uses on the player heat-ray branch; shorter so SEM keeps pressure. */
    private static final int COOLDOWN_TICKS = 100;
    private static final int LOOK_DELAY = 8;
    private static final int FIRE_DELAY = 4;

    private static final float DAMAGE = 500.0f;
    private static final byte PIERCE = 10;

    /** Hardcoded muzzle offsets from TripodAngerProcedure (shared by all three pods). */
    private static final double MUZZLE_X = 3.7;
    private static final double MUZZLE_Y = 19.8;
    private static final double MUZZLE_Z = 4.0;

    private static volatile @Nullable EntityType<?> heatRayType;
    private static volatile boolean heatRayResolved;

    private ExterminationPodFire() {}

    /**
     * While a combat pod holds an {@link AbstractUnit} target, fire the player-style heat-ray
     * volley on the same DIG_SLOWDOWN cadence Extermination uses.
     */
    public static void tick(Mob pod) {
        if (!ExterminationCompat.isSemHunter(pod)) return;
        if (!(pod.level() instanceof ServerLevel level)) return;
        LivingEntity target = pod.getTarget();
        if (!(target instanceof AbstractUnit) || !target.isAlive()) return;
        if (pod.hasEffect(MobEffects.DIG_SLOWDOWN)) return;

        pod.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN, COOLDOWN_TICKS, 0, false, false));
        playShoot(level, pod);
        lookAt(pod, target);

        MinecraftServer server = level.getServer();
        int atLook = server.getTickCount() + LOOK_DELAY;
        server.tell(new TickTask(atLook, () -> {
            if (!pod.isAlive()) return;
            LivingEntity still = pod.getTarget();
            if (still != null && still.isAlive()) {
                lookAt(pod, still);
            }
            int atFire = server.getTickCount() + FIRE_DELAY;
            server.tell(new TickTask(atFire, () -> {
                if (!pod.isAlive()) return;
                if (!(pod.level() instanceof ServerLevel sl)) return;
                spawnDualHeatRays(sl, pod);
            }));
        }));
    }

    private static void lookAt(Mob pod, LivingEntity target) {
        pod.lookAt(EntityAnchorArgument.Anchor.EYES, target.position());
    }

    private static void playShoot(ServerLevel level, Mob pod) {
        SoundEvent sound = SHOOT_SOUND_ID != null ? ForgeRegistries.SOUND_EVENTS.getValue(SHOOT_SOUND_ID) : null;
        if (sound == null) return;
        level.playSound(null, BlockPos.containing(pod.getX(), pod.getY(), pod.getZ()), sound, SoundSource.HOSTILE, 4.5f, 1.0f);
    }

    private static void spawnDualHeatRays(ServerLevel level, Mob pod) {
        Vec3 look = pod.getLookAngle();
        double x = pod.getX();
        double y = pod.getY();
        double z = pod.getZ();
        spawnHeatRay(level, pod, x - MUZZLE_X, y + MUZZLE_Y, z + MUZZLE_Z, look);
        spawnHeatRay(level, pod, x + MUZZLE_X, y + MUZZLE_Y, z + MUZZLE_Z, look);
    }

    private static void spawnHeatRay(ServerLevel level, Mob pod, double x, double y, double z, Vec3 look) {
        EntityType<?> type = heatRayType();
        if (type == null) return;
        Entity raw = type.create(level);
        if (!(raw instanceof AbstractArrow arrow)) {
            if (raw != null) raw.discard();
            return;
        }
        arrow.setOwner(pod);
        arrow.setBaseDamage(DAMAGE);
        arrow.setKnockback(0);
        arrow.setSilent(true);
        arrow.setPierceLevel(PIERCE);
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        arrow.setPos(x, y, z);
        float speed = SewvConfig.HEAT_RAY_SPEED.get().floatValue();
        arrow.shoot(look.x, look.y, look.z, speed, 0.0f);
        level.addFreshEntity(arrow);
    }

    @Nullable
    private static EntityType<?> heatRayType() {
        if (heatRayResolved) return heatRayType;
        heatRayResolved = true;
        if (HEAT_RAY_ID != null && ForgeRegistries.ENTITY_TYPES.containsKey(HEAT_RAY_ID)) {
            heatRayType = ForgeRegistries.ENTITY_TYPES.getValue(HEAT_RAY_ID);
        }
        return heatRayType;
    }
}
