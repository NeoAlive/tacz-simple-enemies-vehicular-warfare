package com.neoalive.tacz_sewv.network;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.client.editor.PoolEditorClient;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;
import com.neoalive.tacz_sewv.util.WorldVehiclePools.Category;

/** Server → client: open the pool editor with the current world snapshot + vehicle catalog. */
public class PacketOpenPoolEditor {

    private final Map<TankFaction, Map<Category, List<String>>> pools;
    private final Map<TankFaction, Map<Category, List<String>>> defaults;
    private final List<String> catalog;

    public PacketOpenPoolEditor(Map<TankFaction, Map<Category, List<String>>> pools,
                                Map<TankFaction, Map<Category, List<String>>> defaults,
                                List<String> catalog) {
        this.pools = pools;
        this.defaults = defaults;
        this.catalog = catalog;
    }

    public PacketOpenPoolEditor(FriendlyByteBuf buf) {
        this.pools = readPools(buf);
        this.defaults = readPools(buf);
        this.catalog = readCatalogList(buf);
    }

    public void encode(FriendlyByteBuf buf) {
        writePools(buf, this.pools);
        writePools(buf, this.defaults);
        writeCatalogList(buf, this.catalog);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> PoolEditorClient.openScreen(this.pools, this.defaults, this.catalog)));
        ctx.get().setPacketHandled(true);
    }

    private static Map<TankFaction, Map<Category, List<String>>> readPools(FriendlyByteBuf buf) {
        Map<TankFaction, Map<Category, List<String>>> map = new EnumMap<>(TankFaction.class);
        for (TankFaction faction : TankFaction.values()) {
            Map<Category, List<String>> byCat = new EnumMap<>(Category.class);
            for (Category cat : Category.values()) {
                byCat.put(cat, readStringList(buf));
            }
            map.put(faction, byCat);
        }
        return map;
    }

    private static void writePools(FriendlyByteBuf buf, Map<TankFaction, Map<Category, List<String>>> map) {
        for (TankFaction faction : TankFaction.values()) {
            for (Category cat : Category.values()) {
                writeStringList(buf, map.get(faction).get(cat));
            }
        }
    }

    /**
     * Cap for pool/cue/armor-<em>selection</em> string lists (authoritative edits).
     * Add-catalogs use {@link #writeCatalogList}/{@link #readCatalogList} instead.
     */
    static final int MAX_STRING_LIST = 512;
    static final int MAX_STRING_LEN = 256;
    /** Absolute read ceiling for add-catalogs (DoS guard). Write uses {@link SewvConfig#EDITOR_CATALOG_MAX}. */
    static final int MAX_CATALOG_HARD = 32768;

    static List<String> readStringList(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        if (n < 0 || n > MAX_STRING_LIST) {
            throw new IllegalArgumentException("string list size out of range: " + n);
        }
        List<String> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) list.add(buf.readUtf(MAX_STRING_LEN));
        return list;
    }

    static void writeStringList(FriendlyByteBuf buf, List<String> list) {
        if (list == null) list = List.of();
        buf.writeVarInt(list.size());
        for (String s : list) buf.writeUtf(s, MAX_STRING_LEN);
    }

    /** How many catalog ids the server will send this open (config, clamped to the hard read max). */
    static int catalogCap() {
        try {
            int configured = SewvConfig.EDITOR_CATALOG_MAX.get();
            if (configured < 64) return 64;
            return Math.min(configured, MAX_CATALOG_HARD);
        } catch (Throwable t) {
            // Config not baked yet (shouldn't happen on a live open) — safe default.
            return 16384;
        }
    }

    /**
     * Add-catalog write: keep the first {@link #catalogCap()} entries, drop the rest.
     * Never throws for size — a huge pack must still open the editor.
     */
    static void writeCatalogList(FriendlyByteBuf buf, List<String> list) {
        if (list == null) list = List.of();
        int cap = catalogCap();
        int n = Math.min(list.size(), cap);
        if (list.size() > n) {
            TaczSewv.LOGGER.warn(
                    "[sewv] Truncating editor catalog from {} to {} ids (editorCatalogMax); raise the config if autofill is missing entries",
                    list.size(), n);
        }
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) buf.writeUtf(list.get(i), MAX_STRING_LEN);
    }

    static List<String> readCatalogList(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        if (n < 0 || n > MAX_CATALOG_HARD) {
            throw new IllegalArgumentException("catalog list size out of range: " + n);
        }
        List<String> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) list.add(buf.readUtf(MAX_STRING_LEN));
        return list;
    }
}
