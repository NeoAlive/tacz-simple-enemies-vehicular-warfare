package com.neoalive.tacz_sewv.block;

import com.tacz.guns.api.event.common.EntityHurtByGunEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.LivingKnockBackEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.compat.ExterminationCompat;

/**
 * Sneaking in a walled trench cell nullifies ranged/kinetic and explosive hits (and their
 * knockback). Fall/fire/drown/melee still apply — cover premise, not invulnerability.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID)
public final class TrenchCoverEvents {

    private TrenchCoverEvents() {}

    public static boolean isBallisticOrBlast(DamageSource source) {
        if (ExterminationCompat.isRanged(source)) return true;
        return source.is(DamageTypeTags.IS_EXPLOSION);
    }

    /** True when crouched in a trench cell that still has wall bands (not plinth). */
    public static boolean isSheltered(LivingEntity entity) {
        if (!entity.isCrouching()) return false;
        BlockPos feet = BlockPos.containing(entity.getX(), entity.getY() + 0.01, entity.getZ());
        BlockState state = entity.level().getBlockState(feet);
        if (state.getBlock() instanceof TrenchXCrossBlock) return true;
        if (!(state.getBlock() instanceof TrenchBlock)) {
            // Standing on the upper half: feet can sit just above the lower cell.
            state = entity.level().getBlockState(feet.below());
            if (state.getBlock() instanceof TrenchXCrossBlock) return true;
            if (!(state.getBlock() instanceof TrenchBlock)) return false;
        }
        return state.getValue(TrenchBlock.CONNECTION) != TrenchConnection.PLINTH;
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onAttack(LivingAttackEvent event) {
        if (event.getEntity().level().isClientSide) return;
        if (!isSheltered(event.getEntity())) return;
        if (!isBallisticOrBlast(event.getSource())) return;
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onHurt(LivingHurtEvent event) {
        if (event.getEntity().level().isClientSide) return;
        if (!isSheltered(event.getEntity())) return;
        if (!isBallisticOrBlast(event.getSource())) return;
        event.setCanceled(true);
        event.setAmount(0.0f);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onKnockback(LivingKnockBackEvent event) {
        if (event.getEntity().level().isClientSide) return;
        if (!isSheltered(event.getEntity())) return;
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onGunHurtPre(EntityHurtByGunEvent.Pre event) {
        if (!(event.getHurtEntity() instanceof LivingEntity living)) return;
        if (living.level().isClientSide) return;
        if (!isSheltered(living)) return;
        event.setCanceled(true);
    }
}
