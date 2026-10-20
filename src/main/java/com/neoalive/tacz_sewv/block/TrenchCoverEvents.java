package com.neoalive.tacz_sewv.block;


import com.tacz.guns.api.event.common.EntityHurtByGunEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import com.neoalive.tacz_sewv.TaczSewv;

/**
 * Trench cover combat gates, plus sneak speed while manually crouching in a trench
 * (0.5× normal walk).
 */
@EventBusSubscriber(modid = TaczSewv.MODID)
public final class TrenchCoverEvents {

    /**
     * Vanilla sneak multiplies movement by 0.3. ADD_MULTIPLIED_TOTAL amount {@code 0.5/0.3 - 1} makes
     * {@code speed × (1+amt) × 0.3 == 0.5 × walk}.
     */
    // NeoForge port: 1.21 modifiers are keyed by id, not UUID + name; the old name becomes the path.
    private static final ResourceLocation TRENCH_SNEAK_SPEED_UUID =
            ResourceLocation.fromNamespaceAndPath(TaczSewv.MODID, "sewv_trench_sneak");
    private static final AttributeModifier TRENCH_SNEAK_SPEED = new AttributeModifier(
            TRENCH_SNEAK_SPEED_UUID,
            (0.5D / 0.3D) - 1.0D,
            AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);

    private TrenchCoverEvents() {}

    public static boolean isBallisticOrBlast(DamageSource source) {
        return isRanged(source) || source.is(DamageTypeTags.IS_EXPLOSION);
    }

    /** A projectile, or any TaCZ / SBW damage type (their gunfire is not always tagged as projectile). */
    private static boolean isRanged(DamageSource source) {
        if (source.is(DamageTypeTags.IS_PROJECTILE)) return true;
        if (source.getDirectEntity() instanceof Projectile) return true;
        String ns = source.typeHolder().unwrapKey().map(k -> k.location().getNamespace()).orElse(null);
        return "tacz".equals(ns) || "superbwarfare".equals(ns);
    }

    /** Feet sit in a regular / x-cross trench. */
    public static boolean isInTrench(LivingEntity entity) {
        BlockPos feet = BlockPos.containing(entity.getX(), entity.getY() + 0.01, entity.getZ());
        Level level = entity.level();
        if (isTrenchCell(level.getBlockState(feet))) return true;
        return isTrenchCell(level.getBlockState(feet.below()));
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
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
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
    public static void onAttack(LivingIncomingDamageEvent event) {
        if (event.getEntity().level().isClientSide) return;
        if (!isSheltered(event.getEntity())) return;
        if (!isBallisticOrBlast(event.getSource())) return;
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onHurt(LivingIncomingDamageEvent event) {
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
