package com.neoalive.tacz_sewv.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
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
 *   <li>the shield lets the AI out but not in ({@link #passesOutward}).</li>
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

    private static final ResourceLocation TRIPOD_ID = new ResourceLocation(MODID, "tripod");
    private static final ResourceLocation SHIELD_ID = new ResourceLocation(MODID, "tripod_shield");

    private static Boolean present;
    @Nullable
    private static EntityType<?> tripodType;
    @Nullable
    private static Block shieldBlock;
    private static boolean resolved;

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

    /** Fight ring against a tripod: never inside the standoff for RU/US. */
    public static double standoffRing(Entity unit, @Nullable LivingEntity target, double ring) {
        if (!appliesStandoff(unit) || !isTripod(target)) return ring;
        return Math.max(ring, standoff() + MARGIN);
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
     * The shield is a one-way wall for the AI ({@code MixinTripodShieldBlock}): a shield block has no
     * collision for an AI unit, or a hull an AI drives, whose centre is horizontally closer to the
     * nearest tripod than the block is. From inside, every block ahead of the body is farther out than
     * its centre, so a caged crew drives straight out on its own standoff retreat; from outside, every
     * block ahead is nearer, so the shield stays solid as the mod intends. A block already overlapping
     * the body never stops it (vanilla collision only tests cells past the leading face), so it does not
     * matter that the wall turns solid behind a hull's centre as it crosses. Players, any hull carrying
     * one, and projectiles are untouched. Server-side only: the tripod list is.
     */
    public static boolean passesOutward(@Nullable Entity entity, BlockPos pos) {
        if (entity == null || !present() || entity.level().isClientSide()) return false;
        if (!(entity instanceof AbstractUnit || isAiHull(entity))) return false;
        Entity tripod = nearestWithin(entity.level(), Vec3.atCenterOf(pos), SHELL_REACH);
        if (tripod == null) return false;
        double bx = pos.getX() + 0.5 - tripod.getX();
        double bz = pos.getZ() + 0.5 - tripod.getZ();
        double ex = entity.getX() - tripod.getX();
        double ez = entity.getZ() - tripod.getZ();
        return ex * ex + ez * ez < bx * bx + bz * bz;
    }

    private static boolean isAiHull(Entity entity) {
        if (!(entity instanceof VehicleEntity hull) || !(hull.getFirstPassenger() instanceof AbstractUnit)) return false;
        for (Entity rider : hull.getPassengers()) {
            if (rider instanceof Player) return false;
        }
        return true;
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
