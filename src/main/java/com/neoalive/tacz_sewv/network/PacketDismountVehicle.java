package com.neoalive.tacz_sewv.network;

import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.minecraftforge.network.NetworkEvent;
import com.neoalive.tacz_sewv.bridge.IEscort;
import com.neoalive.tacz_sewv.bridge.IVehicleBoarder;
import com.neoalive.tacz_sewv.entity.ai.MortarSupport;
import com.neoalive.tacz_sewv.entity.ai.PatrolSupport;

import java.util.List;
import java.util.function.Supplier;

public class PacketDismountVehicle {

    private final List<Integer> unitIds;

    public PacketDismountVehicle(List<Integer> unitIds) {
        this.unitIds = unitIds;
    }

    public PacketDismountVehicle(FriendlyByteBuf buf) {
        this.unitIds = buf.readList(FriendlyByteBuf::readVarInt);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeCollection(this.unitIds, FriendlyByteBuf::writeVarInt);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
    ctx.get().enqueueWork(() -> {
        Player player = ctx.get().getSender();
        if (player == null) return;

        int dismounted = 0;
        for (int unitId : this.unitIds) {
            Entity e = player.level().getEntity(unitId);

            // Ownership-check each unit individually so a spoofed packet can't
            // dismount another player's units by id.
            if (e instanceof PmcUnitEntity pmc && pmc.isOwnedBy(player)) {
                boolean wasMounted = pmc.getVehicle() != null;
                if (wasMounted) {
                    pmc.stopRiding();
                }
                IVehicleBoarder boarder = (IVehicleBoarder) pmc;
                boarder.tacz_sewv$setBoarding(false);
                boarder.tacz_sewv$setMountTargetId(-1);
                // This key is the stand-down verb for every order, so it also frees any
                // mortar the unit was holding — otherwise nothing else could ever be
                // assigned to that tube — and cancels any standing patrol order.
                MortarSupport.releaseClaim(pmc);
                PatrolSupport.clear(pmc);
                // Likewise any escort order — dismount stands a unit fully down.
                ((IEscort) pmc).tacz_sewv$setEscortTargetId(-1);
                if (wasMounted) dismounted++;
            }
        }

        NetworkHandler.orderFeedback(player, "message.tacz_sewv.dismount", dismounted,
                ChatFormatting.YELLOW, dismounted);
    });
    ctx.get().setPacketHandled(true);
}
}