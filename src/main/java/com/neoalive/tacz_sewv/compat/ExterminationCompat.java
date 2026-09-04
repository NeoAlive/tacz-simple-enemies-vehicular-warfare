package com.neoalive.tacz_sewv.compat;

import java.util.Set;
import java.util.UUID;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.logging.LogUtils;
import com.tacz.guns.api.event.common.EntityHurtByGunEvent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.config.ClientConfig;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.init.ModGameRules;

/**
 * Softcompat for Extermination pods: breakable ranged shield, HP multiplier, SEM targeting,
 * heat-ray volleys against SEM, and heat-ray damage against SBW vehicles.
 *
 * <p>No Extermination class is ever referenced — detection is registry-id only. Cosmetic FX is
 * best-effort after cancel and must never affect the damage block (see
 * {@link ExterminationShieldFx}).
 *
 * <p>Shield absorbs ranged hits until {@link SewvConfig#TRIPOD_SHIELD_BREAK_DAMAGE}, then drops
 * (optional regen). Heat rays are AbstractArrows that only hurt {@link LivingEntity} natively —
 * {@link #onHeatRayImpact} bridges them onto {@link VehicleEntity}.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID)
public final class ExterminationCompat {

    public static final String MODID = "extermination";

    /** Live combat pods + their spawn-egg / rise wrappers. */
    private static final Set<String> SHIELDED_IDS = Set.of(
            "extermination:tripod",
            "extermination:tripod_spawn",
            "extermination:uberpod",
            "extermination:uberpod_spawn",
            "extermination:tripod_harvester",
            "extermination:tripod_harvester_spawn",
            "extermination:emperorpod",
            "extermination:emperorpod_spawn");

    /**
     * Pods that should hunt SEM units at the same priority as players (Extermination's own
     * {@code NearestAttackableTargetGoal(Player)} is priority 5).
     */
    private static final Set<String> SEM_HUNTER_IDS = Set.of(
            "extermination:tripod",
            "extermination:uberpod",
            "extermination:emperorpod");

    private static final ResourceLocation HEAT_RAY_ID =
            ResourceLocation.tryParse("extermination:projectile_heat_ray_projectile");

    /** Same priority Extermination uses for {@code Player}. */
    private static final int SEM_TARGET_PRIORITY = 5;

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String HP_FLAG = "sewv:tripod_hp_boosted";
    private static final UUID HP_MOD_UUID = UUID.fromString("c8e4f2a1-9b3d-4e7f-a0c5-1d6e8b2f4a90");
    private static final String HP_MOD_NAME = "sewv_tripod_hp";

    private static final int DEBUG_FLARE_INTERVAL = 10;

    /** Only latch {@code true}; early false during registry bootstrap must not stick forever. */
    private static boolean resolvedPresent;
    private static boolean available;

    private ExterminationCompat() {}

    public static boolean present() {
        return ModList.get().isLoaded(MODID);
    }

    public static boolean available() {
        resolve();
        return available;
    }

    /** True for any shielded Extermination pod (live or spawn wrapper). */
    public static boolean isShieldedPod(@Nullable Entity entity) {
        if (entity == null || !available()) return false;
        ResourceLocation key = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        return key != null && SHIELDED_IDS.contains(key.toString());
    }

    /** True for live combat pods that hunt SEM units (not spawn wrappers / harvester). */
    public static boolean isSemHunter(@Nullable Entity entity) {
        if (entity == null || !available()) return false;
        ResourceLocation key = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        return key != null && SEM_HUNTER_IDS.contains(key.toString());
    }

    /**
     * {@code /gamerule sewvInvasionOverrides} — only registered when Extermination is loaded.
     * Default true. Gates vehicle pod keep-out and emperor/uber full-body glow suppress.
     */
    public static boolean invasionOverrides(@Nullable Level level) {
        if (level == null || !available()) return false;
        if (ModGameRules.INVASION_OVERRIDES == null) return false;
        return level.getGameRules().getBoolean(ModGameRules.INVASION_OVERRIDES);
    }

    public static boolean isRanged(DamageSource source) {
        if (source.is(DamageTypeTags.IS_PROJECTILE)) return true;
        Entity direct = source.getDirectEntity();
        if (direct instanceof Projectile) return true;
        ResourceLocation typeId = source.typeHolder().unwrapKey().map(ResourceKey::location).orElse(null);
        if (typeId == null) return false;
        String ns = typeId.getNamespace();
        return "tacz".equals(ns) || "superbwarfare".equals(ns);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onGunHurtPre(EntityHurtByGunEvent.Pre event) {
        if (!SewvConfig.TRIPOD_SHIELD_ENABLED.get()) return;
        Entity hurt = event.getHurtEntity();
        if (!isShieldedPod(hurt)) return;
        if (!(hurt instanceof LivingEntity living)) return;

        if (!ExterminationShieldState.tryAbsorb(living, event.getBaseAmount())) return;

        event.setCanceled(true);
        try {
            Entity bullet = event.getBullet();
            Vec3 hit = bullet != null ? closestOnAabb(living.getBoundingBox(), bullet.position()) : null;
            ExterminationShieldFx.onBlockedAt(living, hit);
        } catch (Throwable t) {
            LOGGER.warn("[sewv pod shield] FX failed (TACZ still blocked): {}", t.toString());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onAttack(LivingAttackEvent event) {
        if (!SewvConfig.TRIPOD_SHIELD_ENABLED.get()) return;
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide) return;
        if (event.getAmount() <= 0.0f) return;
        if (!isShieldedPod(entity)) return;
        if (!isRanged(event.getSource())) return;

        if (!ExterminationShieldState.tryAbsorb(entity, event.getAmount())) return;

        event.setCanceled(true);
        try {
            ExterminationShieldFx.onBlocked(entity, event.getSource());
        } catch (Throwable t) {
            LOGGER.warn("[sewv pod shield] FX failed (attack still blocked): {}", t.toString());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onHurt(LivingHurtEvent event) {
        if (!SewvConfig.TRIPOD_SHIELD_ENABLED.get()) return;
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide) return;
        if (event.getAmount() <= 0.0f) return;
        if (!isShieldedPod(entity)) return;
        if (!isRanged(event.getSource())) return;

        if (!ExterminationShieldState.tryAbsorb(entity, event.getAmount())) return;

        event.setCanceled(true);
        event.setAmount(0.0f);
        try {
            ExterminationShieldFx.onBlocked(entity, event.getSource());
        } catch (Throwable t) {
            LOGGER.warn("[sewv pod shield] FX failed (hurt still blocked): {}", t.toString());
        }
    }

    /**
     * AbstractArrow only applies damage to {@link LivingEntity}. SBW hulls are plain entities, so
     * heat rays impact them without calling {@link VehicleEntity#hurt}. Bridge that here.
     */
    @SubscribeEvent
    public static void onHeatRayImpact(ProjectileImpactEvent event) {
        if (!available()) return;
        Projectile projectile = event.getProjectile();
        if (projectile.level().isClientSide) return;
        if (!isHeatRay(projectile)) return;
        HitResult result = event.getRayTraceResult();
        if (!(result instanceof EntityHitResult entityHit)) return;
        Entity hit = entityHit.getEntity();
        if (!(hit instanceof VehicleEntity vehicle)) return;

        float damage = projectile instanceof AbstractArrow arrow
                ? (float) arrow.getBaseDamage()
                : 50.0f;
        if (damage <= 0.0f) return;

        Entity owner = projectile.getOwner();
        DamageSource source = projectile instanceof AbstractArrow arrow
                ? projectile.damageSources().arrow(arrow, owner)
                : projectile.damageSources().mobProjectile(projectile, owner instanceof LivingEntity living ? living : null);
        try {
            vehicle.hurt(source, damage);
        } catch (Throwable t) {
            LOGGER.warn("[sewv heat ray] vehicle hurt failed: {}", t.toString());
        }
    }

    static boolean isHeatRay(@Nullable Entity entity) {
        if (entity == null || HEAT_RAY_ID == null) return false;
        ResourceLocation key = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        return HEAT_RAY_ID.equals(key);
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        Level level = event.getLevel();
        if (level.isClientSide) return;
        Entity entity = event.getEntity();
        boostHeatRayIfNeeded(entity);
        if (!(entity instanceof LivingEntity living)) return;
        if (isShieldedPod(living)) {
            applyHpMultiplier(living);
        }
        if (entity instanceof Mob mob) {
            addSemHuntGoal(mob);
        }
    }

    /**
     * Extermination's high-HP "shield" VFX is four {@code glow_squid_ink} columns that fill the
     * whole hitbox. When invasion overrides are on, drop those commands — our impact flare is the
     * replacement; hurt sound still plays from the procedure before the particle lines.
     */
    @SubscribeEvent
    public static void onCommand(CommandEvent event) {
        if (!available()) return;
        Entity source = event.getParseResults().getContext().getSource().getEntity();
        if (!isShieldedPod(source)) return;
        if (!invasionOverrides(source.level())) return;
        String raw = event.getParseResults().getReader().getString();
        if (raw != null && raw.contains("glow_squid_ink")) {
            event.setCanceled(true);
        }
    }

    /** Speeds heat rays to {@link SewvConfig#HEAT_RAY_SPEED} (default 3× Extermination's 3.5). */
    static void boostHeatRayIfNeeded(Entity entity) {
        if (!isHeatRay(entity)) return;
        Vec3 motion = entity.getDeltaMovement();
        double speed = motion.length();
        if (speed < 1.0e-4) return;
        double want = SewvConfig.HEAT_RAY_SPEED.get();
        entity.setDeltaMovement(motion.scale(want / speed));
    }

    @SubscribeEvent
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide) return;

        if (entity instanceof Mob mob) {
            try {
                ExterminationPodFire.tick(mob);
            } catch (Throwable t) {
                LOGGER.warn("[sewv pod fire] heat-ray volley failed: {}", t.toString());
            }
        }

        if (!ClientConfig.flag(ClientConfig.TRIPOD_SHIELD_FLARE_ALWAYS_ON)) return;
        if (!isShieldedPod(entity)) return;
        if (entity.tickCount % DEBUG_FLARE_INTERVAL != 0) return;
        try {
            ExterminationShieldFx.emitDebug(entity);
        } catch (Throwable t) {
            LOGGER.warn("[sewv pod shield] debug flare failed: {}", t.toString());
        }
    }

    /**
     * Extermination only lists vanilla/illager classes — SEM units never enter the target ladder.
     * Re-added on every join because goals are rebuilt from the entity constructor on chunk load;
     * the marker subclass prevents stacking when the same instance re-joins (e.g. dimension hop).
     */
    static void addSemHuntGoal(Mob mob) {
        if (!isSemHunter(mob)) return;
        for (var wrapped : mob.targetSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof SemHuntGoal) return;
        }
        mob.targetSelector.addGoal(SEM_TARGET_PRIORITY, new SemHuntGoal(mob));
    }

    /** Marker so re-join does not stack another copy of the same hunt goal. */
    private static final class SemHuntGoal extends NearestAttackableTargetGoal<AbstractUnit> {
        SemHuntGoal(Mob mob) {
            super(mob, AbstractUnit.class, true, false);
        }
    }

    static void applyHpMultiplier(LivingEntity living) {
        double mult = SewvConfig.TRIPOD_HP_MULTIPLIER.get();
        if (mult <= 1.0) return;
        if (living.getPersistentData().getBoolean(HP_FLAG)) return;

        AttributeInstance maxHealth = living.getAttribute(Attributes.MAX_HEALTH);
        if (maxHealth == null) return;
        if (maxHealth.getModifier(HP_MOD_UUID) != null) {
            living.getPersistentData().putBoolean(HP_FLAG, true);
            return;
        }

        AttributeModifier mod = new AttributeModifier(
                HP_MOD_UUID, HP_MOD_NAME, mult - 1.0, AttributeModifier.Operation.MULTIPLY_TOTAL);
        maxHealth.addPermanentModifier(mod);
        living.getPersistentData().putBoolean(HP_FLAG, true);
        living.setHealth(living.getMaxHealth());
    }

    static Vec3 closestOnAabb(AABB box, Vec3 p) {
        return new Vec3(
                Mth.clamp(p.x, box.minX, box.maxX),
                Mth.clamp(p.y, box.minY, box.maxY),
                Mth.clamp(p.z, box.minZ, box.maxZ));
    }

    private static void resolve() {
        if (resolvedPresent && available) return;
        if (!present()) {
            resolvedPresent = true;
            available = false;
            return;
        }
        // Any one combat id present means the mod's entity registry loaded.
        ResourceLocation sample = ResourceLocation.tryParse("extermination:tripod");
        available = sample != null && ForgeRegistries.ENTITY_TYPES.containsKey(sample);
        if (available) {
            resolvedPresent = true;
        }
    }
}
