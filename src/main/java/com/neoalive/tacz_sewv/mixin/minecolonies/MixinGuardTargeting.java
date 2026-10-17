package com.neoalive.tacz_sewv.mixin.minecolonies;

import java.util.UUID;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.permissions.Rank;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.core.entity.ai.workers.guard.AbstractEntityAIGuard;
import net.minecraft.world.entity.LivingEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.neoalive.tacz_sewv.compat.MineColoniesCompat;
import com.neoalive.tacz_sewv.config.SewvConfig;

/**
 * Stops colony guards opening fire on a PMC unit the colony has no quarrel with.
 *
 * <p>MineColonies decides who a guard may attack from {@code CompatibilityManager.getAllMonsters()},
 * which is every entity type whose {@code MobCategory} is {@code MONSTER} plus the
 * {@code #minecolonies:hostile} tag. SEM registers {@code pmcunit} as {@code MONSTER}, so a colony
 * shoots the escort the player walked in with. Unlike the mirror-image problem (US units being
 * attackable by nobody), this one <b>cannot</b> be fixed by datapack: a tag can only add to that
 * set, never remove from it, and the only stock opt-out is the per-guard-tower "hostiles"
 * exclusion list in the building UI — a manual action, per building, that a mod cannot ship.
 *
 * <p>{@code isAttackableTarget} is {@code public static}, so a plain cancellable HEAD injection is
 * the whole fix; there is no instance state to reach for. Injecting here rather than at the
 * several call sites means the answer is the same for the target scan, the retaliation path and
 * the patrol alert, which all funnel through it.
 *
 * <p>MineColonies ships no mixins of its own (it uses Access Transformers), so nothing else is
 * competing for this method.
 */
@Mixin(AbstractEntityAIGuard.class)
public abstract class MixinGuardTargeting {

    @Inject(
            method = "isAttackableTarget(Lcom/minecolonies/api/entity/citizen/AbstractEntityCitizen;Lnet/minecraft/world/entity/LivingEntity;)Z",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private static void tacz_sewv$spareOwnedPmc(AbstractEntityCitizen user, LivingEntity entity,
                                                CallbackInfoReturnable<Boolean> cir) {
        if (!MineColoniesCompat.present()) return;
        if (!SewvConfig.MINECOLONIES_PROTECT_PMC.get()) return;
        if (!(entity instanceof PmcUnitEntity pmc)) return;

        // An ownerless PMC is a friendly camp garrison that nobody commands, not the player's
        // escort — it stays a valid target.
        UUID owner = pmc.getOwnerUUID();
        if (owner == null) return;

        IColony colony = user.getCitizenColonyHandler().getColonyOrRegister();
        if (colony == null) return;

        // Spare it unless the colony has declared its owner hostile. MineColonies flips a player
        // to that rank by itself once they attack the colony (isValidAttackingPlayer), so a
        // stranger who opens fire loses the protection without anyone having to configure it —
        // which is why "not hostile" is a safe default rather than "is a colony member".
        Rank rank = colony.getPermissions().getRank(owner);
        if (rank != null && !rank.isHostile()) {
            cir.setReturnValue(false);
        }
    }
}
