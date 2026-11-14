package com.neoalive.tacz_sewv.network;

import java.util.Locale;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.crew.LogoPoolIndex;
import com.neoalive.tacz_sewv.crew.NamePools;
import com.neoalive.tacz_sewv.crew.NamePreference;
import com.neoalive.tacz_sewv.crew.NpcIdentity;
import com.neoalive.tacz_sewv.crew.PmcIdentityPreference;
import com.neoalive.tacz_sewv.crew.PmcVoices;
import com.neoalive.tacz_sewv.crew.VoicePreference;
import com.neoalive.tacz_sewv.skin.HelmetLogoSupport;
import com.neoalive.tacz_sewv.skin.PmcLogoEncoder;
import com.neoalive.tacz_sewv.skin.PmcVehicleLogoSupport;
import com.neoalive.tacz_sewv.skin.WebLogoCache;

/** Client to server: commit everything the terminal edits in one go. */
public final class PacketApplyPmcIdentity {

    private static final int MAX_COMPANY_LEN = 30;

    private final String companyName;
    private final String nameCategory;
    private final String voice;
    private final String logoPool;
    private final String logoId;
    private final String logoUrl;

    public PacketApplyPmcIdentity(String companyName, String nameCategory, String voice,
            String logoPool, String logoId, String logoUrl) {
        this.companyName = companyName;
        this.nameCategory = nameCategory;
        this.voice = voice;
        this.logoPool = logoPool;
        this.logoId = logoId;
        this.logoUrl = logoUrl;
    }

    public PacketApplyPmcIdentity(FriendlyByteBuf buf) {
        this.companyName = buf.readUtf(MAX_COMPANY_LEN * 4);
        this.nameCategory = buf.readUtf(64);
        this.voice = buf.readUtf(32);
        this.logoPool = buf.readUtf(64);
        this.logoId = buf.readUtf(64);
        this.logoUrl = buf.readUtf(WebLogoCache.MAX_URL * 4);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(this.companyName, MAX_COMPANY_LEN * 4);
        buf.writeUtf(this.nameCategory, 64);
        buf.writeUtf(this.voice, 32);
        buf.writeUtf(this.logoPool, 64);
        buf.writeUtf(this.logoId, 64);
        buf.writeUtf(this.logoUrl, WebLogoCache.MAX_URL * 4);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            String pool = this.logoPool.toLowerCase(Locale.ROOT);
            String logo = this.logoId.toLowerCase(Locale.ROOT);
            boolean web = WebLogoCache.POOL.equals(pool);
            String url = web ? this.logoUrl.strip() : "";
            boolean logoOk = pool.startsWith("pmc_")
                    && LogoPoolIndex.isValidPool(pool)
                    && LogoPoolIndex.isValidIcon(pool, logo)
                    && (!web || (WebLogoCache.idFor(url).equals(logo) && WebLogoCache.has(logo)));
            if (!logoOk) {
                NetworkHandler.sendOrderFeedback(player,
                        Component.translatable("message.tacz_sewv.identity.invalid_logo"));
                return;
            }

            PmcIdentityPreference.PmcIdentity before = PmcIdentityPreference.get(player);
            PmcIdentityPreference.PmcIdentity identity =
                    new PmcIdentityPreference.PmcIdentity(sanitizeCompany(this.companyName), pool, logo, url);
            PmcIdentityPreference.set(player, identity);
            NamePreference.set(player, sanitizeCategory(this.nameCategory));
            VoicePreference.set(player, PmcVoices.sanitize(this.voice));

            if (!before.companyName().equals(identity.companyName())) {
                NpcIdentity.refreshCompanyName(player);
            }
            if (!before.logoPool().equals(pool) || !before.logoId().equals(logo)) {
                if (web) WebLogoCache.publish(logo);
                PmcLogoEncoder.invalidateCache();
                HelmetLogoSupport.restampOwned(player);
                PmcVehicleLogoSupport.restampOwned(player);
            }

            NetworkHandler.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new PacketSyncPmcIdentity(identity,
                            NamePreference.get(player, SewvConfig.DEFAULT_NAME_CATEGORY.get()),
                            VoicePreference.get(player)));
            NetworkHandler.sendOrderFeedback(player,
                    Component.translatable("message.tacz_sewv.identity.applied"));
        });
        ctx.get().setPacketHandled(true);
    }

    private static String sanitizeCompany(String raw) {
        if (raw == null) return "";
        String trimmed = raw.strip();
        return trimmed.length() > MAX_COMPANY_LEN ? trimmed.substring(0, MAX_COMPANY_LEN) : trimmed;
    }

    private static String sanitizeCategory(String raw) {
        if (raw == null || raw.isBlank()) return NamePools.RANDOM;
        String key = raw.strip().toUpperCase(Locale.ROOT);
        return NamePools.active().categoryKeys().contains(key) ? key : NamePools.RANDOM;
    }
}
