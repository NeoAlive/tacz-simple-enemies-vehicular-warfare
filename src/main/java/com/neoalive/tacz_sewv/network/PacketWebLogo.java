package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.neoforged.api.distmarker.Dist;

import com.neoalive.tacz_sewv.client.skin.WebLogoTextures;
import com.neoalive.tacz_sewv.platform.DistExecutor;
import com.neoalive.tacz_sewv.platform.NetworkEvent;
import com.neoalive.tacz_sewv.skin.WebLogoCache;

/** Server to client: a downloaded web logo as PNG bytes, or why the link failed. */
public final class PacketWebLogo {

    private static final int MAX_PNG = 64 * 1024;

    private final String url;
    private final String id;
    private final String error;
    private final byte[] png;

    private PacketWebLogo(String url, String id, String error, byte[] png) {
        this.url = url;
        this.id = id;
        this.error = error;
        this.png = png;
    }

    public static PacketWebLogo ok(String url, String id, byte[] png) {
        return new PacketWebLogo(url, id, "", png);
    }

    public static PacketWebLogo error(String url, String reason) {
        return new PacketWebLogo(url, "", reason, new byte[0]);
    }

    public PacketWebLogo(FriendlyByteBuf buf) {
        this.url = buf.readUtf(WebLogoCache.MAX_URL * 4);
        this.id = buf.readUtf(32);
        this.error = buf.readUtf(32);
        this.png = buf.readByteArray(MAX_PNG);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(this.url.length() > WebLogoCache.MAX_URL ? this.url.substring(0, WebLogoCache.MAX_URL) : this.url,
                WebLogoCache.MAX_URL * 4);
        buf.writeUtf(this.id, 32);
        buf.writeUtf(this.error, 32);
        buf.writeByteArray(this.png);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> WebLogoTextures.receive(this.url, this.id, this.error, this.png)));
        ctx.get().setPacketHandled(true);
    }
}
