package com.neoalive.tacz_sewv.network;

import java.util.Map;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.client.ConfigUIClient;
import com.neoalive.tacz_sewv.config.ConfigWireCodec;

/** Server → client: open Combined Arms Configuration with an optional server snapshot. */
public class PacketOpenConfigUI {

    private final boolean canEditServer;
    private final Map<Integer, Object> serverSnapshot;

    public PacketOpenConfigUI(boolean canEditServer, Map<Integer, Object> serverSnapshot) {
        this.canEditServer = canEditServer;
        this.serverSnapshot = serverSnapshot;
    }

    public PacketOpenConfigUI(FriendlyByteBuf buf) {
        this.canEditServer = buf.readBoolean();
        this.serverSnapshot = ConfigWireCodec.readSnapshot(buf);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(this.canEditServer);
        ConfigWireCodec.writeSnapshot(buf, this.serverSnapshot);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ConfigUIClient.open(this.canEditServer, this.serverSnapshot)));
        ctx.get().setPacketHandled(true);
    }
}
