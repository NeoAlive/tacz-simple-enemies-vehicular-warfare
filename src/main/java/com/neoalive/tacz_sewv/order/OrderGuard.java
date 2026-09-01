package com.neoalive.tacz_sewv.order;

import net.minecraft.world.entity.player.Player;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.IPmcDowned;

/** Shared downed-unit rejection at order packet entry points. */
public final class OrderGuard {

    private OrderGuard() {}

    /** @return true when the order was rejected and the handler should skip this unit. */
    public static boolean rejectIfDowned(Player sender, PmcUnitEntity pmc) {
        if (pmc instanceof IPmcDowned downed && downed.sewv$isDowned()) {
            OrderReport.fail(sender, OrderFailure.UNIT_DOWNED);
            return true;
        }
        return false;
    }
}
