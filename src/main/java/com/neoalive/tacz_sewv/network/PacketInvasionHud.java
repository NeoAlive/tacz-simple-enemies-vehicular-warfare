package com.neoalive.tacz_sewv.network;

import com.neoalive.tacz_sewv.client.InvasionHudClient;
import com.neoalive.tacz_sewv.invasion.InvasionHud;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * S→C invasion match HUD. {@code clear=true} tears the widget down; otherwise a full
 * layout+state snapshot (layout is tiny and fixed for the session).
 */
public class PacketInvasionHud {

    private final boolean clear;
    private final InvasionHud.Snapshot snapshot;

    public static PacketInvasionHud clearPacket() {
        return new PacketInvasionHud(true, null);
    }

    public static PacketInvasionHud snapshot(InvasionHud.Snapshot snapshot) {
        return new PacketInvasionHud(false, snapshot);
    }

    private PacketInvasionHud(boolean clear, InvasionHud.Snapshot snapshot) {
        this.clear = clear;
        this.snapshot = snapshot;
    }

    public PacketInvasionHud(FriendlyByteBuf buf) {
        this.clear = buf.readBoolean();
        this.snapshot = this.clear ? null : InvasionHud.Snapshot.decode(buf);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(this.clear);
        if (!this.clear && this.snapshot != null) {
            this.snapshot.encode(buf);
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            if (this.clear || this.snapshot == null) {
                InvasionHudClient.clear();
            } else {
                InvasionHudClient.accept(this.snapshot);
            }
        }));
        ctx.get().setPacketHandled(true);
    }
}
