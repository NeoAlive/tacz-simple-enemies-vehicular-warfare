package com.neoalive.tacz_sewv.command.quick;

import java.util.LinkedHashSet;
import java.util.Set;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.tacz.guns.api.item.IGun;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.crew.OrderAuth;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.entity.ai.support.SuppressOrders;
import com.neoalive.tacz_sewv.entity.ai.support.SuppressiveFire;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.order.OrderGuard;
import com.neoalive.tacz_sewv.order.OrderReport;

/**
 * Suppress Area: put fire on a point for {@link SuppressOrders#BUDGET_TICKS}. The point is the block
 * under the crosshair when the wheel opened, or the clicked point on the world map (which sends this
 * same pipeline with its own block), so the order needs no packet of its own.
 *
 * <p>Who takes it: an on-foot PMC holding a TaCZ gun, or the DRIVER of a ground hull with a machine
 * gun (the drive goal runs the crew, and a crew is one entry however many seats were selected).
 * Aircraft, boats and LV hulls are left out: their own goals have no suppress branch. Other orders
 * are not cleared — this pauses them, as SA's does, and they resume when it ends.
 */
public final class QuickSuppressPipeline implements QuickCommandPipeline {

    @Override
    public void execute(ServerPlayer issuer, QuickCommandContext context) {
        BlockPos point = context.aimBlock();
        if (point == null || !SuppressiveFire.enabled()) {
            OrderReport.ok(issuer, NetworkHandler.orderLine("suppress", null,
                    Component.translatable("message.tacz_sewv.suppress.no_point").withStyle(ChatFormatting.GRAY)));
            return;
        }
        ServerLevel level = issuer.serverLevel();
        double radius = context.unitRadius(SewvConfig.QUICK_LAND_RADIUS.get());

        Set<AbstractUnit> takers = new LinkedHashSet<>();
        for (PmcUnitEntity pmc : QuickCommandUnits.owned(issuer, level, context.unitIds(), radius)) {
            if (OrderGuard.rejectIfDowned(issuer, pmc)) continue;
            if (pmc.getVehicle() instanceof VehicleEntity hull) {
                if (!(hull.getFirstPassenger() instanceof PmcUnitEntity driver)) continue;
                if (driver != pmc && !OrderAuth.check(issuer, driver, "QuickSuppress")) continue;
                if (HullFacts.isHelicopterHull(hull) || HullFacts.isPlaneHull(hull) || HullFacts.isShipHull(hull)) continue;
                if (SuppressiveFire.machineGunSeat(hull) < 0) continue;
                takers.add(driver);
            } else if (pmc.getVehicle() == null && IGun.getIGunOrNull(pmc.getMainHandItem()) != null) {
                takers.add(pmc);
            }
        }

        long now = level.getGameTime();
        for (AbstractUnit unit : takers) {
            SuppressOrders.assign(unit, point, issuer.getUUID(), now);
        }
        NetworkHandler.orderFeedback(issuer, "suppress", "message.tacz_sewv.suppress.ordered",
                takers.size(), ChatFormatting.GREEN, takers.size(), SuppressOrders.BUDGET_TICKS / 20);
    }
}
