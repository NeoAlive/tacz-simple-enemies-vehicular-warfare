package com.neoalive.tacz_sewv.client.skin;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.crew.LogoPoolIndex;
import com.neoalive.tacz_sewv.crew.PmcIdentityPreference;

/**
 * Client-side dynamic textures for PMC logo pools under {@code config/tacz_sewv/logo_pools/}.
 */
@OnlyIn(Dist.CLIENT)
public final class LogoPoolRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String LOG_PREFIX = "[sewv-logo-pools]";

    private static final Map<String, Map<String, ResourceLocation>> TEXTURES = new HashMap<>();
    private static final List<ResourceLocation> REGISTERED = new ArrayList<>();

    private LogoPoolRegistry() {
    }

    public static synchronized void reload(ResourceManager resources) {
        TextureManager textures = Minecraft.getInstance().getTextureManager();
        for (ResourceLocation id : REGISTERED) {
            textures.release(id);
        }
        REGISTERED.clear();
        TEXTURES.clear();

        LogoPoolIndex.reload(resources);

        for (String poolId : LogoPoolIndex.poolIds()) {
            Map<String, ResourceLocation> icons = new HashMap<>();
            for (String iconId : LogoPoolIndex.iconsIn(poolId)) {
                ResourceLocation id = registerIcon(poolId, iconId);
                if (id != null) {
                    icons.put(iconId, id);
                }
            }
            if (!icons.isEmpty()) {
                TEXTURES.put(poolId, Collections.unmodifiableMap(icons));
            }
        }
        LOGGER.info("{} registered {} logo pool(s)", LOG_PREFIX, TEXTURES.size());
    }

    public static synchronized void resetToDefaults(ResourceManager resources) {
        Path root = LogoPoolIndex.rootDirectory();
        if (Files.isDirectory(root)) {
            try (var walk = Files.walk(root)) {
                walk.filter(p -> p.getFileName().toString().endsWith(".png")).forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (Exception e) {
                        LOGGER.warn("{} could not delete {}: {}", LOG_PREFIX, p, e.toString());
                    }
                });
            } catch (Exception e) {
                LOGGER.warn("{} could not wipe {}: {}", LOG_PREFIX, root, e.toString());
            }
        }
        reload(resources);
    }

    public static List<String> poolIds() {
        return LogoPoolIndex.poolIds();
    }

    public static List<String> iconsIn(String poolId) {
        return LogoPoolIndex.iconsIn(poolId);
    }

    @Nullable
    public static ResourceLocation texture(String poolId, String iconId) {
        Map<String, ResourceLocation> pool = TEXTURES.get(poolId);
        return pool == null ? null : pool.get(iconId);
    }

    @Nullable
    private static ResourceLocation registerIcon(String poolId, String iconId) {
        Path file = LogoPoolIndex.iconFile(poolId, iconId);
        if (!Files.isRegularFile(file)) return null;
        try (InputStream in = Files.newInputStream(file)) {
            NativeImage image = NativeImage.read(in);
            ResourceLocation id = new ResourceLocation(
                    TaczSewv.MODID, "dynamic/logo_pools/" + poolId + "/" + iconId);
            DynamicTexture tex = new DynamicTexture(image);
            Minecraft.getInstance().getTextureManager().register(id, tex);
            REGISTERED.add(id);
            return id;
        } catch (Exception e) {
            LOGGER.warn("{} could not load {}: {}", LOG_PREFIX, file, e.toString());
            return null;
        }
    }

    /** First icon in the default pool, for empty selection fallback. */
    @Nullable
    public static ResourceLocation defaultTexture() {
        return texture(PmcIdentityPreference.DEFAULT_POOL, PmcIdentityPreference.DEFAULT_LOGO);
    }
}
