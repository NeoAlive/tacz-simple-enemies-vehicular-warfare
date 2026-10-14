package com.neoalive.tacz_sewv.compat.lv;

import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.entity.ai.support.VehicleFormation;

/**
 * Where an LV crew should drive — the order half of {@code VehicleTargeting.resolveDestination},
 * without the SBW-hull-typed tasks layered over it (FOB routes, capture, entrenchment, patrol,
 * assist, idle wander), which v1 of the LV layer deliberately does not carry.
 *
 * <p>PMC obeys its SEM order exactly as a crew in an SBW hull does; RU/US chase their target and
 * otherwise park. Null means "nowhere to go", which the drive goals turn into a held stop.
 */
final class LvOrders {

    private LvOrders() {}

    @Nullable
    static BlockPos destination(AbstractUnit unit) {
        if (!(unit instanceof PmcUnitEntity pmc)) {
            LivingEntity target = liveTarget(unit);
            return target != null ? target.blockPosition() : null;
        }
        OrderType order = pmc.getOrder();
        switch (order) {
            case MOVE_TO_POSITION: {
                Vec3 moveTarget = pmc.getMoveToTarget();
                if (moveTarget == null || moveTarget.equals(Vec3.ZERO)) return null;
                BlockPos slot = VehicleFormation.formationSlotPos(unit.level(), pmc, moveTarget);
                return slot != null ? slot : BlockPos.containing(moveTarget);
            }
            case ATTACK_THAT_TARGET:
            case FREE_FIRE: {
                LivingEntity target = liveTarget(pmc);
                return target != null ? target.blockPosition() : null;
            }
            case FOLLOW_COMMANDER: {
                Player leader = commander(pmc);
                if (leader == null) return null;
                BlockPos slot = VehicleFormation.formationSlotPos(unit.level(), pmc, leader.position());
                return slot != null ? slot : leader.blockPosition();
            }
            case FORM_WEDGE:
            case FORM_COLUMN: {
                Player leader = commander(pmc);
                return leader == null ? null : VehicleFormation.formationSlotPos(unit.level(), pmc, leader.position());
            }
            default:
                return null; // HOLD_POSITION, CEASE_FIRE and anything newer: hold
        }
    }

    /** Orders that name a place rather than a fight: the crew drives there through contact. */
    static boolean positional(AbstractUnit unit) {
        if (!(unit instanceof PmcUnitEntity pmc)) return false;
        OrderType order = pmc.getOrder();
        return order == OrderType.MOVE_TO_POSITION || order == OrderType.FOLLOW_COMMANDER
                || order == OrderType.FORM_WEDGE || order == OrderType.FORM_COLUMN;
    }

    /** The unit's target if it is still alive — a dead one is not somewhere to drive. */
    @Nullable
    static LivingEntity liveTarget(AbstractUnit unit) {
        LivingEntity target = unit.getTarget();
        return target != null && target.isAlive() ? target : null;
    }

    @Nullable
    private static Player commander(PmcUnitEntity pmc) {
        UUID ownerId = pmc.getOwnerUUID();
        return ownerId != null ? pmc.level().getPlayerByUUID(ownerId) : null;
    }
}
