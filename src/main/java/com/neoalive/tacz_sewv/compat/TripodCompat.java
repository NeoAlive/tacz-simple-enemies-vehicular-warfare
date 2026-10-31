package com.neoalive.tacz_sewv.compat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;

import com.neoalive.tacz_sewv.config.EasyMode;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.util.LoadedClip;

/**
 * Softcompat for Xiaoxiang's Tripod Invasion ({@code xx_tripod_invasion}): the only class that
 * knows a tripod exists. Three rules, all inert without the mod:
 * <ul>
 *   <li>a tripod's shield does not block an AI line of fire ({@link #isShield}, read by
 *       {@link LoadedClip} and SEM's rifle gate). The shield is invisible blocks with a full
 *       collision shape, so every {@code COLLIDER} ray stops on it. The mod already patches
 *       {@code LivingEntity.hasLineOfSight} through a tripod's own shield (within 128 blocks), so
 *       units acquired tripods fine but no fire gate ever opened. Rounds still hit the shield,
 *       which is how it is meant to be drained;</li>
 *   <li>RU/US keep {@link #standoff()} blocks (horizontal) from any tripod;</li>
 *   <li>a tripod is the top target tier ({@link SoftEnemyTargeting.Tier#APEX});</li>
 *   <li>hulls pass through the shield, AI units on foot only outward ({@link #passesShield});</li>
 *   <li>a gun that cannot reach the body still fires when its line meets a raised shield ({@link #shieldInLine});</li>
 *   <li>SBW's homing missiles aim at the body, not under it ({@link #homingY});</li>
 *   <li>the heat ray burns the hull it crosses, not the crew inside ({@link #heatRayHullHits}).</li>
 * </ul>
 *
 * <p>Nothing here links against the mod: the tripod and its shield are matched by registry id,
 * the same shape as {@link SporeCompat}. The one exception is {@code MixinTripodShieldBlock}, which
 * targets the shield block class by name from its own optional mixin config. That is also what keeps it dormant on the 1.21.1 fork —
 * the mod declares {@code minecraft [1.20.1,1.21)}, so it can never load there and every hook
 * below is a compare against null.
 */
public final class TripodCompat {

    public static final String MODID = "xx_tripod_invasion";
    /** Hysteresis past the standoff: flee to here, hold a fight ring at here. */
    public static final double MARGIN = 16.0;
    /** How far from a tripod a shield block can be: the shell is ~19 x 30 blocks (scaled) round the body. */
    private static final double SHELL_REACH = 64.0;
    /** Room under the elevation stop: SBW's ballistic solution lofts a little above the straight line. */
    private static final double PITCH_SLACK_DEG = 3.0;
    private static final Map<VehicleEntity, Float> MAX_PITCH = new WeakHashMap<>();

    private static final ResourceLocation TRIPOD_ID = new ResourceLocation(MODID, "tripod");
    private static final ResourceLocation SHIELD_ID = new ResourceLocation(MODID, "tripod_shield");
    private static final ResourceKey<DamageType> HEAT_RAY =
            ResourceKey.create(Registries.DAMAGE_TYPE, new ResourceLocation(MODID, "heat_ray"));
    /** A hull takes the beam this many times harder than a body would; Easy Mode's soft heat ray drops it to 1. */
    private static final float HULL_HEAT_MULT = 4.0F;
    /** Per hull, two beams of {last tick, streak, next hit tick}: the mod's {@code Exposure}, game-timed. */
    private static final Map<VehicleEntity, long[]> RAY_EXPOSURE = new WeakHashMap<>();

    private static Boolean present;
    @Nullable
    private static EntityType<?> tripodType;
    @Nullable
    private static Block shieldBlock;
    private static boolean resolved;
    // TripodShield, by reflection: the mod is not on the compile classpath.
    @Nullable
    private static Method shieldGetter;
    private static Method shieldActive;
    private static Method shieldCentre;
    private static double shieldRx;
    private static double shieldRy;

    // ponytail: linear scan; loaded tripods are capped by the mod's own loadedTripodLimit.
    // A list rather than an AABB query: the walker is ~54 blocks tall and the mod has to patch
    // Level.getEntities just to make it findable (LevelTripodQueryMixin).
    private static final Map<Level, List<Entity>> LIVE = new WeakHashMap<>();

    private TripodCompat() {}

    public static boolean present() {
        if (present == null) present = ModList.get().isLoaded(MODID);
        return present;
    }

    private static void resolve() {
        if (resolved) return;
        resolved = true;
        if (!present()) return;
        tripodType = ForgeRegistries.ENTITY_TYPES.getValue(TRIPOD_ID);
        Block block = ForgeRegistries.BLOCKS.getValue(SHIELD_ID);
        // getValue answers the default (air) for an unknown id, never null.
        shieldBlock = block != null && SHIELD_ID.equals(ForgeRegistries.BLOCKS.getKey(block)) ? block : null;
    }

    public static boolean isTripod(@Nullable Entity entity) {
        if (entity == null || !present()) return false;
        resolve();
        return tripodType != null && entity.getType() == tripodType;
    }

    /** Hot path (every AI ray step): one reference compare once resolved. */
    public static boolean isShield(BlockState state) {
        if (!resolved) resolve();
        return shieldBlock != null && state.is(shieldBlock);
    }

    public static double standoff() {
        return SewvConfig.TRIPOD_STANDOFF_DISTANCE.get();
    }

    /** The standoff rule is RU/US only: PMC is the player's to place. */
    public static boolean appliesStandoff(Entity unit) {
        return unit instanceof RUunitEntity || unit instanceof USunitEntity;
    }

    /** Nearest live tripod within {@code radius} blocks, measured horizontally, or null. */
    @Nullable
    public static Entity nearestWithin(Level level, Vec3 pos, double radius) {
        if (!present()) return null;
        List<Entity> tripods = LIVE.get(level);
        if (tripods == null || tripods.isEmpty()) return null;
        Entity best = null;
        double bestSq = radius * radius;
        for (Entity tripod : tripods) {
            if (!tripod.isAlive()) continue;
            double dx = tripod.getX() - pos.x;
            double dz = tripod.getZ() - pos.z;
            double sq = dx * dx + dz * dz;
            if (sq <= bestSq) {
                best = tripod;
                bestSq = sq;
            }
        }
        return best;
    }

    /** Every live tripod in {@code level}; the APEX target scan's candidate list. */
    public static List<Entity> tripods(Level level) {
        List<Entity> tripods = LIVE.get(level);
        return tripods == null ? List.of() : tripods;
    }

    /** Nearest tripod within {@code radius} of an RU/US unit's body (itself or its hull), or null. */
    @Nullable
    public static Entity threatening(Entity unit, Entity body, double radius) {
        if (!present() || !appliesStandoff(unit)) return null;
        return nearestWithin(body.level(), body.position(), radius);
    }

    /** A hull/tube/trench at {@code pos} sits inside some tripod's standoff: do not claim it. */
    public static boolean blocksClaim(Entity unit, Vec3 pos) {
        return present() && appliesStandoff(unit) && nearestWithin(unit.level(), pos, standoff()) != null;
    }

    /**
     * Fight ring against a tripod. RU/US never hold inside the standoff. Any crew in a hull also
     * holds far enough out that its own gun can elevate onto the body ({@link #elevationRange}):
     * SBW's AI turret aims at the bounding-box centre, which on a standing tripod is the body ~45
     * blocks up, and an MBT's +20° stop needs ~125 blocks for that. Closer in, the turret clamps
     * short, the aim never settles and nothing fires.
     */
    public static double standoffRing(Entity unit, @Nullable LivingEntity target, double ring) {
        if (!isTripod(target)) return ring;
        double floor = appliesStandoff(unit) ? standoff() + MARGIN : 0.0;
        if (unit.getVehicle() instanceof VehicleEntity hull) floor = Math.max(floor, elevationRange(hull, target));
        return Math.max(ring, floor);
    }

    /**
     * Horizontal distance at which {@code hull}'s turret, at its elevation stop less
     * {@link #PITCH_SLACK_DEG}, points at {@code tripod}'s bounding-box centre. 0 when the gun
     * elevates near-vertically or the hull carries no turret data. Capped at three quarters of the
     * wide radius, so a crew never backs out of the range it can still see from.
     */
    static double elevationRange(VehicleEntity hull, LivingEntity tripod) {
        double usable = MAX_PITCH.computeIfAbsent(hull, TripodCompat::readMaxPitch) - PITCH_SLACK_DEG;
        if (usable <= 1.0 || usable >= 80.0) return 0.0;
        double rise = tripod.getBoundingBox().getCenter().y - (hull.getY() + hull.getBbHeight());
        if (rise <= 0.0) return 0.0;
        return Math.min(rise / Math.tan(Math.toRadians(usable)), reachCap());
    }

    private static float readMaxPitch(VehicleEntity hull) {
        // Through computed(): read once per hull, a hull's turret cannot change.
        try {
            return hull.getTurretMaxPitch();
        } catch (Throwable unreadable) {
            return 0.0F;
        }
    }

    private static double reachCap() {
        try {
            return SewvConfig.WIDE_SCAN_RADIUS.get() * 0.75;
        } catch (Throwable unbaked) {
            return 192.0;
        }
    }

    /** SEM's chase must not walk an RU/US unit back inside the ring it just fled. */
    public static boolean holdsStandoff(Mob mob) {
        if (!appliesStandoff(mob)) return false;
        LivingEntity target = mob.getTarget();
        if (!isTripod(target)) return false;
        double r = standoff() + MARGIN;
        double dx = target.getX() - mob.getX();
        double dz = target.getZ() - mob.getZ();
        return dx * dx + dz * dz <= r * r;
    }

    /**
     * Who the shield lets through ({@code MixinTripodShieldBlock}). Any hull, crewed by anyone or
     * nobody, both ways and on both sides: the client moves a player-driven hull itself, so the test
     * cannot need the server-only tripod list. An AI unit on foot gets a one-way wall: no collision
     * from a block horizontally farther from the nearest tripod than the unit's centre. From inside,
     * every block ahead of the body is farther out, so a caged unit walks straight out on its standoff
     * retreat; from outside, every block ahead is nearer, so the shield stays solid as the mod intends.
     * A block already overlapping the body never stops it (vanilla collision only tests cells past the
     * leading face). Players on foot and projectiles are untouched.
     */
    public static boolean passesShield(@Nullable Entity entity, BlockPos pos) {
        if (entity == null || !present()) return false;
        if (entity instanceof VehicleEntity) return true;
        if (!(entity instanceof AbstractUnit) || entity.level().isClientSide()) return false;
        Entity tripod = nearestWithin(entity.level(), Vec3.atCenterOf(pos), SHELL_REACH);
        if (tripod == null) return false;
        double bx = pos.getX() + 0.5 - tripod.getX();
        double bz = pos.getZ() + 0.5 - tripod.getZ();
        double ex = entity.getX() - tripod.getX();
        double ez = entity.getZ() - tripod.getZ();
        return ex * ex + ez * ez < bx * bx + bz * bz;
    }

    /**
     * A shot along the barrel line {@code muzzle → dir} lands on {@code target}'s shield. A tripod
     * whose shield is up takes damage wherever the shell is struck, so this is a hit even when the
     * gun cannot elevate (or depress) onto the body. False for anything else, or a shield that is down.
     */
    public static boolean shieldInLine(LivingEntity target, Vec3 muzzle, Vec3 dir) {
        if (!isTripod(target)) return false;
        Vec3 c = activeShieldCentre(target);
        return c != null && ShieldRay.hits(muzzle.x, muzzle.y, muzzle.z, dir.x, dir.y, dir.z,
                c.x, c.y, c.z, shieldRx, shieldRy);
    }

    /** The shield's centre while it is up, else null; also fills {@link #shieldRx}/{@link #shieldRy}. */
    @Nullable
    private static Vec3 activeShieldCentre(Entity tripod) {
        try {
            if (shieldGetter == null) {
                shieldGetter = tripod.getClass().getMethod("getShield");
                Class<?> shield = shieldGetter.getReturnType();
                shieldActive = shield.getMethod("active");
                shieldCentre = shield.getMethod("center");
                shieldRx = shield.getField("RX").getDouble(null);
                shieldRy = shield.getField("RY").getDouble(null);
            }
            Object shield = shieldGetter.invoke(tripod);
            if (shield == null || !(Boolean) shieldActive.invoke(shield)) return null;
            return (Vec3) shieldCentre.invoke(shield);
        } catch (Throwable renamed) {
            // A renamed shield API reads as "down": no shield shots, everything else unchanged.
            return null;
        }
    }

    /**
     * The y SBW's anti-ground missiles should read as a target's feet ({@code MixinMissileTripodHoming}).
     * They home on {@code getY()} (the AGM-65 and Kh-39) or on {@code getY() + half the height} (the
     * Javelin and 9M missiles), but a tripod's hit box is its body alone, from 32 blocks over its feet
     * to its full 53.8: the first flew into the ground under it and the second under its belly. The
     * answer is chosen so the missile's own formula lands on the body's centre.
     */
    public static double homingY(Entity target, boolean addsHalfHeight) {
        if (!isTripod(target)) return target.getY();
        double centre = target.getBoundingBox().getCenter().y;
        return addsHalfHeight ? centre - 0.5 * target.getBbHeight() : centre;
    }

    /**
     * Burns every hull the beam segment {@code start → end} crosses, called once per beam per tick
     * from {@code MixinTripodHeatRay}. The hit test, the ramp (18 + 0.8 per consecutive tick, capped
     * at 100; one hit every 10 - streak/6 ticks, floored at 1) and the damage type are the mod's own
     * {@code TripodHeatRay.trace} copied verbatim, so the only thing decided here is that the target
     * is the hull: its own {@code DamageModifiers} then price the beam ({@code heat_ray} matches only
     * the {@code All} rows, so a T-90's flat -20 shrugs off the opening pulses and the full beam
     * still melts it in seconds). The crew stays out of it through
     * {@code superbwarfare:vehicle_not_absorb}, or an enclosed crew would hand the hull the same
     * beam again once per rider.
     */
    public static void heatRayHullHits(Entity tripod, Vec3 start, Vec3 end, int beam) {
        Level level = tripod.level();
        if (level.isClientSide()) return;
        long now = level.getGameTime();
        DamageSource source = null;
        for (VehicleEntity hull : level.getEntitiesOfClass(VehicleEntity.class, new AABB(start, end).inflate(0.35))) {
            AABB box = hull.getBoundingBox().inflate(0.25);
            if (!box.contains(start) && box.clip(start, end).isEmpty()) continue;
            // ponytail: keyed per hull and beam, not per tripod; two tripods on one hull share a ramp.
            long[] e = RAY_EXPOSURE.computeIfAbsent(hull, h -> new long[6]);
            int i = (beam & 1) * 3; // {last tick, streak, next hit tick}
            if (e[i] == now) continue;
            e[i + 1] = now - e[i] <= 2 ? e[i + 1] + 1 : 1;
            e[i] = now;
            if (e[i + 1] != 1 && now < e[i + 2]) continue;
            e[i + 2] = now + Math.max(1, 10 - e[i + 1] / 6);
            if (source == null) {
                source = new DamageSource(level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
                        .getHolderOrThrow(HEAT_RAY), tripod);
            }
            float pulse = 18.0F + Math.min(82.0F, e[i + 1] * 0.8F);
            hull.hurt(source, EasyMode.softTripodHeatRay() ? pulse : pulse * HULL_HEAT_MULT);
        }
    }

    /** Eye-to-eye block ray with shields transparent (see {@link LoadedClip}). */
    public static boolean clearShot(Mob shooter, LivingEntity target) {
        return LoadedClip.clip(shooter.level(), new ClipContext(shooter.getEyePosition(),
                target.getEyePosition(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, shooter))
                .getType() == HitResult.Type.MISS;
    }

    /** Forge bus; registered only when the mod is present. */
    public static final class Events {
        private Events() {}

        @SubscribeEvent
        public static void onJoin(EntityJoinLevelEvent event) {
            if (event.getLevel().isClientSide() || !isTripod(event.getEntity())) return;
            List<Entity> tripods = LIVE.computeIfAbsent(event.getLevel(), l -> new ArrayList<>());
            if (!tripods.contains(event.getEntity())) tripods.add(event.getEntity());
        }

        @SubscribeEvent
        public static void onLeave(EntityLeaveLevelEvent event) {
            if (event.getLevel().isClientSide()) return;
            List<Entity> tripods = LIVE.get(event.getLevel());
            if (tripods != null) tripods.remove(event.getEntity());
        }
    }
}
