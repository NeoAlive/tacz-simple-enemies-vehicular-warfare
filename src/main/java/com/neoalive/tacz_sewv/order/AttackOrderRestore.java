package com.neoalive.tacz_sewv.order;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

/**
 * Carries a PMC's ATTACK_THAT_TARGET order across a reload.
 *
 * <p>SEM saves the order ({@code CurrentOrder}) but not its target, which is a network id and
 * means nothing in the next session. A reloaded unit therefore comes back as ATTACK_THAT_TARGET
 * with id -1: {@code AttackSpecificTargetGoal.canUse} wants an id, so it never runs and never
 * reaches the {@code stop()} that would drop the order, and our own scans yield under that order —
 * the unit (an artillery battery on an entity mission, most visibly) sits there forever.
 * The target's UUID rides in persistent data instead and is re-resolved to a fresh id.
 */
public final class AttackOrderRestore {

    private static final String TAG = "sewv:attack_target";

    /** How long after a load the target gets to turn up (its chunk may load after ours). */
    private static final int GRACE_TICKS = 600;

    private AttackOrderRestore() {}

    /** From {@code setAttackTargetId} HEAD: remember who the id names while it still resolves. */
    public static void remember(PmcUnitEntity pmc, int id) {
        if (pmc.level().isClientSide()) return;
        Entity target = id < 0 ? null : pmc.level().getEntity(id);
        if (target == null) {
            pmc.getPersistentData().remove(TAG);
        } else {
            pmc.getPersistentData().putUUID(TAG, target.getUUID());
        }
    }

    /** From the unit's tick: re-point a reloaded order at its target, or drop it to FREE_FIRE. */
    public static void tick(PmcUnitEntity pmc) {
        if (pmc.tickCount % 20 != 0) return;
        if (pmc.getOrder() != OrderType.ATTACK_THAT_TARGET || pmc.getAttackTargetId() != -1) return;
        if (!(pmc.level() instanceof ServerLevel level)) return;

        boolean saved = pmc.getPersistentData().hasUUID(TAG);
        if (saved && level.getEntity(pmc.getPersistentData().getUUID(TAG)) instanceof LivingEntity t
                && t.isAlive()) {
            pmc.setAttackTargetId(t.getId());
            return;
        }
        // tickCount restarts at 0 on every load, so this is "since the unit came back".
        if (saved && pmc.tickCount < GRACE_TICKS) return;
        pmc.getPersistentData().remove(TAG);
        pmc.setOrder(OrderType.FREE_FIRE);
    }
}
