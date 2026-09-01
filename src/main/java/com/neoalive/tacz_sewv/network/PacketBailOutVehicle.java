package com.neoalive.tacz_sewv.network;

import java.util.List;
import java.util.function.Supplier;

import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.crew.OrderAuth;
import com.neoalive.tacz_sewv.entity.ai.support.BailOutSupport;
import com.neoalive.tacz_sewv.order.OrderFailure;
import com.neoalive.tacz_sewv.order.OrderGuard;
import com.neoalive.tacz_sewv.order.OrderReport;

/**
 * Player-triggered emergency bail — sets {@link BailOutSupport#TAG_MANUAL_BAIL} for
 * {@link com.neoalive.tacz_sewv.entity.ai.goal.BailOutVehicleGoal} on the next tick.
 */
public class PacketBailOutVehicle {

    private final List<Integer> unitIds;

    public PacketBailOutVehicle(List<Integer> unitIds) {
        this.unitIds = unitIds;
    }

    public PacketBailOutVehicle(FriendlyByteBuf buf) {
        this.unitIds = PacketLists.readUnitIds(buf);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeCollection(this.unitIds, FriendlyByteBuf::writeVarInt);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            net.minecraft.server.level.ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            int queued = 0;
            for (int unitId : this.unitIds) {
                Entity e = player.level().getEntity(unitId);
                if (!(e instanceof PmcUnitEntity pmc)) {
                    OrderReport.fail(player, OrderFailure.NOT_A_UNIT);
                    continue;
                }
                if (!OrderAuth.check(player, pmc, "PacketBailOutVehicle")) {
                    OrderReport.fail(player, OrderFailure.NOT_OWNED);
                    continue;
                }
                if (OrderGuard.rejectIfDowned(player, pmc)) continue;
                if (pmc.getVehicle() == null) {
                    OrderReport.fail(player, OrderFailure.NOT_MOUNTED);
                    continue;
                }
                BailOutSupport.requestManualBail(pmc);
                queued++;
            }

            NetworkHandler.orderFeedback(player, "message.tacz_sewv.bail_out", queued,
                    ChatFormatting.YELLOW, queued);
        });
        ctx.get().setPacketHandled(true);
    }
}
