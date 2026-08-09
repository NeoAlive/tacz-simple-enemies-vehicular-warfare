package com.neoalive.tacz_sewv.block;

import java.util.UUID;

import com.tacz.guns.api.event.common.EntityHurtByGunEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.LivingKnockBackEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.compat.ExterminationCompat;

/**
 * Trench cover combat gates, plus movement spices: netting ceiling forces sneak (collision),
 * and sneaking inside any trench runs at 2× normal walk speed.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID)
public final class TrenchCoverEvents {

    /**
     * Vanilla sneak multiplies movement by 0.3. MULTIPLY_TOTAL amount {@code 2/0.3 - 1} makes
     * {@code speed × (1+amt) × 0.3 == 2 × walk}.
     */
    private static final UUID TRENCH_SNEAK_SPEED_UUID =
            UUID.fromString("a3c8e2f1-5b4d-4e9a-9c7f-1d2e3f4a5b6c");
    private static final AttributeModifier TRENCH_SNEAK_SPEED = new AttributeModifier(
            TRENCH_SNEAK_SPEED_UUID,
            "sewv_trench_sneak",
            (2.0D / 0.3D) - 1.0D,
            AttributeModifier.Operation.MULTIPLY_TOTAL);

    private TrenchCoverEvents() {}

    public static boolean isBallisticOrBlast(DamageSource source) {
        if (ExterminationCompat.isRanged(source)) return true;
        return source.is(DamageTypeTags.IS_EXPLOSION);
    }

    /** Feet (or the cell below) sit in a regular / x-cross trench. */
    public static boolean isInTrench(LivingEntity entity) {
        BlockPos feet = BlockPos.containing(entity.getX(), entity.getY() + 0.01, entity.getZ());
        if (isTrenchCell(entity.level().getBlockState(feet))) return true;
        return isTrenchCell(entity.level().getBlockState(feet.below()));
    }

    private static boolean isTrenchCell(BlockState state) {
        return state.getBlock() instanceof TrenchBlock
                || state.getBlock() instanceof TrenchXCrossBlock;
    }

    /** True when crouched in a trench cell that still has wall bands (not plinth). */
    public static boolean isSheltered(LivingEntity entity) {
        if (!entity.isCrouching()) return false;
        BlockPos feet = BlockPos.containing(entity.getX(), entity.getY() + 0.01, entity.getZ());
        BlockState state = entity.level().getBlockState(feet);
        if (state.getBlock() instanceof TrenchXCrossBlock) return true;
        if (!(state.getBlock() instanceof TrenchBlock)) {
            state = entity.level().getBlockState(feet.below());
            if (state.getBlock() instanceof TrenchXCrossBlock) return true;
            if (!(state.getBlock() instanceof TrenchBlock)) return false;
        }
        return state.getValue(TrenchBlock.CONNECTION) != TrenchConnection.PLINTH;
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Player player = event.player;
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) return;

        boolean boost = player.isCrouching() && isInTrench(player);
        boolean has = speed.getModifier(TRENCH_SNEAK_SPEED_UUID) != null;
        if (boost && !has) {
            speed.addTransientModifier(TRENCH_SNEAK_SPEED);
        } else if (!boost && has) {
            speed.removeModifier(TRENCH_SNEAK_SPEED_UUID);
        }
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
