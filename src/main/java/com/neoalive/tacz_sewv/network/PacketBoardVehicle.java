package com.neoalive.tacz_sewv.network;

import com.neoalive.tacz_sewv.bridge.IEscort;
import com.neoalive.tacz_sewv.bridge.IVehicleBoarder;
import com.neoalive.tacz_sewv.entity.ai.MortarSupport;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

public class PacketBoardVehicle {

    private final List<Integer> unitIds;
    private final int vehicleId;
    private final boolean passengerOnly; // fill non-driver seats only — see IVehicleBoarder

    public PacketBoardVehicle(List<Integer> unitIds, int vehicleId, boolean passengerOnly) {
        this.unitIds = unitIds;
        this.vehicleId = vehicleId;
        this.passengerOnly = passengerOnly;
    }

    public PacketBoardVehicle(FriendlyByteBuf buf) {
        this.unitIds = buf.readList(FriendlyByteBuf::readVarInt);
        this.vehicleId = buf.readVarInt();
        this.passengerOnly = buf.readBoolean();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeCollection(this.unitIds, FriendlyByteBuf::writeVarInt);
        buf.writeVarInt(this.vehicleId);
        buf.writeBoolean(this.passengerOnly);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
    ctx.get().enqueueWork(() -> {
        Player player = ctx.get().getSender();
        if (player == null) return;

        int ordered = 0;
        for (int unitId : this.unitIds) {
            Entity e = player.level().getEntity(unitId);
            if (e instanceof PmcUnitEntity pmc && pmc.isOwnedBy(player)) {
                // A board order goes out to every owned unit in range, so leave crews that
                // are already committed to a mortar out of it — otherwise pressing this at
                // a vehicle silently pulls a working mortar team off its tube, including
                // the units that would only walk over and bounce off a full hull anyway.
                // Stand a crew down with the dismount key first to free it up.
                if (MortarSupport.hasMortarClaim(pmc)) continue;

                IVehicleBoarder boarder = (IVehicleBoarder) pmc;
                boarder.tacz_sewv$setMountTargetId(this.vehicleId);
                boarder.tacz_sewv$setPassengerOnly(this.passengerOnly);
                boarder.tacz_sewv$setBoarding(true);
                // Board and escort both drive the unit on foot at goal priority 1 — a unit can't do
                // both. Boarding wins the moment it's ordered.
                ((IEscort) pmc).tacz_sewv$setEscortTargetId(-1);
                ordered++;
            }
        }

        NetworkHandler.orderFeedback(player, "message.tacz_sewv.board.ordered", ordered,
                ChatFormatting.GREEN, ordered);
    });
    ctx.get().setPacketHandled(true);
}
}