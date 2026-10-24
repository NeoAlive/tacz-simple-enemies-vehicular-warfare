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
     * The unit that takes a positional order for {@code unitId}: itself on foot, or its hull's
     * driver when it rides one. Selecting a tank by its gunner orders the tank, the same remap the
     * flight orders use. Reports NOT_A_UNIT / NOT_DRIVER / NOT_OWNED and answers null for those.
     */
    @org.jetbrains.annotations.Nullable
    public static PmcUnitEntity orderBody(ServerPlayer sender, int unitId, String context) {
        if (!(sender.level().getEntity(unitId) instanceof PmcUnitEntity selected)) {
            OrderReport.fail(sender, OrderFailure.NOT_A_UNIT);
            return null;
        }
        PmcUnitEntity pmc = selected;
        if (selected.getVehicle() != null) {
            if (!(selected.getVehicle().getFirstPassenger() instanceof PmcUnitEntity driver)) {
                OrderReport.fail(sender, OrderFailure.NOT_DRIVER, selected);
                return null;
            }
            pmc = driver;
        }
        if (!com.neoalive.tacz_sewv.crew.OrderAuth.check(sender, pmc, context)) {
            OrderReport.fail(sender, OrderFailure.NOT_OWNED);
            return null;
        }
        return pmc;
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
