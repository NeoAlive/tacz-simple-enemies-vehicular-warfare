package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.client.TdtScreen;
import com.neoalive.tacz_sewv.crew.PmcIdentityPreference;

/** S→C: saved PMC identity and name-pool category for the TDT Identity tab. */
public final class PacketSyncPmcIdentity {

    private final String companyName;
    private final String logoPool;
    private final String logoId;
    private final String nameCategory;

    public PacketSyncPmcIdentity(PmcIdentityPreference.PmcIdentity identity, String nameCategory) {
        this.companyName = identity.companyName();
        this.logoPool = identity.logoPool();
        this.logoId = identity.logoId();
        this.nameCategory = nameCategory;
    }

    public PacketSyncPmcIdentity(FriendlyByteBuf buf) {
        this.companyName = buf.readUtf();
        this.logoPool = buf.readUtf();
        this.logoId = buf.readUtf();
        this.nameCategory = buf.readUtf();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(this.companyName);
        buf.writeUtf(this.logoPool);
        buf.writeUtf(this.logoId);
        buf.writeUtf(this.nameCategory);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            TdtScreen.receiveIdentitySync(this.companyName, this.logoPool, this.logoId, this.nameCategory);
            if (Minecraft.getInstance().screen instanceof TdtScreen screen) {
                screen.onIdentitySynced();
            }
        }));
        ctx.get().setPacketHandled(true);
    }
}
