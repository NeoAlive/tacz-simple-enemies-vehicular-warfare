package com.neoalive.tacz_sewv.entity.ai;

import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.player.Player;
import net.nekoyuni.SimpleEnemyMod.entity.ai.roles.utils.UnitRole;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

/**
 * Goal loadouts for the medic and engineer support units, replacing the faction unit's normal
 * {@code setupRoleGoals} (and, by not calling super, the vehicle-AI injection with it — neither
 * crews anything).
 *
 * <p>A <b>medic</b> gets no targetSelector at all: it is neutral, so it must never pick a fight and
 * never retaliates. An <b>engineer</b> repairs by default but fights when engaged, so it gets SEM's
 * own infantry kit plus targeting restricted to infantry.
 */
public final class SupportUnitGoals {

    private SupportUnitGoals() {}

    public static void medic(AbstractUnit unit, GoalSelector goals, GoalSelector targets) {
        reset(unit, goals, targets);
        goals.addGoal(0, new FloatGoal(unit));
        goals.addGoal(1, new MedicGoal(unit));
        idle(unit, goals);
    }

    public static void engineer(AbstractUnit unit, GoalSelector goals, GoalSelector targets) {
        reset(unit, goals, targets);

        // Repair outranks the combat kit, but stands down on its own the instant the unit holds a
        // target (RepairGoal's canUse bails on it), so this reads as "repair by default, fight when
        // engaged" without either side needing to know about the other.
        goals.addGoal(1, new RepairGoal(unit));

        // Deploys and flies up to a couple of unarmed recon drones that relay spotted enemies
        // to nearby same-faction units/vehicle crews. Claims no flags — it only ever acts on
        // the drones it owns, never on the engineer — so it runs alongside repair and combat
        // with nothing to arbitrate.
        goals.addGoal(1, new DroneOperatorGoal(unit));

        // SEM's own infantry goals — cover, movement, look, stroll, and RangedGunAttackGoal, which
        // fires whatever TACZ gun is in the MAIN hand. That last one is what the holstered sidearm
        // relies on (see EngineerLoadout). Reused rather than rebuilt because it reads SEM's combat
        // config internally, which is a class this mod must not hard-link (it moves between versions).
        UnitRole.DEFAULT.getGoals().addGoals(unit);

        // Infantry ONLY, and deliberately: a pistol cannot scratch a hull, so an engineer that
        // engaged vehicle crews would abandon the one job it is for to plink at a tank. Excluding
        // passengers is the test — a crew is exactly a unit riding something.
        // isNonHostile covers same-faction friends AND SEM's per-faction "friendly to players/PMC"
        // toggle, read from VehicleTargeting's cached copy rather than SEM's config class directly.
        // Also excludes PMC when the faction is friendly (same predicate handles both), which is why
        // this one goal doesn't need its own config gate the way the Player goal below does.
        targets.addGoal(2, new NearestAttackableTargetGoal<>(unit, AbstractUnit.class, true,
                target -> !target.isPassenger() && !VehicleTargeting.isNonHostile(unit, target)));

        // Only installed when NOT friendly — mirrors SEM's own RUunitEntity/USunitEntity, which
        // never adds a Player-targeting goal at all under `if (!RU_UNITS_FRIENDLY.get())`, rather
        // than adding it unconditionally and trusting a predicate to keep it quiet. An engineer
        // used to do the latter: the goal always existed and isNonHostile was its only guard, so
        // any edge case in that one check (or a race on the cached config before it settles) meant
        // an engineer could still acquire a "friendly" player as a target. Not installing the goal
        // when friendly removes that failure mode entirely rather than trying to harden it.
        if (!VehicleTargeting.isFactionFriendly(unit)) {
            targets.addGoal(3, new NearestAttackableTargetGoal<>(unit, Player.class, true,
                    target -> !target.isPassenger() && !VehicleTargeting.isNonHostile(unit, target)));
        }
    }

    private static void reset(AbstractUnit unit, GoalSelector goals, GoalSelector targets) {
        goals.removeAllGoals(g -> true);
        targets.removeAllGoals(g -> true);
        if (unit.getRole() == null) unit.setRole(UnitRole.DEFAULT);
    }

    private static void idle(AbstractUnit unit, GoalSelector goals) {
        goals.addGoal(7, new WaterAvoidingRandomStrollGoal(unit, 0.8));
        goals.addGoal(8, new LookAtPlayerGoal(unit, Player.class, 8.0F));
        goals.addGoal(9, new RandomLookAroundGoal(unit));
    }
}
