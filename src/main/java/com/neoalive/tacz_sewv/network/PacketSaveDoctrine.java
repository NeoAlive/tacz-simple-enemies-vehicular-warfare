package com.neoalive.tacz_sewv.network;

import com.neoalive.tacz_sewv.entity.ai.utility.Doctrine;
import com.neoalive.tacz_sewv.entity.ai.utility.PlayerDoctrineData;
import com.neoalive.tacz_sewv.item.DoctrineLedgerItem;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public class PacketSaveDoctrine {

    private final int[] axes;

    public PacketSaveDoctrine(int[] axes) {
        this.axes = axes;
    }

    public PacketSaveDoctrine(FriendlyByteBuf buf) {
        this.axes = buf.readVarIntArray();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarIntArray(this.axes);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            // 1. Validate packet length
            if (this.axes.length != Doctrine.Axis.VALUES.length) {
                return;
            }

            // 2. Validate values within bounds and total allocation equals 20
            int total = 0;
            for (int val : this.axes) {
                if (val < -Doctrine.AXIS_LIMIT || val > Doctrine.AXIS_LIMIT) return; // Invalid value
                total += Math.abs(val);
            }
            if (total != 20) return; // Invalid total points

            // 3. Verify player is holding the ledger
            ItemStack held = player.getItemInHand(InteractionHand.MAIN_HAND);
            if (!(held.getItem() instanceof DoctrineLedgerItem)) {
                held = player.getItemInHand(InteractionHand.OFF_HAND);
                if (!(held.getItem() instanceof DoctrineLedgerItem)) return; // Not holding book
            }

            // 4. Verify book ownership matches player
            UUID ownerUUID = DoctrineLedgerItem.getOwner(held);
            if (ownerUUID == null || !ownerUUID.equals(player.getUUID())) {
                player.displayClientMessage(Component.translatable("message.tacz_sewv.doctrine.not_owner").withStyle(ChatFormatting.RED), true);
                return;
            }

            // 5. Save doctrine
            Doctrine customDoctrine = Doctrine.ofAxes(this.axes);
            PlayerDoctrineData.get(player.level()).setDoctrine(player.getUUID(), customDoctrine);

            // 6. Consume item
            held.shrink(1);
            player.displayClientMessage(Component.translatable("message.tacz_sewv.doctrine.saved").withStyle(ChatFormatting.GREEN), true);
        });
        ctx.get().setPacketHandled(true);
    }
}
