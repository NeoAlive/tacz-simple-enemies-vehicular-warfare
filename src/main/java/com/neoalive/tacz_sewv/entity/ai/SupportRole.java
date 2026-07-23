package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.init.ModItems;
import com.atsuishio.superbwarfare.item.misc.MedicalKitItem;
import com.tacz.guns.api.item.IGun;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

/**
 * Medic and engineer for units that are not medic or engineer <b>entities</b>: the role is read off
 * what the unit is holding, so a player can turn any PMC into one by handing it the right tool and
 * turn it back by taking the tool away.
 *
 * <p>RU/US get their support units as dedicated entity types with their own goal loadouts
 * ({@code SupportUnitGoals}), which a PMC cannot have: a PMC is a unit the <b>player</b> recruits,
 * equips and commands, so its role has to be something the player can change in the field rather
 * than something decided at spawn. That difference is why this is item-driven rather than another
 * pair of entity types — and it is also why the effect is expressed as <em>vetoes and extra goals</em>
 * on an ordinary PMC rather than a rebuilt goal selector: the unit has to be able to go back to
 * being a rifleman the moment the tool leaves its hands.
 *
 * <p><b>Either hand counts, not just the main one.</b> An engineer's sidearm swap
 * ({@code EngineerLoadout.updateHolster}) puts the repair tool in the OFF hand for the duration of
 * a fight — read only the main hand and the role would blink off exactly when the "may it shoot?"
 * question is being asked, which is the one moment it has to be stable.
 */
public enum SupportRole {

    /** An ordinary combatant. */
    NONE,
    /** Holding a medical kit: neutral, never takes a target, patches its own side up. */
    MEDIC,
    /** Holding a repair tool: fixes hulls, and fights only if it has a sidearm and only infantry. */
    ENGINEER;

    /**
     * What this unit's hands say it is. Medic wins a tie: a unit carrying both is more useful alive
     * and neutral than fighting, and picking deterministically beats leaving it to hand order.
     */
    public static SupportRole of(LivingEntity entity) {
        if (!(entity instanceof AbstractUnit unit)) return NONE;
        if (holds(unit, MedicalKitItem.class)) return MEDIC;
        if (unit.getMainHandItem().is(ModItems.REPAIR_TOOL.get())
                || unit.getOffhandItem().is(ModItems.REPAIR_TOOL.get())) {
            return ENGINEER;
        }
        return NONE;
    }

    /** A PMC in a support role — the case the entity-typed {@code VehicleTargeting.isMedic} misses. */
    public static boolean isSupportPmc(LivingEntity entity) {
        return entity instanceof PmcUnitEntity && of(entity) != NONE;
    }

    /**
     * Whether {@code unit} must refuse {@code target}, for a role that does not fight, or does not
     * fight <em>that</em>.
     *
     * <p>Applied at {@code setTarget} rather than by pulling goals out of the selector, because a
     * target that never exists needs no goal to be disabled: SEM's chase goal, its rifle goal and
     * every drive goal in this mod all read {@code getTarget()} and do nothing without one. That
     * also covers the routes a goal edit would miss — retaliation, vanilla's alertOthers
     * propagation, and a player's own ATTACK_THAT_TARGET order.
     */
    public static boolean refusesTarget(AbstractUnit unit, LivingEntity target) {
        return switch (of(unit)) {
            case MEDIC -> true;
            // A repair tool is not a weapon. Without a sidearm the engineer has nothing to fight
            // with, and even with one it leaves armour alone: a pistol cannot scratch a hull, and a
            // unit that traded its job for plinking at a tank would be worse than useless. A crew is
            // exactly a unit riding something, which is what the passenger test says.
            case ENGINEER -> !hasSidearm(unit) || target == null || target.isPassenger();
            case NONE -> false;
        };
    }

    /** A TACZ gun in either hand — what SEM's rifle goal needs to have something to fire. */
    public static boolean hasSidearm(AbstractUnit unit) {
        return IGun.getIGunOrNull(unit.getMainHandItem()) != null
                || IGun.getIGunOrNull(unit.getOffhandItem()) != null;
    }

    private static boolean holds(AbstractUnit unit, Class<?> item) {
        return isItem(unit.getMainHandItem(), item) || isItem(unit.getOffhandItem(), item);
    }

    private static boolean isItem(ItemStack stack, Class<?> item) {
        return !stack.isEmpty() && item.isInstance(stack.getItem());
    }
}
