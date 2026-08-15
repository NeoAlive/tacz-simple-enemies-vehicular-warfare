package com.neoalive.tacz_sewv.network;

import java.util.List;
import java.util.function.Supplier;

import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkEvent;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.crew.OrderAuth;
import com.neoalive.tacz_sewv.entity.ai.command.platoon.Platoon;
import com.neoalive.tacz_sewv.entity.ai.command.platoon.PlatoonRegistry;
import com.neoalive.tacz_sewv.invasion.InvasionOrderGate;
import com.neoalive.tacz_sewv.order.OrderFailure;
import com.neoalive.tacz_sewv.order.OrderReport;

/** TDT "Platoon" category — Exit Platoon: the selected unit(s) leave their platoon manually. */
public class PacketExitPlatoon {

    private final List<Integer> unitIds;

    public PacketExitPlatoon(List<Integer> unitIds) {
        this.unitIds = unitIds;
    }

    public PacketExitPlatoon(FriendlyByteBuf buf) {
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
                if (!(e instanceof PmcUnitEntity pmc)) {
                    OrderReport.fail(sp, OrderFailure.NOT_A_UNIT);
                    continue;
                }
                if (!OrderAuth.check(sp, pmc, "PacketExitPlatoon")) {
                    OrderReport.fail(sp, OrderFailure.NOT_OWNED);
                    continue;
                }
                Platoon platoon = PlatoonRegistry.platoonOf(sp.serverLevel(), unitId);
                if (platoon == null) {
                    OrderReport.fail(sp, OrderFailure.NOT_IN_PLATOON, pmc);
                    continue;
                }
                PlatoonRegistry.exitPlatoon(sp.serverLevel(), unitId);
                ordered++;
            }

            NetworkHandler.orderFeedback(player, "message.tacz_sewv.platoon.exit", ordered, ChatFormatting.YELLOW);
        });
        ctx.get().setPacketHandled(true);
    }
}
