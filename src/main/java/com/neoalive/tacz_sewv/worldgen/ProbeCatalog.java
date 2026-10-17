package com.neoalive.tacz_sewv.worldgen;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import javax.annotation.Nullable;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.block.SpawnProbeCategory;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;

/**
 * Every structure template, from every mod and datapack, that contains a {@code tacz_sewv:spawn_probe}.
 *
 * <p>Built on server load and on {@code /reload}. It is deliberately paranoid, because it reads
 * files this mod does not own: the decompressed size is capped, a cheap byte search for the probe's
 * registry id runs before any NBT is parsed (most templates never get past it), each file is
 * isolated in its own {@code catch (Throwable)}, and the result replaces the previous catalog only
 * once the whole scan has finished. A bad file costs one log line and nothing else.
 */
public final class ProbeCatalog extends SimplePreparableReloadListener<Map<ResourceLocation, TemplateInfo>> {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_BYTES = 32 << 20;
    private static final String PROBE_ID = "tacz_sewv:spawn_probe";
    private static final byte[] NEEDLE = PROBE_ID.getBytes(StandardCharsets.UTF_8);

    private static volatile Map<ResourceLocation, TemplateInfo> active = Map.of();

    public static Map<ResourceLocation, TemplateInfo> all() {
        return active;
    }

    @Nullable
    public static TemplateInfo get(ResourceLocation id) {
        return active.get(id);
    }

    public static boolean isEmpty() {
        return active.isEmpty();
    }

    @Override
    protected Map<ResourceLocation, TemplateInfo> prepare(ResourceManager manager, ProfilerFiller profiler) {
        Map<ResourceLocation, TemplateInfo> found = new HashMap<>();
        int scanned = 0;
        int failed = 0;
        try {
            for (Map.Entry<ResourceLocation, Resource> e
                    : manager.listResources("structures", rl -> rl.getPath().endsWith(".nbt")).entrySet()) {
                scanned++;
                try {
                    TemplateInfo info = scan(e.getKey(), e.getValue());
                    if (info != null) found.put(info.id(), info);
                } catch (Throwable t) {
                    failed++;
                    LOGGER.warn("[tacz_sewv] structure template {} skipped by the probe scan: {}", e.getKey(), t.toString());
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[tacz_sewv] structure probe scan could not list templates, keeping the previous catalog", t);
            return active;
        }
        LOGGER.info("[tacz_sewv] probe scan: {} structure templates read, {} carry spawn probes, {} unreadable",
                scanned, found.size(), failed);
        return found;
    }

    @Override
    protected void apply(Map<ResourceLocation, TemplateInfo> prepared, ResourceManager manager, ProfilerFiller profiler) {
        active = Map.copyOf(prepared);
    }

    @Nullable
    private static TemplateInfo scan(ResourceLocation file, Resource resource) throws IOException {
        byte[] data;
        try (InputStream in = new GZIPInputStream(resource.open())) {
            data = in.readNBytes(MAX_BYTES + 1);
        }
        if (data.length > MAX_BYTES) throw new IOException("larger than " + MAX_BYTES + " bytes decompressed");
        if (indexOf(data, NEEDLE) < 0) return null;

        CompoundTag root = NbtIo.read(new DataInputStream(new ByteArrayInputStream(data)),
                new NbtAccounter(MAX_BYTES * 2L));
        ListTag palette = root.getList("palette", Tag.TAG_COMPOUND);
        if (palette.isEmpty() && root.contains("palettes", Tag.TAG_LIST)) {
            palette = root.getList("palettes", Tag.TAG_LIST).getList(0);
        }
        Set<Integer> probeStates = new HashSet<>();
        for (int i = 0; i < palette.size(); i++) {
            if (PROBE_ID.equals(palette.getCompound(i).getString("Name"))) probeStates.add(i);
        }
        if (probeStates.isEmpty()) return null; // the id appeared as text only

        int probes = 0;
        int vehicles = 0;
        int chests = 0;
        Set<TankFaction> factions = EnumSet.noneOf(TankFaction.class);
        ListTag blocks = root.getList("blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag block = blocks.getCompound(i);
            CompoundTag nbt = block.getCompound("nbt");
            if (probeStates.contains(block.getInt("state"))) {
                probes++;
                try {
                    factions.add(TankFaction.valueOf(nbt.getString("FactionType")));
                } catch (IllegalArgumentException ignored) {
                    // unknown faction: counted, but it names nobody
                }
                if (SpawnProbeCategory.parse(nbt.getString("Category")) == SpawnProbeCategory.VEHICLE) vehicles++;
            } else if (nbt.contains("LootTable", Tag.TAG_STRING)) {
                chests++;
            }
        }
        String path = file.getPath();
        ResourceLocation id = new ResourceLocation(file.getNamespace(),
                path.substring("structures/".length(), path.length() - ".nbt".length()));
        return new TemplateInfo(id, probes, factions, StructureOwner.tokenOf(id.getPath()), vehicles, chests);
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0, last = hay.length - needle.length; i <= last; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
