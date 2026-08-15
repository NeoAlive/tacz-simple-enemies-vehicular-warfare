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
import com.neoalive.tacz_sewv.entity.ai.command.platoon.PlatoonRegistry;
import com.neoalive.tacz_sewv.invasion.InvasionOrderGate;
import com.neoalive.tacz_sewv.order.OrderFailure;
import com.neoalive.tacz_sewv.order.OrderReport;

/**
 * TDT "Platoon" category — Join Platoon: the selected unit(s) ask to join the platoon led by
 * {@code commanderId}, the entity the player aimed at. Each unit is validated independently
 * (ownership, on-foot/mounted match to the platoon's type, capacity) so a mixed selection reports
 * exactly which ones did not take and why.
 */
public class PacketJoinPlatoon {

    private final List<Integer> unitIds;
    private final int commanderId;

    public PacketJoinPlatoon(List<Integer> unitIds, int commanderId) {
        this.unitIds = unitIds;
        this.commanderId = commanderId;
    }

    public PacketJoinPlatoon(FriendlyByteBuf buf) {
        this.unitIds = PacketLists.readUnitIds(buf);
        this.commanderId = buf.readVarInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeCollection(this.unitIds, FriendlyByteBuf::writeVarInt);
        buf.writeVarInt(this.commanderId);
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
                if (!OrderAuth.check(sp, pmc, "PacketJoinPlatoon")) {
                    OrderReport.fail(sp, OrderFailure.NOT_OWNED);
                    continue;
                }

                PlatoonRegistry.JoinResult result = PlatoonRegistry.joinPlatoon(sp.serverLevel(), this.commanderId, unitId);
                switch (result) {
                    case OK -> ordered++;
                    case ALREADY_IN -> OrderReport.fail(sp, OrderFailure.ALREADY_IN_PLATOON, pmc);
                    case NOT_A_UNIT -> OrderReport.fail(sp, OrderFailure.NOT_A_UNIT);
                    case NOT_OWNED -> OrderReport.fail(sp, OrderFailure.NOT_OWNED);
                    case NO_PLATOON -> OrderReport.fail(sp, OrderFailure.NO_PLATOON_HERE);
                    case MUST_BE_ON_FOOT -> OrderReport.fail(sp, OrderFailure.MUST_BE_ON_FOOT, pmc);
                    case MUST_BE_MOUNTED -> OrderReport.fail(sp, OrderFailure.MUST_BE_MOUNTED, pmc);
                    case FULL -> OrderReport.fail(sp, OrderFailure.PLATOON_FULL, pmc);
                }
            }

            NetworkHandler.orderFeedback(player, "message.tacz_sewv.platoon.join", ordered, ChatFormatting.GREEN);
        });
        ctx.get().setPacketHandled(true);
    }
}
