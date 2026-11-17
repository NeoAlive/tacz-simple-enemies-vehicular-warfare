package com.neoalive.tacz_sewv.command.quick;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.crew.OrderAuth;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.entity.ai.cover.CoverQuery;
import com.neoalive.tacz_sewv.entity.ai.support.CoverHoldGeometry;
import com.neoalive.tacz_sewv.entity.ai.support.CoverHoldOrders;
import com.neoalive.tacz_sewv.entity.ai.support.OrderStandDown;
import com.neoalive.tacz_sewv.fob.FobSupport;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.order.OrderGuard;
import com.neoalive.tacz_sewv.order.OrderReport;

/**
 * Cover and Hold: take cover near the block under the crosshair, against a threat downrange of it
 * (the bearing from the issuer through that block), then hold for {@code holdSeconds}, 0 = until the
 * next order. One instance per wheel value, so the hold time rides the pipeline id and the packet
 * needs no field (the role-kit pattern).
 *
 * <p>Takers: an on-foot PMC, or the DRIVER of an SBW wheel/track hull for the whole crew. Aircraft,
 * ships, emplacements and LV hulls (not an SBW {@code VehicleEntity}) are left out, as are units
 * under FOB command. Each taker is stood down and put on FREE_FIRE, which is also where a timed
 * hold leaves it.
 *
 * <p>A hull's spot is picked here, where the batch is known and hulls can be kept apart. A unit on
 * foot searches from its goal instead, because SEM allows only a few cover searches per tick.
 */
public final class QuickCoverHoldPipeline implements QuickCommandPipeline {

    private final int holdSeconds;

    public QuickCoverHoldPipeline(int holdSeconds) {
        this.holdSeconds = holdSeconds;
    }

    @Override
    public void execute(ServerPlayer issuer, QuickCommandContext context) {
        BlockPos anchor = context.aimBlock();
        if (anchor == null) {
            OrderReport.ok(issuer, NetworkHandler.orderLine("cover_hold", null,
                    Component.translatable("message.tacz_sewv.cover_hold.no_point").withStyle(ChatFormatting.GRAY)));
            return;
        }
        ServerLevel level = issuer.serverLevel();
        double radius = context.unitRadius(SewvConfig.QUICK_LAND_RADIUS.get());

        Set<PmcUnitEntity> foot = new LinkedHashSet<>();
        Set<PmcUnitEntity> drivers = new LinkedHashSet<>();
        for (PmcUnitEntity pmc : QuickCommandUnits.owned(issuer, level, context.unitIds(), radius)) {
            if (OrderGuard.rejectIfDowned(issuer, pmc)) continue;
            if (pmc.getVehicle() instanceof VehicleEntity hull) {
                if (!(hull.getFirstPassenger() instanceof PmcUnitEntity driver)) continue;
                if (driver != pmc && !OrderAuth.check(issuer, driver, "QuickCoverHold")) continue;
                if (!HullFacts.isGroundMobileHull(hull) || FobSupport.blocksOrders(driver)) continue;
                drivers.add(driver);
            } else if (pmc.getVehicle() == null && !FobSupport.blocksOrders(pmc)) {
                foot.add(pmc);
            }
        }

        double ax = anchor.getX() + 0.5;
        double az = anchor.getZ() + 0.5;
        double[] bearing = CoverHoldGeometry.bearing(issuer.getX(), issuer.getZ(), ax, az, issuer.getYRot());
        double[] t = CoverHoldGeometry.threatPoint(ax, anchor.getY() + 1.0, az, bearing);
        Vec3 threat = new Vec3(t[0], t[1], t[2]);
        long holdTicks = this.holdSeconds * 20L;

        List<PmcUnitEntity> takers = new ArrayList<>(foot);
        takers.addAll(drivers);
        for (PmcUnitEntity pmc : takers) {
            OrderStandDown.clearForNewOrder(pmc, "CoverHold");
            pmc.setOrder(OrderType.FREE_FIRE);
        }
        CoverHoldOrders.beginBatch(takers, issuer.getUUID());

        int i = 0;
        for (PmcUnitEntity pmc : foot) {
            double[] o = CoverHoldGeometry.spreadOrigin(ax, az, bearing, i++, foot.size());
            CoverHoldOrders.write(pmc, null, o[0], o[1], threat, holdTicks, false, false);
        }
        List<Vec3> claimed = new ArrayList<>();
        for (PmcUnitEntity driver : drivers) {
            VehicleEntity hull = (VehicleEntity) driver.getVehicle();
            Vec3 spot = CoverQuery.suggestHoldCover(level, hull, ax, az, threat.x, threat.z, claimed);
            boolean open = spot == null || !CoverQuery.isCovered(level, spot.x, spot.z, threat.x, threat.z);
            if (spot == null) spot = new Vec3(ax, anchor.getY() + 1.0, az);
            claimed.add(spot);
            CoverHoldOrders.write(driver, BlockPos.containing(spot), ax, az, threat, holdTicks, true, open);
        }

        Component hold = this.holdSeconds <= 0
                ? Component.translatable("message.tacz_sewv.cover_hold.forever")
                : Component.translatable("message.tacz_sewv.cover_hold.seconds", this.holdSeconds);
        NetworkHandler.orderFeedback(issuer, "cover_hold", "message.tacz_sewv.cover_hold.ordered",
                takers.size(), ChatFormatting.GREEN, hold);
    }
}
