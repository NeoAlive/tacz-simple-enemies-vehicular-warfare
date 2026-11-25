package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;

import com.neoalive.tacz_sewv.client.DoctrineClient;
import com.neoalive.tacz_sewv.entity.ai.utility.Doctrine;
import com.neoalive.tacz_sewv.entity.ai.utility.PlayerDoctrineData;
import com.neoalive.tacz_sewv.platform.DistExecutor;
import com.neoalive.tacz_sewv.platform.NetworkEvent;
import com.neoalive.tacz_sewv.platform.PacketDistributor;

/**
 * Server to client: the player's saved doctrine and point budget, so the ledger opens on what is actually active.
 * Sent on login, after a save and after an operator edit. Display only; the server re-validates every save.
 */
public class PacketDoctrineSync {

    private final boolean has;
    private final int[] axes;
    private final int budget;

    public PacketDoctrineSync(boolean has, int[] axes, int budget) {
        this.has = has;
        this.axes = axes;
        this.budget = budget;
    }

    public PacketDoctrineSync(FriendlyByteBuf buf) {
        this.has = buf.readBoolean();
        int[] read = buf.readVarIntArray(Doctrine.Axis.VALUES.length);
        this.axes = new int[Doctrine.Axis.VALUES.length];
        System.arraycopy(read, 0, this.axes, 0, Math.min(read.length, this.axes.length));
        this.budget = buf.readVarInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(this.has);
        buf.writeVarIntArray(this.axes);
        buf.writeVarInt(this.budget);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> DoctrineClient.accept(this.has, this.axes, this.budget)));
        ctx.get().setPacketHandled(true);
    }

    /** The player's current doctrine (or none) and budget. */
    public static void sendTo(ServerPlayer player) {
        PlayerDoctrineData data = PlayerDoctrineData.get(player.level());
        Doctrine doctrine = data.getDoctrine(player.getUUID());
        int[] axes = new int[Doctrine.Axis.VALUES.length];
        if (doctrine != null) {
            for (Doctrine.Axis axis : Doctrine.Axis.VALUES) axes[axis.ordinal()] = doctrine.raw(axis);
        }
        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new PacketDoctrineSync(doctrine != null, axes, data.budget(player.getUUID())));
    }
}
