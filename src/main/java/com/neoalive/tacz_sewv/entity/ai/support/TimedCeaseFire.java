package com.neoalive.tacz_sewv.entity.ai.support;

import java.util.EnumSet;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.ai.goal.Goal;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.IPmcDowned;

/**
 * A Cease Fire that lifts itself. The order itself is SEM's CEASE_FIRE, unchanged; this only stamps the unit
 * the order landed on (a UNIT formation's leader for its followers) with a deadline and the order to go back
 * to. Persistent NBT: game-time deadline and an order name, both id-free. Any new order drops the stamp
 * through {@link OrderStandDown}, so an expired stamp only acts on a unit still under CEASE_FIRE.
 */
public final class TimedCeaseFire {

    public static final String TAG = "sewv:timed_cease_fire";
    public static final int MIN_SECONDS = 10;
    public static final int MAX_SECONDS = 120;
    public static final int STEP_SECONDS = 10;

    private static final String DEADLINE = "Deadline";
    private static final String RESTORE = "Restore";

    private TimedCeaseFire() {}

    /** The order a timed cease fire hands back. Orders that carry state the stand-down dropped become FREE_FIRE. */
    public static OrderType restoreFor(OrderType prev) {
        return switch (prev) {
            case FOLLOW_COMMANDER, HOLD_POSITION -> prev;
            default -> OrderType.FREE_FIRE;
        };
    }

    /** What the unit was doing before any cease fire, so re-timing a running one keeps the original order. */
    public static OrderType priorOrder(PmcUnitEntity pmc) {
        CompoundTag all = pmc.getPersistentData();
        if (all.contains(TAG)) return parse(all.getCompound(TAG).getString(RESTORE));
        return pmc.getOrder();
    }

    public static void write(PmcUnitEntity pmc, OrderType prev, int seconds) {
        CompoundTag t = new CompoundTag();
        t.putLong(DEADLINE, pmc.level().getGameTime() + seconds * 20L);
        t.putString(RESTORE, restoreFor(prev).name());
        pmc.getPersistentData().put(TAG, t);
    }

    public static boolean has(PmcUnitEntity pmc) {
        return pmc.getPersistentData().contains(TAG);
    }

    public static void clear(PmcUnitEntity pmc) {
        pmc.getPersistentData().remove(TAG);
    }

    static void expireIfDue(PmcUnitEntity pmc) {
        CompoundTag all = pmc.getPersistentData();
        if (!all.contains(TAG)) return;
        CompoundTag t = all.getCompound(TAG);
        if (pmc.level().getGameTime() < t.getLong(DEADLINE)) return;
        all.remove(TAG);
        if (pmc.getOrder() != OrderType.CEASE_FIRE) return;
        if (pmc instanceof IPmcDowned d && d.sewv$isDowned()) return;
        FormationSubjects.resumeFire(pmc, parse(t.getString(RESTORE)));
    }

    private static OrderType parse(String name) {
        try {
            return restoreFor(OrderType.valueOf(name));
        } catch (IllegalArgumentException e) {
            return OrderType.FREE_FIRE;
        }
    }

    /** Flagless watcher; never runs, it only checks the deadline while being evaluated. */
    public static final class WatchGoal extends Goal {

        private final PmcUnitEntity unit;

        public WatchGoal(PmcUnitEntity unit) {
            this.unit = unit;
            this.setFlags(EnumSet.noneOf(Flag.class));
        }

        @Override
        public boolean canUse() {
            if (!this.unit.level().isClientSide && has(this.unit)) expireIfDue(this.unit);
            return false;
        }
    }
}
