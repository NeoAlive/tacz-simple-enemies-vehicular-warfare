package com.neoalive.tacz_sewv.order;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.IPmcDowned;
import com.neoalive.tacz_sewv.entity.ai.support.TerritorySupport;

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

    /**
     * Same shape, for a unit on a Territory Mode post: it is commandable only through the RTS panel, so every
     * order that would give it movement of its own is refused. Stand-down entry points (dismiss, dismount,
     * bail) deliberately do not call this — they must always win.
     */
    public static boolean rejectIfTerritory(Player sender, PmcUnitEntity pmc, String channel) {
        return sender instanceof ServerPlayer sp && TerritorySupport.refuses(sp, pmc, channel);
    }
}
