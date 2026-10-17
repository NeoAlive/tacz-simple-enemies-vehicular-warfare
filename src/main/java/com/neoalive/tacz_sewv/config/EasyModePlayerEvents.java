package com.neoalive.tacz_sewv.config;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.invasion.InvasionSession;

/**
 * Easy Mode player-sustain hooks: incoming damage scale (D2) and post-respawn acquire grace (D3).
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID)
public final class EasyModePlayerEvents {

    public static final String TAG_RESPAWN_GRACE_UNTIL = "sewv:easy_grace_until";

    private EasyModePlayerEvents() {}

    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        if (event.getEntity() instanceof Player player) {
            onPlayerHurt(player, event);
            return;
        }
        // Damaging an observer clears that player's soft-priority grace (retaliation OK).
        if (event.getEntity() instanceof AbstractUnit) {
            Entity src = event.getSource().getEntity();
            if (src instanceof Player attacker) {
                clearGraceIfDamagedBy(event.getEntity(), attacker);
            }
        }
    }

    private static void onPlayerHurt(Player player, LivingHurtEvent event) {
        if (player.level().isClientSide()) return;
        if (!EasyMode.playerDamageScaleEnabled()) return;
        if (player.isCreative() || player.isSpectator()) return;
        if (player.level() instanceof ServerLevel sl && InvasionSession.isActive(sl)) return;
        if (!isHostileCombatSource(event.getSource(), player)) return;

        float scale = EasyMode.playerDamageScale();
        if (scale >= 0.999F) return;
        event.setAmount(event.getAmount() * scale);
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!EasyMode.playerPrioritySoft()) return;
        long until = player.level().getGameTime() + EasyMode.PLAYER_RESPAWN_GRACE_TICKS;
        player.getPersistentData().putLong(TAG_RESPAWN_GRACE_UNTIL, until);
    }

    /** True while Easy Mode soft-priority grace is active for this player. */
    public static boolean inRespawnGrace(Player player) {
        if (!EasyMode.playerPrioritySoft() || player == null) return false;
        long until = player.getPersistentData().getLong(TAG_RESPAWN_GRACE_UNTIL);
        return until > 0L && player.level().getGameTime() < until;
    }

    /** Clear grace when the player damages this observer (retaliation allowed). */
    public static void clearGraceIfDamagedBy(LivingEntity observer, LivingEntity attacker) {
        if (!(attacker instanceof Player player)) return;
        if (!EasyMode.playerPrioritySoft()) return;
        player.getPersistentData().remove(TAG_RESPAWN_GRACE_UNTIL);
    }

    private static boolean isHostileCombatSource(DamageSource source, Player player) {
        Entity culprit = source.getEntity();
        if (culprit == null) culprit = source.getDirectEntity();
        if (culprit instanceof Projectile proj && proj.getOwner() != null) {
            culprit = proj.getOwner();
        }
        if (culprit instanceof AbstractUnit unit) {
            return !VehicleTargeting.isNonHostile(unit, player);
        }
        if (culprit instanceof VehicleEntity hull) {
            Entity driver = hull.getFirstPassenger();
            if (driver instanceof AbstractUnit unit) {
                return !VehicleTargeting.isNonHostile(unit, player);
            }
        }
        if (culprit instanceof LivingEntity living && living.getVehicle() instanceof VehicleEntity) {
            // Passenger crew firing / splash attributed to the rider.
            if (living instanceof AbstractUnit unit) {
                return !VehicleTargeting.isNonHostile(unit, player);
            }
        }
        // SBW channels without a clean owner (mortar HE / custom_explosion) still count.
        var typeKey = source.typeHolder().unwrapKey();
        if (typeKey.isPresent()) {
            String path = typeKey.get().location().toString();
            if (path.startsWith("superbwarfare:")) return true;
        }
        return false;
    }
}
