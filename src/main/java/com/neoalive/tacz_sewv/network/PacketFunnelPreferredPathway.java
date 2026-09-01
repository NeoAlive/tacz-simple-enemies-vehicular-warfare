package com.neoalive.tacz_sewv.network;

import java.util.List;
import java.util.function.Supplier;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.crew.OrderAuth;
import com.neoalive.tacz_sewv.entity.ai.support.OrderStandDown;
import com.neoalive.tacz_sewv.entity.ai.support.PathwaySupport;
import com.neoalive.tacz_sewv.entity.ai.support.PatrolSupport;
import com.neoalive.tacz_sewv.map.PreferredPathwayData;
import com.neoalive.tacz_sewv.order.OrderFailure;
import com.neoalive.tacz_sewv.order.OrderGuard;
import com.neoalive.tacz_sewv.order.OrderReport;

/** Manual funnel: assign nearby owned ground units (on foot or driving) onto a saved pathway. */
public class PacketFunnelPreferredPathway {

    private final List<Integer> unitIds;
    private final String pathId;

    public PacketFunnelPreferredPathway(List<Integer> unitIds, String pathId) {
        this.unitIds = unitIds;
        this.pathId = pathId;
    }

    public PacketFunnelPreferredPathway(FriendlyByteBuf buf) {
        this.unitIds = PacketLists.readUnitIds(buf);
        this.pathId = buf.readUtf();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeCollection(this.unitIds, FriendlyByteBuf::writeVarInt);
        buf.writeUtf(this.pathId);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            var route = PreferredPathwayData.getPath(
                    player.level(), player.getUUID(), player.level().dimension(), this.pathId);
            if (route == null || route.size() < 2) {
                OrderReport.fail(player, OrderFailure.NO_ROUTE);
                return;
            }

            int funneled = 0;
            if (this.unitIds.isEmpty()) {
                for (PmcUnitEntity pmc : PathwaySupport.funnelCandidates(player)) {
                    if (OrderGuard.rejectIfDowned(player, pmc)) continue;
                    funnelOne(pmc, route, this.pathId);
                    funneled++;
                }
            } else {
                for (int unitId : this.unitIds) {
                    Entity e = player.level().getEntity(unitId);
                    if (!(e instanceof PmcUnitEntity pmc)) {
                        OrderReport.fail(player, OrderFailure.NOT_A_UNIT);
                        continue;
                    }
                    if (!OrderAuth.check(player, pmc, "PacketFunnelPreferredPathway")) {
                        OrderReport.fail(player, OrderFailure.NOT_OWNED);
                        continue;
                    }
                    if (OrderGuard.rejectIfDowned(player, pmc)) continue;
                    if (!PathwaySupport.isGroundFunnelUnit(pmc)) continue;
                    funnelOne(pmc, route, this.pathId);
                    funneled++;
                }
            }

            NetworkHandler.orderFeedback(player, "message.tacz_sewv.pathway.funnel",
                    funneled, ChatFormatting.GREEN, funneled, this.pathId);
        });
        ctx.get().setPacketHandled(true);
    }

    private static void funnelOne(PmcUnitEntity pmc, List<net.minecraft.core.BlockPos> route, String pathId) {
        OrderStandDown.clearForPathwayAssign(pmc);
        pmc.setOrder(OrderType.FREE_FIRE);
        if (pmc.getVehicle() instanceof VehicleEntity hull && hull.getFirstPassenger() == pmc) {
            PatrolSupport.beginFunnelRoute(pmc, route);
        } else {
            PathwaySupport.begin(pmc, route, 0, pathId, false);
        }
    }
}
