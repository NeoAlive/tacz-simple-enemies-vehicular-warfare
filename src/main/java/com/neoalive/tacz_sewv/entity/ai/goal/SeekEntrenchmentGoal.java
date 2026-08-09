package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.EnumSet;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;

import com.neoalive.tacz_sewv.block.TrenchNetworks;
import com.neoalive.tacz_sewv.bridge.IEntrenched;
import com.neoalive.tacz_sewv.bridge.IMortarCrew;
import com.neoalive.tacz_sewv.bridge.IVehicleBoarder;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.support.EntrenchSupport;

/**
 * Idle RU/US infantry claim a nearby trench network (emplacements first, then cells), writing
 * the same {@link IEntrenched} / board / mortar state a player ENTRENCHED order would.
 *
 * <p>Pattern matches {@link SeekAbandonedVehicleGoal}: flagless, work in {@code canUse}, always
 * return false so locomotion stays with {@link BoardVehicleGoal} / {@link ManMortarGoal} /
 * {@link EntrenchGoal}.
 */
public class SeekEntrenchmentGoal extends Goal {

    private static final int SCAN_INTERVAL = 40;
    private static final int MAX_SCAN_INTERVAL = 200;

    private final AbstractUnit unit;
    private int scanCooldown;
    private int scanInterval = SCAN_INTERVAL;

    public SeekEntrenchmentGoal(AbstractUnit unit) {
        this.unit = unit;
        this.setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        if (!shouldScan()) return false;
        if (this.scanCooldown-- > 0) return false;

        boolean assigned = tryAssign();
        this.scanInterval = assigned
                ? SCAN_INTERVAL
                : Math.min(MAX_SCAN_INTERVAL, this.scanInterval * 2);
        this.scanCooldown = this.scanInterval;
        return false;
    }

    private boolean shouldScan() {
        if (this.unit.level().isClientSide()) return false;
        if (!SewvConfig.AUTO_ENTRENCH_ENABLED.get()) return false;
        if (!(this.unit instanceof RUunitEntity || this.unit instanceof USunitEntity)) return false;
        if (this.unit.isPassenger()) return false;
        if (this.unit.getTarget() != null) return false;
        if (this.unit instanceof IEntrenched e && e.sewv$isEntrenched()) return false;
        if (this.unit instanceof IVehicleBoarder boarder && boarder.tacz_sewv$isBoarding()) return false;
        if (this.unit instanceof IMortarCrew mortar
                && mortar.sewv$getMortarTargetId() != IMortarCrew.NO_MORTAR) {
            return false;
        }
        return true;
    }

    private boolean tryAssign() {
        if (!(this.unit.level() instanceof ServerLevel level)) return false;
        double radius = SewvConfig.AUTO_ENTRENCH_SCAN_RADIUS.get();
        BlockPos here = this.unit.blockPosition();
        TrenchNetworks.NetworkDetail network = TrenchNetworks.get(level)
                .findNearbyNetwork(here, (int) Math.ceil(radius));
        if (network == null) return false;

        BlockPos hit = pickHit(network, here);
        if (hit == null) return false;
        return EntrenchSupport.assign(level, List.of(this.unit), hit) > 0;
    }

    /** Prefer an emplacement pad, else the nearest cell to the unit. */
    private static BlockPos pickHit(TrenchNetworks.NetworkDetail network, BlockPos here) {
        BlockPos bestEmp = null;
        double bestEmpDist = Double.MAX_VALUE;
        for (long packed : network.emplacements()) {
            BlockPos p = BlockPos.of(packed);
            double d = p.distSqr(here);
            if (d < bestEmpDist) {
                bestEmpDist = d;
                bestEmp = p;
            }
        }
        if (bestEmp != null) return bestEmp;

        BlockPos bestCell = null;
        double bestCellDist = Double.MAX_VALUE;
        for (int i = 0; i < network.cells().size(); i++) {
            BlockPos p = BlockPos.of(network.cells().getLong(i));
            double d = p.distSqr(here);
            if (d < bestCellDist) {
                bestCellDist = d;
                bestCell = p;
            }
        }
        return bestCell;
    }
}
