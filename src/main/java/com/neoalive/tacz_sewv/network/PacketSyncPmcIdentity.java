package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.neoforged.api.distmarker.Dist;

import com.neoalive.tacz_sewv.client.TdtScreen;
import com.neoalive.tacz_sewv.crew.PmcIdentityPreference;
import com.neoalive.tacz_sewv.platform.DistExecutor;
import com.neoalive.tacz_sewv.platform.NetworkEvent;
import com.neoalive.tacz_sewv.skin.WebLogoCache;

/** Server to client: the saved terminal identity, name pool and crew language. */
public final class PacketSyncPmcIdentity {

    private final String companyName;
    private final String logoPool;
    private final String logoId;
    private final String logoUrl;
    private final String nameCategory;
    private final String voice;

    public PacketSyncPmcIdentity(PmcIdentityPreference.PmcIdentity identity, String nameCategory, String voice) {
        this.companyName = identity.companyName();
        this.logoPool = identity.logoPool();
        this.logoId = identity.logoId();
        this.logoUrl = identity.logoUrl();
        this.nameCategory = nameCategory;
        this.voice = voice;
    }

    public PacketSyncPmcIdentity(FriendlyByteBuf buf) {
        this.companyName = buf.readUtf();
        this.logoPool = buf.readUtf();
        this.logoId = buf.readUtf();
        this.logoUrl = buf.readUtf(WebLogoCache.MAX_URL * 4);
        this.nameCategory = buf.readUtf();
        this.voice = buf.readUtf(32);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(this.companyName);
        buf.writeUtf(this.logoPool);
        buf.writeUtf(this.logoId);
        buf.writeUtf(this.logoUrl, WebLogoCache.MAX_URL * 4);
        buf.writeUtf(this.nameCategory);
        buf.writeUtf(this.voice, 32);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            TdtScreen.receiveIdentitySync(this.companyName, this.logoPool, this.logoId, this.logoUrl,
                    this.nameCategory, this.voice);
            if (Minecraft.getInstance().screen instanceof TdtScreen screen) {
                screen.onIdentitySynced();
            }
        }));
        ctx.get().setPacketHandled(true);
    }
}
