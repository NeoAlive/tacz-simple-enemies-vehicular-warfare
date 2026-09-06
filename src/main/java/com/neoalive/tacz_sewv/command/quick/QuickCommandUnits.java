package com.neoalive.tacz_sewv.command.quick;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.crew.OrderAuth;
import com.neoalive.tacz_sewv.entity.ai.support.MortarSupport;
import com.neoalive.tacz_sewv.fob.FobSupport;
import com.neoalive.tacz_sewv.order.OrderFailure;
import com.neoalive.tacz_sewv.order.OrderGuard;
import com.neoalive.tacz_sewv.order.OrderReport;

/** Shared server-side unit filters for quick-command pipelines. */
public final class QuickCommandUnits {

    private QuickCommandUnits() {}

    /**
     * Owned PMCs from the client id list (team-aware). Skips dead units.
     * Optional radius re-check against the issuer (client pick is not authoritative).
     */
    public static List<PmcUnitEntity> owned(ServerPlayer issuer, ServerLevel level,
                                            List<Integer> unitIds, double radius) {
        double r2 = radius * radius;
        List<PmcUnitEntity> out = new ArrayList<>();
        for (int id : unitIds) {
            Entity e = level.getEntity(id);
            if (!(e instanceof PmcUnitEntity pmc) || !pmc.isAlive()) continue;
            if (!OrderAuth.check(issuer, pmc, "QuickCommand")) continue;
            if (pmc.distanceToSqr(issuer) > r2) continue;
            out.add(pmc);
        }
        return out;
    }

    /** Owned on-foot PMCs (downed / mortar-claimed / FOB-command / route-pending skipped). */
    public static List<PmcUnitEntity> onFootOwned(ServerPlayer issuer, ServerLevel level,
                                                  List<Integer> unitIds) {
        return onFootOwned(issuer, level, unitIds,
                com.neoalive.tacz_sewv.config.SewvConfig.QUICK_LAND_RADIUS.get());
    }

    public static List<PmcUnitEntity> onFootOwned(ServerPlayer issuer, ServerLevel level,
                                                  List<Integer> unitIds, double radius) {
        List<PmcUnitEntity> out = new ArrayList<>();
        for (PmcUnitEntity pmc : owned(issuer, level, unitIds, radius)) {
            if (pmc.getVehicle() != null) continue;
            if (OrderGuard.rejectIfDowned(issuer, pmc)) continue;
            if (MortarSupport.hasMortarClaim(pmc)) continue;
            if (refuseFobOrRoute(issuer, pmc)) continue;
            out.add(pmc);
        }
        return out;
    }

    /** Same as {@link #onFootOwned} but as {@link AbstractUnit} for EntrenchSupport. */
    public static List<AbstractUnit> onFootOwnedAsUnits(ServerPlayer issuer, ServerLevel level,
                                                        List<Integer> unitIds) {
        return new ArrayList<>(onFootOwned(issuer, level, unitIds));
    }

    /**
     * Gate for movement / board / formation orders under FOB command or an in-flight FOB route.
     * Cancel / dismount skip this — stand-down must always win.
     */
    public static boolean refuseFobOrRoute(ServerPlayer issuer, PmcUnitEntity pmc) {
        if (FobSupport.blocksOrders(pmc)) {
            OrderReport.fail(issuer, OrderFailure.FOB_COMMAND, pmc);
            return true;
        }
        if (FobSupport.hasRoutePending(pmc)) {
            OrderReport.fail(issuer, OrderFailure.ROUTE_ACTIVE, pmc);
            return true;
        }
        return false;
    }
}
