package com.neoalive.tacz_sewv.client.skin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import com.neoalive.tacz_sewv.TaczSewv;

/**
 * Serves the crew-skin PNGs from {@code config/tacz_sewv/} as client resources, at
 * {@code tacz_sewv:textures/sewv_skins/<dynamic path>} (see {@link CrewSkinRegistry#asResource}).
 *
 * <p>Our own renderers keep using the {@code DynamicTexture}s. This exists for Coltan: its GemRender
 * importer reads textures through the ResourceManager, and stitching several skins into one variant
 * model (one draw per armor piece) needs every skin readable there. Lookups go to the live registry
 * map on every access, so a {@code /sewv debug reloadSkins} without a resource reload is seen too.
 */
@OnlyIn(Dist.CLIENT)
public final class SkinResourcePack implements PackResources {
    public static final String ID = "tacz_sewv_config_skins";

    /** Always-on, top-of-list, fixed client pack; null if the pack metadata cannot be read. */
    @Nullable
    public static Pack create() {
        return Pack.readMetaAndCreate(ID, Component.literal("SBW: Combined Arms config skins"), true,
                id -> new SkinResourcePack(), PackType.CLIENT_RESOURCES, Pack.Position.TOP,
                PackSource.BUILT_IN);
    }

    @Nullable
    @Override
    public IoSupplier<java.io.InputStream> getRootResource(String... elements) {
        return null;
    }

    @Nullable
    @Override
    public IoSupplier<java.io.InputStream> getResource(PackType type, ResourceLocation location) {
        if (type != PackType.CLIENT_RESOURCES || !TaczSewv.MODID.equals(location.getNamespace())) {
            return null;
        }
        Path file = CrewSkinRegistry.sourceForResource(location.getPath());
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        return () -> Files.newInputStream(file);
    }

    @Override
    public void listResources(PackType type, String namespace, String path, ResourceOutput output) {
        if (type != PackType.CLIENT_RESOURCES || !TaczSewv.MODID.equals(namespace)) {
            return;
        }
        for (String dynamicPath : CrewSkinRegistry.sourcePaths()) {
            String resourcePath = CrewSkinRegistry.RESOURCE_PREFIX + dynamicPath;
            if (!resourcePath.startsWith(path)) {
                continue;
            }
            ResourceLocation location = new ResourceLocation(TaczSewv.MODID, resourcePath);
            IoSupplier<java.io.InputStream> supplier = getResource(type, location);
            if (supplier != null) {
                output.accept(location, supplier);
            }
        }
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        return type == PackType.CLIENT_RESOURCES ? Set.of(TaczSewv.MODID) : Set.of();
    }

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public <T> T getMetadataSection(MetadataSectionSerializer<T> deserializer) {
        if (deserializer == PackMetadataSection.TYPE) {
            return (T) new PackMetadataSection(Component.literal("SBW: Combined Arms config skins"),
                    SharedConstants.getCurrentVersion().getPackVersion(PackType.CLIENT_RESOURCES));
        }
        return null;
    }

    @Override
    public String packId() {
        return ID;
    }

    @Override
    public boolean isBuiltin() {
        return true;
    }

    @Override
    public void close() {
    }
}
