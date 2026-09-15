package com.neoalive.tacz_sewv.client.skin;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.client.TdtScreen;
import com.neoalive.tacz_sewv.config.ClientConfig;
import com.neoalive.tacz_sewv.crew.PmcIdentityPreference;
import com.neoalive.tacz_sewv.skin.HelmetLogoSupport;

/**
 * Composites a PMC logo into the DogTag UV rect of a mich helmet texture,
 * optionally with a low-contrast grayscale pass on the DogTag UV (alpha preserved).
 */
@OnlyIn(Dist.CLIENT)
public final class HelmetLogoTextures {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<String, ResourceLocation> CACHE = new HashMap<>();

    /** Mid-grey pivot and contrast fraction (2/5) for the desaturate pass. */
    private static final int GRAY_MID = 0x8A;
    private static final int GRAY_CONTRAST_NUM = 2;
    private static final int GRAY_CONTRAST_DEN = 5;

    private HelmetLogoTextures() {
    }

    public static void clear() {
        TextureManager textures = Minecraft.getInstance().getTextureManager();
        for (ResourceLocation id : CACHE.values()) {
            textures.release(id);
        }
        CACHE.clear();
    }

    @Nullable
    public static ResourceLocation apply(ResourceLocation base, LivingEntity wearer, ItemStack stack) {
        if (base == null || !HelmetLogoSupport.isMich(stack)) return base;

        boolean grayscale = ClientConfig.flag(ClientConfig.HELMET_GRAYSCALE);

        String pool = HelmetLogoSupport.poolOf(stack);
        String logo = HelmetLogoSupport.logoOf(stack);
        if (pool == null || logo == null) {
            PmcIdentityPreference.PmcIdentity identity = clientIdentity(wearer);
            pool = identity.logoPool();
            logo = identity.logoId();
        }

        ResourceLocation logoTex = LogoPoolRegistry.texture(pool, logo);
        if (logoTex == null) {
            logoTex = LogoPoolRegistry.defaultTexture();
        }
        boolean stamp = logoTex != null;
        if (!stamp && !grayscale) return base;

        String key = base + "|" + (stamp ? pool + "/" + logo : "nologo") + "|g" + (grayscale ? 1 : 0);
        ResourceLocation cached = CACHE.get(key);
        if (cached != null) return cached;

        try {
            NativeImage helmet = copyPixels(base);
            if (helmet == null) return base;
            try {
                if (stamp) {
                    NativeImage mark = copyPixels(logoTex);
                    if (mark != null) {
                        try {
                            blitLogo(helmet, mark);
                        } finally {
                            mark.close();
                        }
                    }
                }
                if (grayscale) {
                    desaturateDogTag(helmet);
                }
                ResourceLocation id = new ResourceLocation(TaczSewv.MODID,
                        "dynamic/helmet_logo/" + Integer.toHexString(key.hashCode()));
                Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(helmet));
                CACHE.put(key, id);
                return id;
            } catch (Exception e) {
                helmet.close();
                throw e;
            }
        } catch (Exception e) {
            LOGGER.warn("[sewv-helmet-logo] composite failed for {}: {}", key, e.toString());
            return base;
        }
    }

    /** Low-contrast grey on the DogTag UV only; alpha unchanged. */
    private static void desaturateDogTag(NativeImage img) {
        int x1 = Math.min(HelmetLogoSupport.UV_X + HelmetLogoSupport.UV_W, img.getWidth());
        int y1 = Math.min(HelmetLogoSupport.UV_Y + HelmetLogoSupport.UV_H, img.getHeight());
        for (int y = HelmetLogoSupport.UV_Y; y < y1; y++) {
            for (int x = HelmetLogoSupport.UV_X; x < x1; x++) {
                int c = img.getPixelRGBA(x, y);
                int a = (c >>> 24) & 0xFF;
                if (a == 0) continue;
                int b = (c >>> 16) & 0xFF;
                int g = (c >>> 8) & 0xFF;
                int r = c & 0xFF;
                int lum = (r * 77 + g * 150 + b * 29) >> 8;
                int grey = GRAY_MID + ((lum - GRAY_MID) * GRAY_CONTRAST_NUM) / GRAY_CONTRAST_DEN;
                if (grey < 0) grey = 0;
                else if (grey > 255) grey = 255;
                img.setPixelRGBA(x, y, (a << 24) | (grey << 16) | (grey << 8) | grey);
            }
        }
    }

    private static PmcIdentityPreference.PmcIdentity clientIdentity(LivingEntity wearer) {
        Player local = Minecraft.getInstance().player;
        if (local == null) return PmcIdentityPreference.PmcIdentity.defaults();
        if (wearer == local) {
            return new PmcIdentityPreference.PmcIdentity(
                    "", clientPool(), clientLogo());
        }
        if (wearer instanceof PmcUnitEntity pmc && local.getUUID().equals(pmc.getOwnerUUID())) {
            return new PmcIdentityPreference.PmcIdentity(
                    "", clientPool(), clientLogo());
        }
        return PmcIdentityPreference.PmcIdentity.defaults();
    }

    /** Mirrored from {@link TdtScreen}'s last synced identity. */
    private static String clientPool() {
        return TdtScreen.savedLogoPool();
    }

    private static String clientLogo() {
        return TdtScreen.savedLogoId();
    }

    private static void blitLogo(NativeImage dest, NativeImage logo) {
        int dw = HelmetLogoSupport.UV_W;
        int dh = HelmetLogoSupport.UV_H;
        int dx0 = HelmetLogoSupport.UV_X;
        int dy0 = HelmetLogoSupport.UV_Y;
        int sw = logo.getWidth();
        int sh = logo.getHeight();
        if (sw <= 0 || sh <= 0) return;

        for (int y = 0; y < dh; y++) {
            for (int x = 0; x < dw; x++) {
                int sx = x * sw / dw;
                int sy = y * sh / dh;
                int abgr = logo.getPixelRGBA(sx, sy);
                int a = (abgr >>> 24) & 0xFF;
                if (a < 16) continue;
                int dx = dx0 + x;
                int dy = dy0 + y;
                if (dx < 0 || dy < 0 || dx >= dest.getWidth() || dy >= dest.getHeight()) continue;
                dest.setPixelRGBA(dx, dy, abgr);
            }
        }
    }

    @Nullable
    private static NativeImage copyPixels(ResourceLocation loc) {
        TextureManager tm = Minecraft.getInstance().getTextureManager();
        AbstractTexture tex = tm.getTexture(loc);
        if (tex instanceof DynamicTexture dyn && dyn.getPixels() != null) {
            NativeImage src = dyn.getPixels();
            NativeImage copy = new NativeImage(src.getWidth(), src.getHeight(), false);
            copy.copyFrom(src);
            return copy;
        }
        try {
            Resource res = Minecraft.getInstance().getResourceManager().getResource(loc).orElse(null);
            if (res == null) return null;
            try (InputStream in = res.open()) {
                NativeImage src = NativeImage.read(in);
                NativeImage copy = new NativeImage(src.getWidth(), src.getHeight(), false);
                copy.copyFrom(src);
                src.close();
                return copy;
            }
        } catch (Exception e) {
            LOGGER.debug("[sewv-helmet-logo] could not read {}: {}", loc, e.toString());
            return null;
        }
    }
}
