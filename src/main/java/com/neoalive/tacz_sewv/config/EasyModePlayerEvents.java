package com.neoalive.tacz_sewv.config;

import java.util.List;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.tacz.guns.api.event.common.EntityHurtByGunEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.common.Mod;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;

/**
 * Easy Mode's player-side half: hostile damage scaling for the player and their PMC, the
 * TaCZ bullet backstop, the respawn grace and the threat highlight. Everything reads through
 * {@link EasyMode}, which is already off during an invasion.
 *
 * <p>Only this mod's and SEM's sources and entities are touched: no player attribute, no healing,
 * no knockback, nothing a vanilla or other-mod hit would feel.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID)
public final class EasyModePlayerEvents {

    public static final String TAG_RESPAWN_GRACE_UNTIL = "sewv:easy_grace_until";

    private static final int HIGHLIGHT_INTERVAL_TICKS = 20;

    private EasyModePlayerEvents() {}

    // --- Damage ---

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingHurt(LivingHurtEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide()) return;
        if (victim instanceof Player player) {
            onPlayerHurt(player, event);
            return;
        }
        if (victim instanceof PmcUnitEntity pmc && pmc.getOwnerUUID() != null) {
            onPmcHurt(pmc, event);
            return;
        }
        // Damaging an observer clears that player's soft-priority grace (retaliation OK).
        if (victim instanceof AbstractUnit) {
            Entity src = event.getSource().getEntity();
            if (src instanceof Player attacker) {
                clearGraceIfDamagedBy(victim, attacker);
            }
        }
    }

    /**
     * LOWEST, so the scale lands after SBW's seat-absorb split has moved its share onto the hull
     * (that share is scaled separately, by the hull mixin) — the player's own part is what is left.
     */
    private static void onPlayerHurt(Player player, LivingHurtEvent event) {
        if (!EasyMode.active()) return;
        if (player.isCreative() || player.isSpectator()) return;
        DamageSource source = event.getSource();
        // The player's own SBW/TaCZ ordnance only: their TNT or bed is vanilla's business.
        boolean selfBlast = isExplosion(source) && culpritOf(source) == player && isProjectDamage(source);
        if (!selfBlast && !isHostileTo(source, player)) return;

        float scale = 1.0f;
        if (EasyMode.playerDamageScaleEnabled()) scale *= EasyMode.playerDamageScale();
        if (EasyMode.playerSustain() && isExplosion(source)) scale *= EasyTuning.EXPLOSION_EXTRA_MULT;
        if (!selfBlast && isVehicleFire(source)) scale *= EasyTuning.VEHICLE_TO_PLAYER_MULT;
        if (scale < 0.999F) event.setAmount(event.getAmount() * scale);
    }

    private static void onPmcHurt(PmcUnitEntity pmc, LivingHurtEvent event) {
        if (!EasyMode.buffPmc()) return;
        if (!isHostileTo(event.getSource(), pmc)) return;
        float scale = EasyTuning.PMC_DAMAGE_TAKEN;
        if (isExplosion(event.getSource())) scale *= EasyTuning.EXPLOSION_EXTRA_MULT;
        event.setAmount(event.getAmount() * scale);
    }

    /**
     * TaCZ's own pre-hit hook for a hostile rifleman's bullet into the player's side: less base
     * damage and no headshot bonus. Version-proof against SEM, since it keys on TaCZ alone — it
     * holds even if every SEM rifle mixin failed to apply.
     */
    @SubscribeEvent
    public static void onGunHurtPre(EntityHurtByGunEvent.Pre event) {
        if (event.getLogicalSide() != LogicalSide.SERVER) return;
        if (!EasyMode.softInfantry(event.getAttacker())) return;
        if (!EasyMode.isFriendlySide(event.getHurtEntity())) return;
        event.setBaseAmount(event.getBaseAmount() * EasyTuning.INFANTRY_BULLET_MULT);
        event.setHeadshotMultiplier(1.0f);
    }

    /**
     * Hostile damage to a hull crewed by the player's side, for {@code MixinVehicleEasyDamage}.
     * Shooting a hostile-crewed hull also ends that player's respawn grace, the same as hitting a
     * unit does — a hull is not a LivingEntity, so the hurt event never sees it.
     */
    public static float scaleHullDamage(VehicleEntity hull, DamageSource source, float amount) {
        if (!EasyMode.active()) return amount;
        Entity culprit = culpritOf(source);
        if (culprit instanceof Player attacker && EasyMode.isHostileActor(hull)) {
            clearGraceIfDamagedBy(null, attacker);
            return amount;
        }
        if (!EasyMode.protectVehicles() || !EasyMode.isFriendlySide(hull)) return amount;
        if (!isHostileActorSource(source, culprit)) return amount;
        return amount * EasyTuning.FRIENDLY_HULL_DAMAGE_MULT;
    }

    // --- Sustain: bonus hearts, regeneration, highlight ---

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.player instanceof ServerPlayer player)) return;
        // tickCount is per-entity and advances every tick, so a modulo on it is a real cadence
        // (unlike a goal-tick parity).
        int t = player.tickCount;
        if (t % HIGHLIGHT_INTERVAL_TICKS == 0) highlightThreats(player);
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!EasyMode.playerPrioritySoft()) return;
        long until = player.level().getGameTime() + EasyMode.PLAYER_RESPAWN_GRACE_TICKS;
        player.getPersistentData().putLong(TAG_RESPAWN_GRACE_UNTIL, until);
    }

    /**
     * Hostile units targeting this player glow. Server-side effect, so nothing needs syncing; a
     * crewman hidden by his seat glows unseen, but the hull he sits in is hard to miss anyway.
     */
    private static void highlightThreats(ServerPlayer player) {
        if (!EasyMode.highlightThreats()) return;
        if (player.isCreative() || player.isSpectator()) return;
        if (!(player.level() instanceof ServerLevel level)) return;
        List<AbstractUnit> threats = level.getEntitiesOfClass(AbstractUnit.class,
                player.getBoundingBox().inflate(EasyTuning.HIGHLIGHT_RADIUS),
                u -> u.getTarget() == player && u.isAlive());
        for (AbstractUnit unit : threats) {
            unit.addEffect(new MobEffectInstance(MobEffects.GLOWING, EasyTuning.HIGHLIGHT_GLOW_TICKS, 0, false, false));
        }
    }

    // --- Targeting grace / soft priority ---

    /** True while Easy Mode soft-priority grace is active for this player. */
    public static boolean inRespawnGrace(Player player) {
        if (!EasyMode.playerPrioritySoft() || player == null) return false;
        long until = player.getPersistentData().getLong(TAG_RESPAWN_GRACE_UNTIL);
        return until > 0L && player.level().getGameTime() < until;
    }

    /** Whether {@code observer} must not proactively pick {@code player} (grace, no retaliation). */
    public static boolean refusesPlayer(LivingEntity observer, Player player) {
        return inRespawnGrace(player) && observer.getLastHurtByMob() != player;
    }

    /**
     * Soft priority: when any non-player is a candidate, drop the players — except one that just
     * hurt this observer, which it may always answer.
     */
    public static void preferNonPlayers(LivingEntity observer, List<? extends LivingEntity> candidates) {
        if (!EasyMode.playerPrioritySoft()) return;
        boolean hasNonPlayer = false;
        for (LivingEntity e : candidates) {
            if (!(e instanceof Player)) {
                hasNonPlayer = true;
                break;
            }
        }
        if (!hasNonPlayer) return;
        LivingEntity retaliate = observer.getLastHurtByMob();
        candidates.removeIf(e -> e instanceof Player p && retaliate != p);
    }

    /** Clear grace when the player damages a hostile (retaliation allowed). */
    public static void clearGraceIfDamagedBy(@Nullable LivingEntity observer, LivingEntity attacker) {
        if (!(attacker instanceof Player player)) return;
        if (!EasyMode.playerPrioritySoft()) return;
        player.getPersistentData().remove(TAG_RESPAWN_GRACE_UNTIL);
    }

    // --- Source classification ---

    private static boolean isProjectDamage(DamageSource source) {
        var key = source.typeHolder().unwrapKey();
        if (key.isEmpty()) return false;
        String ns = key.get().location().getNamespace();
        return ns.equals("superbwarfare") || ns.equals("tacz");
    }

    private static boolean isExplosion(DamageSource source) {
        return source.is(DamageTypeTags.IS_EXPLOSION);
    }

    /**
     * Who is behind a hit: the attacking entity, else the direct one, a projectile resolved to its
     * owner, and a hull resolved to the first unit aboard it (any seat — the gunner shooting is
     * rarely the driver).
     */
    @Nullable
    private static Entity culpritOf(DamageSource source) {
        Entity culprit = source.getEntity();
        if (culprit == null) culprit = source.getDirectEntity();
        if (culprit instanceof Projectile proj && proj.getOwner() != null) {
            culprit = proj.getOwner();
        }
        if (culprit instanceof VehicleEntity hull) {
            for (Entity passenger : hull.getPassengers()) {
                if (passenger instanceof AbstractUnit) return passenger;
            }
        }
        return culprit;
    }

    /**
     * A hit from a vehicle weapon: a hull in the source chain, a unit firing from a seat, or
     * unattributed SBW ordnance (shells and their splash). Hand-held SBW launchers fired on foot are
     * not vehicles and keep the plain scale.
     */
    private static boolean isVehicleFire(DamageSource source) {
        Entity attacker = source.getEntity();
        Entity direct = source.getDirectEntity();
        if (attacker instanceof VehicleEntity || direct instanceof VehicleEntity) return true;
        if (direct instanceof Projectile proj && proj.getOwner() instanceof VehicleEntity) return true;
        Entity culprit = culpritOf(source);
        if (culprit instanceof AbstractUnit unit) return unit.getVehicle() instanceof VehicleEntity;
        return culprit == null && isUnattributedSbw(source, null);
    }

    private static boolean isHostileTo(DamageSource source, LivingEntity victim) {
        Entity culprit = culpritOf(source);
        if (culprit instanceof AbstractUnit unit) {
            return !VehicleTargeting.isNonHostile(unit, victim);
        }
        return isUnattributedSbw(source, culprit);
    }

    private static boolean isHostileActorSource(DamageSource source, @Nullable Entity culprit) {
        if (culprit instanceof AbstractUnit) return EasyMode.isHostileActor(culprit);
        return isUnattributedSbw(source, culprit);
    }

    /**
     * SBW channels without a clean owner (mortar HE / custom_explosion) still count — but never a
     * player's own fire, which is the player's business.
     */
    private static boolean isUnattributedSbw(DamageSource source, @Nullable Entity culprit) {
        if (culprit instanceof Player) return false;
        var typeKey = source.typeHolder().unwrapKey();
        return typeKey.isPresent() && typeKey.get().location().getNamespace().equals("superbwarfare");
    }
}
