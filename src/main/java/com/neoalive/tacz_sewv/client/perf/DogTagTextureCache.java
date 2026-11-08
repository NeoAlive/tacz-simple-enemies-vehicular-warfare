package com.neoalive.tacz_sewv.client.perf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.atsuishio.superbwarfare.tools.SpritePixelHelper;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.config.PerfFlags;

/**
 * SBW rebuilds and re-registers a 16x16 dog tag texture on every render call. This keeps the last
 * texture per vehicle and only rebuilds when the synced pattern changes. Render thread only.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class DogTagTextureCache {

    private static final int MAX_ENTRIES = 512;
    private static final Map<String, Entry> ENTRIES = new HashMap<>();

    private DogTagTextureCache() {}

    public static ResourceLocation icon(SpritePixelHelper helper, List<List<Short>> grid, String path) {
        if (!PerfFlags.dogTagIconCache() || !RenderSystem.isOnRenderThread()) {
            return helper.getDogTagIcon(grid, path);
        }
        Entry entry = ENTRIES.get(path);
        if (entry != null && entry.snapshot.equals(grid)) {
            return entry.location;
        }
        ResourceLocation location = helper.getDogTagIcon(grid, path);
        if (entry == null && ENTRIES.size() >= MAX_ENTRIES) {
            clear();
        }
        ENTRIES.put(path, new Entry(snapshot(grid), location));
        return location;
    }

    public static int size() {
        return ENTRIES.size();
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            RenderSystem.recordRenderCall(DogTagTextureCache::clear);
        }
    }

    private static void clear() {
        var textures = Minecraft.getInstance().getTextureManager();
        for (Entry entry : ENTRIES.values()) {
            textures.release(entry.location);
        }
        ENTRIES.clear();
    }

    private static List<List<Short>> snapshot(List<List<Short>> grid) {
        List<List<Short>> copy = new ArrayList<>(grid.size());
        for (List<Short> row : grid) {
            copy.add(new ArrayList<>(row));
        }
        return copy;
    }

    private record Entry(List<List<Short>> snapshot, ResourceLocation location) {}
}
