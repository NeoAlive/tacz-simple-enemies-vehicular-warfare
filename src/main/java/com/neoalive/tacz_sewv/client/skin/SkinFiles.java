package com.neoalive.tacz_sewv.client.skin;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.TaczSewv;

/**
 * Seeding and wiping for the config skin folders, shared by {@link CrewSkinRegistry} and
 * {@link VehicleSkinRegistry}.
 *
 * <p>The default set is <b>enumerated out of the jar</b> rather than listed in a hardcoded array.
 * Both registries used to carry one and both had drifted from what is actually shipped — the vehicle
 * list named 4 of 20 files, and the armor list named 18 files that no longer existed after the
 * {@code _camo_rng} rename, so seeding was a no-op. Nothing at build time checks such a list;
 * {@code listResources} cannot go stale. It is the same call SEM's unit renderers use to discover
 * their own variant textures.
 */
@OnlyIn(Dist.CLIENT)
final class SkinFiles {

    private static final Logger LOGGER = LogUtils.getLogger();

    private SkinFiles() {
    }

    /**
     * Copy every jar default under {@code assets/tacz_sewv/<defaultsRoot>/} into {@code dir},
     * preserving subfolders. Existing files are never overwritten — {@link #wipe} first if you mean
     * to restore.
     */
    static void seed(Path dir, String defaultsRoot, ResourceManager resources, String logPrefix) {
        int copied = 0;
        Map<ResourceLocation, Resource> found =
                resources.listResources(defaultsRoot, id -> id.getPath().endsWith(".png"));
        for (Map.Entry<ResourceLocation, Resource> entry : found.entrySet()) {
            ResourceLocation id = entry.getKey();
            if (!TaczSewv.MODID.equals(id.getNamespace())) continue;
            String relative = id.getPath().substring(defaultsRoot.length() + 1);
            Path dest = dir.resolve(relative);
            if (Files.exists(dest)) continue;
            try (InputStream in = entry.getValue().open()) {
                Files.createDirectories(dest.getParent());
                Files.copy(in, dest);
                copied++;
            } catch (Exception e) {
                LOGGER.warn("{} could not seed {}: {}", logPrefix, relative, e.toString());
            }
        }
        if (copied > 0) {
            LOGGER.info("{} seeded {} default skin(s) into {}", logPrefix, copied, dir);
        }
    }

    /** Delete every {@code .png} under {@code dir}, leaving the folders. */
    static void wipe(Path dir, String logPrefix) {
        if (!Files.isDirectory(dir)) return;
        List<Path> doomed;
        // Collected before deleting: Files.walk is lazy, so mutating the tree mid-stream is not safe.
        try (Stream<Path> tree = Files.walk(dir)) {
            doomed = tree.filter(p -> p.getFileName().toString().endsWith(".png")).toList();
        } catch (Exception e) {
            LOGGER.warn("{} could not wipe {}: {}", logPrefix, dir, e.toString());
            return;
        }
        for (Path file : doomed) {
            try {
                Files.delete(file);
            } catch (Exception e) {
                LOGGER.warn("{} could not delete {}: {}", logPrefix, file, e.toString());
            }
        }
    }
}
