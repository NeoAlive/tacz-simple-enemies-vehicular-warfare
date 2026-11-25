package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import com.neoalive.tacz_sewv.entity.ai.utility.Doctrine;
import com.neoalive.tacz_sewv.entity.ai.utility.PlayerDoctrineData;
import com.neoalive.tacz_sewv.item.DoctrineLedgerItem;
import com.neoalive.tacz_sewv.platform.NetworkEvent;

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
                player.displayClientMessage(Component.translatable("message.tacz_sewv.doctrine.bad_packet")
                        .withStyle(ChatFormatting.RED), true);
                return;
            }

            // 2. Validate values within bounds and total allocation equals 20
            int total = 0;
            for (int val : this.axes) {
                if (val < -Doctrine.AXIS_LIMIT || val > Doctrine.AXIS_LIMIT) {
                    player.displayClientMessage(Component.translatable("message.tacz_sewv.doctrine.bad_packet")
                            .withStyle(ChatFormatting.RED), true);
                    return;
                }
                total += Math.abs(val);
            }
            PlayerDoctrineData budgetData = PlayerDoctrineData.get(player.level());
            int budget = budgetData.budget(player.getUUID());
            if (!PlayerDoctrineData.validTotal(total, budget)) {
                player.displayClientMessage(Component.translatable("message.tacz_sewv.doctrine.bad_total",
                        Math.min(PlayerDoctrineData.BASE_BUDGET, budget), budget)
                        .withStyle(ChatFormatting.RED), true);
                return;
            }

            // 3. Verify player is holding the ledger
            ItemStack held = player.getItemInHand(InteractionHand.MAIN_HAND);
            if (!(held.getItem() instanceof DoctrineLedgerItem)) {
                held = player.getItemInHand(InteractionHand.OFF_HAND);
                if (!(held.getItem() instanceof DoctrineLedgerItem)) {
                    player.displayClientMessage(Component.translatable("message.tacz_sewv.doctrine.need_ledger")
                            .withStyle(ChatFormatting.RED), true);
                    return;
                }
            }

            // 4. Save. The ledger is kept: a doctrine can be revised at any time with the same book. Old ledgers
            // still carry the retired sewv_is_initial tag; it is ignored.
            budgetData.setDoctrine(player.getUUID(), Doctrine.ofAxes(this.axes));
            player.displayClientMessage(Component.translatable("message.tacz_sewv.doctrine.saved").withStyle(ChatFormatting.GREEN), true);
            PacketDoctrineSync.sendTo(player);
        });
        ctx.get().setPacketHandled(true);
    }
}
