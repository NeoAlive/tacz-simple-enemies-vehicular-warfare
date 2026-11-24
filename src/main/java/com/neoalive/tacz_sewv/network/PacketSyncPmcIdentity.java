package com.neoalive.tacz_sewv.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.client.TdtScreen;
import com.neoalive.tacz_sewv.crew.NamePools;
import com.neoalive.tacz_sewv.crew.PmcIdentityPreference;
import com.neoalive.tacz_sewv.skin.WebLogoCache;

/** Server to client: the saved terminal identity, name pool and crew language. */
public final class PacketSyncPmcIdentity {

    private final String companyName;
    private final String logoPool;
    private final String logoId;
    private final String logoUrl;
    private final String nameCategory;
    private final String voice;
    /** The server's name categories: a remote client never loads the datapack that holds them. */
    private final List<String> nameCategories;

    public PacketSyncPmcIdentity(PmcIdentityPreference.PmcIdentity identity, String nameCategory, String voice) {
        this.companyName = identity.companyName();
        this.logoPool = identity.logoPool();
        this.logoId = identity.logoId();
        this.logoUrl = identity.logoUrl();
        this.nameCategory = nameCategory;
        this.voice = voice;
        this.nameCategories = NamePools.active().categoryKeys();
    }

    public PacketSyncPmcIdentity(FriendlyByteBuf buf) {
        this.companyName = buf.readUtf();
        this.logoPool = buf.readUtf();
        this.logoId = buf.readUtf();
        this.logoUrl = buf.readUtf(WebLogoCache.MAX_URL * 4);
        this.nameCategory = buf.readUtf();
        this.voice = buf.readUtf(32);
        int n = Math.min(buf.readVarInt(), 256);
        List<String> keys = new ArrayList<>(n);
        for (int i = 0; i < n; i++) keys.add(buf.readUtf(64));
        this.nameCategories = keys;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(this.companyName);
        buf.writeUtf(this.logoPool);
        buf.writeUtf(this.logoId);
        buf.writeUtf(this.logoUrl, WebLogoCache.MAX_URL * 4);
        buf.writeUtf(this.nameCategory);
        buf.writeUtf(this.voice, 32);
        List<String> keys = this.nameCategories.size() > 256 ? this.nameCategories.subList(0, 256) : this.nameCategories;
        buf.writeVarInt(keys.size());
        for (String k : keys) buf.writeUtf(k.length() > 64 ? k.substring(0, 64) : k, 64);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            TdtScreen.receiveIdentitySync(this.companyName, this.logoPool, this.logoId, this.logoUrl,
                    this.nameCategory, this.voice, this.nameCategories);
            if (Minecraft.getInstance().screen instanceof TdtScreen screen) {
                screen.onIdentitySynced();
            }
        }));
        ctx.get().setPacketHandled(true);
    }
}
