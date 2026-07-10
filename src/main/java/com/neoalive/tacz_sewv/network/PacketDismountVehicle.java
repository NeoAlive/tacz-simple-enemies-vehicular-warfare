package com.neoalive.tacz_sewv.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class PacketDismountVehicle {

    private final List<Integer> unitIds;

    public PacketDismountVehicle(List<Integer> unitIds) {
        this.unitIds = unitIds;
    }

    public PacketDismountVehicle(FriendlyByteBuf buf) {
        int size = buf.readInt();
        this.unitIds = new ArrayList<>();
        for (int i = 0; i < size; i++) this.unitIds.add(buf.readInt());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeInt(this.unitIds.size());
        for (int id : this.unitIds) buf.writeInt(id);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            Player player = ctx.get().getSender();
            if (player == null) return;

            for (int unitId : this.unitIds) {
                Entity e = player.level().getEntity(unitId);
                if (e instanceof PmcUnitEntity pmc && pmc.isOwnedBy(player)) {
                    if (pmc.getVehicle() != null) {
                        pmc.stopRiding(); // vanilla dismount
                    }
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }
}