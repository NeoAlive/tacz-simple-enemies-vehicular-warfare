package com.neoalive.tacz_sewv.network;

import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.minecraftforge.network.NetworkEvent;
import com.neoalive.tacz_sewv.bridge.IVehicleBoarder;
import com.neoalive.tacz_sewv.config.SewvConfig;

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
                if (wasMounted) dismounted++;
            }
        }

        if (SewvConfig.SHOW_ORDER_FEEDBACK.get()) {
            Component msg;
            if (dismounted == 0) {
                msg = Component.translatable("message.tacz_sewv.dismount.none");
            } else if (dismounted == 1) {
                msg = Component.translatable("message.tacz_sewv.dismount.single", dismounted);
            } else {
                msg = Component.translatable("message.tacz_sewv.dismount.multiple", dismounted);
            }
            player.displayClientMessage(msg.copy().withStyle(ChatFormatting.YELLOW), true);
        }
    });
    ctx.get().setPacketHandled(true);
}
}