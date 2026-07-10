package com.neoalive.tacz_sewv.network;

import com.neoalive.tacz_sewv.bridge.IVehicleBoarder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.minecraftforge.network.NetworkEvent;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class PacketBoardVehicle {

    private final List<Integer> unitIds;
    private final int vehicleId;

    public PacketBoardVehicle(List<Integer> unitIds, int vehicleId) {
        this.unitIds = unitIds;
        this.vehicleId = vehicleId;
    }

    public PacketBoardVehicle(FriendlyByteBuf buf) {
        int size = buf.readInt();
        this.unitIds = new ArrayList<>();
        for (int i = 0; i < size; i++) this.unitIds.add(buf.readInt());
        this.vehicleId = buf.readInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeInt(this.unitIds.size());
        for (int id : this.unitIds) buf.writeInt(id);
        buf.writeInt(this.vehicleId);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
    ctx.get().enqueueWork(() -> {
        Player player = ctx.get().getSender();
        if (player == null) return;
        System.out.println("[TACZ_SEWV] BOARD packet received for " + this.unitIds.size() + " units");

        for (int unitId : this.unitIds) {
            Entity e = player.level().getEntity(unitId);
            System.out.println("[TACZ_SEWV] unit " + unitId + " = " + e + ", ownedBy=" + 
                (e instanceof PmcUnitEntity p ? p.isOwnedBy(player) : "not a PMC"));
            if (e instanceof PmcUnitEntity pmc && pmc.isOwnedBy(player)) {
                try {
                    IVehicleBoarder boarder = (IVehicleBoarder) pmc;
                    boarder.tacz_sewv$setMountTargetId(this.vehicleId);
                    boarder.tacz_sewv$setBoarding(true);

                    System.out.println(
    "[TACZ_SEWV] AFTER WRITE entity=" + unitId +
    " mount=" + boarder.tacz_sewv$getMountTargetId() +
    " boarding=" + boarder.tacz_sewv$isBoarding()
                    );
                    System.out.println("[TACZ_SEWV] state set on " + unitId);
                } catch (Exception ex) {
                    System.out.println("[TACZ_SEWV] CAST FAILED: " + ex);
                }
            }
        }
    });
    ctx.get().setPacketHandled(true);
}
}