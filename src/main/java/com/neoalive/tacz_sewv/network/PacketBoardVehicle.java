package com.neoalive.tacz_sewv.network;

import com.neoalive.tacz_sewv.bridge.IVehicleBoarder;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.MortarSupport;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
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
        int size = buf.readVarInt();
        this.unitIds = new ArrayList<>();
        for (int i = 0; i < size; i++) this.unitIds.add(buf.readVarInt());
        this.vehicleId = buf.readVarInt();
        this.passengerOnly = buf.readBoolean();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(this.unitIds.size());
        for (int id : this.unitIds) buf.writeVarInt(id);
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
                ordered++;
            }
        }

        // Server-authoritative feedback — reflects the units the server actually
        // accepted, not the optimistic client count.
        if (SewvConfig.SHOW_ORDER_FEEDBACK.get()) {
            Component msg;
            if (ordered == 0) {
                msg = Component.translatable("message.tacz_sewv.board.ordered.none");
            } else if (ordered == 1) {
                msg = Component.translatable("message.tacz_sewv.board.ordered.single", ordered);
            } else {
                msg = Component.translatable("message.tacz_sewv.board.ordered.multiple", ordered);
            }
            player.displayClientMessage(msg.copy().withStyle(ChatFormatting.GREEN), true);
        }
    });
    ctx.get().setPacketHandled(true);
}
}