package com.neoalive.tacz_sewv.network;

import java.util.List;
import java.util.function.Supplier;

import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.crew.OrderAuth;
import com.neoalive.tacz_sewv.entity.unit.PmcCommanderEntity;
import com.neoalive.tacz_sewv.invasion.InvasionOrderGate;
import com.neoalive.tacz_sewv.order.OrderFailure;
import com.neoalive.tacz_sewv.order.OrderReport;

/** TDT "Platoon" category — flips whether the selected Commander(s) may dispatch auto-orders. */
public class PacketToggleAutoOrders {

    private final List<Integer> unitIds;

    public PacketToggleAutoOrders(List<Integer> unitIds) {
        this.unitIds = unitIds;
    }

    public PacketToggleAutoOrders(FriendlyByteBuf buf) {
        this.unitIds = PacketLists.readUnitIds(buf);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeCollection(this.unitIds, FriendlyByteBuf::writeVarInt);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            Player player = ctx.get().getSender();
            if (!(player instanceof ServerPlayer sp)) return;
            if (InvasionOrderGate.denyIfActive(sp)) return;

            int ordered = 0;
            for (int unitId : this.unitIds) {
                Entity e = player.level().getEntity(unitId);
                if (!(e instanceof PmcCommanderEntity commander)) {
                    OrderReport.fail(sp, OrderFailure.NOT_COMMANDER);
                    continue;
                }
                if (!OrderAuth.check(sp, commander, "PacketToggleAutoOrders")) {
                    OrderReport.fail(sp, OrderFailure.NOT_OWNED);
                    continue;
                }
                commander.setAutoOrdersEnabled(!commander.autoOrdersEnabled());
                ordered++;
            }

            NetworkHandler.orderFeedback(player, "message.tacz_sewv.platoon.auto_orders", ordered, ChatFormatting.YELLOW);
        });
        ctx.get().setPacketHandled(true);
    }
}
