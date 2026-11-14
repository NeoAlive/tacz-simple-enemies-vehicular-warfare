package com.neoalive.tacz_sewv.client.skin;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.client.TdtScreen;
import com.neoalive.tacz_sewv.skin.WebLogoCache;

/** Textures for web logos the server sent. Dropped on logout. */
@OnlyIn(Dist.CLIENT)
public final class WebLogoTextures {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<String, ResourceLocation> TEXTURES = new HashMap<>();

    private WebLogoTextures() {
    }

    public static void receive(String url, String id, String error, byte[] png) {
        if (error.isEmpty() && WebLogoCache.isId(id) && !TEXTURES.containsKey(id)) {
            try {
                NativeImage image = NativeImage.read(new ByteArrayInputStream(png));
                if (image.getWidth() > WebLogoCache.SIZE || image.getHeight() > WebLogoCache.SIZE) {
                    image.close();
                    error = WebLogoCache.ERR_FORMAT;
                } else {
                    ResourceLocation loc = new ResourceLocation(TaczSewv.MODID, "dynamic/web_logo/" + id);
                    Minecraft.getInstance().getTextureManager().register(loc, new DynamicTexture(image));
                    TEXTURES.put(id, loc);
                    // A helmet composited before the bytes arrived used the fallback logo.
                    HelmetLogoTextures.clear();
                }
            } catch (Exception e) {
                LOGGER.warn("[sewv-web-logo] bad image from server for {}: {}", id, e.toString());
                error = WebLogoCache.ERR_FORMAT;
            }
        }
        TdtScreen.receiveWebLogo(url, error.isEmpty() ? id : "", error);
    }

    @Nullable
    public static ResourceLocation texture(String id) {
        return TEXTURES.get(id);
    }

    public static void clear() {
        for (ResourceLocation loc : TEXTURES.values()) {
            Minecraft.getInstance().getTextureManager().release(loc);
        }
        TEXTURES.clear();
    }
}
