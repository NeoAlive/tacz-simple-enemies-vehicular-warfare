package com.neoalive.tacz_sewv.crew;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.TaczSewv;

/**
 * Server-side index of {@code config/tacz_sewv/logo_pools/pmc_*} folders. Used to validate TDT
 * Apply requests and to locate PNGs for dogTag encoding — no GL, filenames only.
 */
public final class LogoPoolIndex {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String LOG_PREFIX = "[sewv-logo-pools]";
    private static final String DEFAULTS_ROOT = "logo_pools_defaults";
    private static final Pattern POOL_ID = Pattern.compile("^pmc_[a-z0-9_]+$");

    private static volatile Map<String, List<String>> pools = Map.of(
            PmcIdentityPreference.DEFAULT_POOL,
            List.of("pmc_1", "pmc_2", "pmc_3"));
    @Nullable
    private static volatile ResourceManager resources;

    private LogoPoolIndex() {
    }

    public static Path rootDirectory() {
        return FMLPaths.CONFIGDIR.get().resolve(TaczSewv.MODID).resolve("logo_pools");
    }

    public static Path iconFile(String poolId, String iconId) {
        return rootDirectory().resolve(poolId).resolve(iconId + ".png");
    }

    public static List<String> poolIds() {
        return new ArrayList<>(pools.keySet());
    }

    public static List<String> iconsIn(String poolId) {
        return pools.getOrDefault(poolId, List.of());
    }

    public static boolean isValidPool(String poolId) {
        return pools.containsKey(poolId);
    }

    public static boolean isValidIcon(String poolId, String iconId) {
        List<String> icons = pools.get(poolId);
        return icons != null && icons.contains(iconId);
    }

    /** Seeds missing defaults from the jar, then rescans every {@code pmc_*} subfolder. */
    public static synchronized void reload(@Nullable ResourceManager manager) {
        resources = manager;
        Path root = rootDirectory();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            LOGGER.warn("{} could not create {}: {}", LOG_PREFIX, root, e.toString());
        }

        if (manager != null) {
            seedDefaults(root, manager);
        }

        Map<String, List<String>> next = new HashMap<>();
        if (Files.isDirectory(root)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
                for (Path sub : stream) {
                    if (!Files.isDirectory(sub)) continue;
                    String poolId = sub.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (!POOL_ID.matcher(poolId).matches()) continue;
                    List<String> icons = scanIcons(sub);
                    if (!icons.isEmpty()) {
                        next.put(poolId, icons);
                    }
                }
            } catch (IOException e) {
                LOGGER.warn("{} could not list {}: {}", LOG_PREFIX, root, e.toString());
            }
        }

        if (next.isEmpty()) {
            next.put(PmcIdentityPreference.DEFAULT_POOL, List.of("pmc_1", "pmc_2", "pmc_3"));
        }
        pools = Collections.unmodifiableMap(next);
        LOGGER.info("{} indexed {} logo pool(s)", LOG_PREFIX, pools.size());
    }

    private static List<String> scanIcons(Path poolDir) {
        List<String> icons = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(poolDir, "*.png")) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                icons.add(name.substring(0, name.length() - 4).toLowerCase(Locale.ROOT));
            }
        } catch (IOException e) {
            LOGGER.warn("{} could not list {}: {}", LOG_PREFIX, poolDir, e.toString());
        }
        Collections.sort(icons);
        return icons;
    }

    private static void seedDefaults(Path dir, ResourceManager resources) {
        int copied = 0;
        Map<ResourceLocation, Resource> found =
                resources.listResources(DEFAULTS_ROOT, id -> id.getPath().endsWith(".png"));
        for (Entry<ResourceLocation, Resource> entry : found.entrySet()) {
            ResourceLocation id = entry.getKey();
            if (!TaczSewv.MODID.equals(id.getNamespace())) continue;
            String relative = id.getPath().substring(DEFAULTS_ROOT.length() + 1);
            Path dest = dir.resolve(relative);
            if (Files.exists(dest)) continue;
            try (InputStream in = entry.getValue().open()) {
                Files.createDirectories(dest.getParent());
                Files.copy(in, dest);
                copied++;
            } catch (IOException e) {
                LOGGER.warn("{} could not seed {}: {}", LOG_PREFIX, relative, e.toString());
            }
        }
        if (copied > 0) {
            LOGGER.info("{} seeded {} default logo(s) into {}", LOG_PREFIX, copied, dir);
        }
    }

    /** Opens a logo PNG for dogTag encoding. Caller closes the stream. */
    @Nullable
    public static InputStream openIcon(String poolId, String iconId) {
        Path file = iconFile(poolId, iconId);
        if (Files.isRegularFile(file)) {
            try {
                return Files.newInputStream(file);
            } catch (IOException e) {
                LOGGER.warn("{} could not read {}: {}", LOG_PREFIX, file, e.toString());
            }
        }
        ResourceLocation id = new ResourceLocation(
                TaczSewv.MODID, DEFAULTS_ROOT + "/" + poolId + "/" + iconId + ".png");
        ResourceManager manager = resources;
        if (manager != null) {
            try {
                return manager.getResource(id).orElseThrow().open();
            } catch (Exception e) {
                LOGGER.debug("{} jar resource {} unavailable: {}", LOG_PREFIX, id, e.toString());
            }
        }
        String classpath = "/assets/" + TaczSewv.MODID + "/" + DEFAULTS_ROOT + "/"
                + poolId + "/" + iconId + ".png";
        InputStream in = LogoPoolIndex.class.getResourceAsStream(classpath);
        if (in == null) {
            LOGGER.warn("{} no logo PNG for {}/{} (disk or jar)", LOG_PREFIX, poolId, iconId);
        }
        return in;
    }

    /** Rescans logo pools on server reload (/reload). */
    public static final class Loader extends SimplePreparableReloadListener<Void> {
        @Override
        protected Void prepare(ResourceManager manager, ProfilerFiller profiler) {
            return null;
        }

        @Override
        protected void apply(Void prepared, ResourceManager manager, ProfilerFiller profiler) {
            LogoPoolIndex.reload(manager);
            com.neoalive.tacz_sewv.skin.PmcLogoEncoder.invalidateCache();
        }
    }
}
