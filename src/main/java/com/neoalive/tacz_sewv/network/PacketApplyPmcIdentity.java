package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import com.neoalive.tacz_sewv.crew.LogoPoolIndex;
import com.neoalive.tacz_sewv.crew.NpcIdentity;
import com.neoalive.tacz_sewv.crew.PmcIdentityPreference;
import com.neoalive.tacz_sewv.skin.PmcVehicleLogoSupport;

/** C→S: commit PMC company name + logo from the TDT Identity tab. */
public final class PacketApplyPmcIdentity {

    private static final int MAX_COMPANY_LEN = 30;

    private final String companyName;
    private final String logoPool;
    private final String logoId;

    public PacketApplyPmcIdentity(String companyName, String logoPool, String logoId) {
        this.companyName = companyName;
        this.logoPool = logoPool;
        this.logoId = logoId;
    }

    public PacketApplyPmcIdentity(FriendlyByteBuf buf) {
        this.companyName = buf.readUtf();
        this.logoPool = buf.readUtf();
        this.logoId = buf.readUtf();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(this.companyName);
        buf.writeUtf(this.logoPool);
        buf.writeUtf(this.logoId);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            String company = sanitizeCompany(this.companyName);
            String pool = this.logoPool.toLowerCase();
            String logo = this.logoId.toLowerCase();

            if (!LogoPoolIndex.isValidPool(pool) || !LogoPoolIndex.isValidIcon(pool, logo)) {
                NetworkHandler.sendOrderFeedback(player,
                        Component.translatable("message.tacz_sewv.identity.invalid_logo"));
                return;
            }

            PmcIdentityPreference.PmcIdentity identity = new PmcIdentityPreference.PmcIdentity(company, pool, logo);
            PmcIdentityPreference.set(player, identity);
            NpcIdentity.refreshCompanyName(player);
            com.neoalive.tacz_sewv.skin.PmcLogoEncoder.invalidateCache();
            PmcVehicleLogoSupport.restampOwned(player);

            NetworkHandler.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new PacketSyncPmcIdentity(identity, com.neoalive.tacz_sewv.crew.NamePreference.get(
                            player, com.neoalive.tacz_sewv.config.SewvConfig.DEFAULT_NAME_CATEGORY.get())));
            NetworkHandler.sendOrderFeedback(player,
                    Component.translatable("message.tacz_sewv.identity.applied"));
        });
        ctx.get().setPacketHandled(true);
    }

    private static String sanitizeCompany(String raw) {
        if (raw == null) return "";
        String trimmed = raw.strip();
        if (trimmed.length() > MAX_COMPANY_LEN) {
            trimmed = trimmed.substring(0, MAX_COMPANY_LEN);
        }
        return trimmed;
    }
}
