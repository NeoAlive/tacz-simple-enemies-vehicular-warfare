package com.neoalive.tacz_sewv.command.quick;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.entity.ai.support.MortarSupport;
import com.neoalive.tacz_sewv.order.OrderGuard;

/** Shared server-side unit filters for quick-command pipelines. */
public final class QuickCommandUnits {

    private QuickCommandUnits() {}

    /** Owned on-foot PMCs from the client id list (downed / mortar-claimed skipped). */
    public static List<PmcUnitEntity> onFootOwned(ServerPlayer issuer, ServerLevel level,
                                                  List<Integer> unitIds) {
        List<PmcUnitEntity> out = new ArrayList<>();
        for (int id : unitIds) {
            Entity e = level.getEntity(id);
            if (!(e instanceof PmcUnitEntity pmc)) continue;
            if (!pmc.isOwnedBy(issuer)) continue;
            if (pmc.getVehicle() != null) continue;
            if (OrderGuard.rejectIfDowned(issuer, pmc)) continue;
            if (MortarSupport.hasMortarClaim(pmc)) continue;
            out.add(pmc);
        }
        return out;
    }

    /** Same as {@link #onFootOwned} but as {@link AbstractUnit} for EntrenchSupport. */
    public static List<AbstractUnit> onFootOwnedAsUnits(ServerPlayer issuer, ServerLevel level,
                                                        List<Integer> unitIds) {
        return new ArrayList<>(onFootOwned(issuer, level, unitIds));
    }
}
