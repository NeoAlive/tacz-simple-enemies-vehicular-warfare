package com.neoalive.tacz_sewv.network;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.FriendlyByteBuf;

import com.neoalive.tacz_sewv.loadout.LoadoutMerge;
import com.neoalive.tacz_sewv.loadout.LoadoutRow;

/**
 * Shared codec for the two loadout-editor packets. Caps are enforced on read in both directions
 * (a hostile client must not be able to make the server allocate), and rows travel as their SEM
 * JSON so this class never has to know a key — new keys need no protocol change.
 */
final class LoadoutWire {

    static final int MAX_ROWS = 256;
    static final int MAX_HIDDEN = 1024;
    /** Catalogs are the only big lists: every gun / attachment / armor id the server has loaded. */
    static final int MAX_CATALOG = 16384;
    static final int ID_LEN = 256;
    static final int JSON_LEN = 4096;
    private static final Gson GSON = new Gson();

    private LoadoutWire() {}

    static void writeRow(FriendlyByteBuf buf, LoadoutRow r) {
        buf.writeUtf(r.name.length() > 64 ? r.name.substring(0, 64) : r.name, 64);
        String json = GSON.toJson(r.toJson());
        // A row carrying a huge unknown key must not make encode throw: "{}" reads back as null and is skipped.
        buf.writeUtf(json.length() > JSON_LEN ? "{}" : json, JSON_LEN);
    }

    /** Null when the JSON does not describe a loadout (a required key is missing). */
    static LoadoutRow readRow(FriendlyByteBuf buf) {
        String name = buf.readUtf(64);
        try {
            JsonObject o = JsonParser.parseString(buf.readUtf(JSON_LEN)).getAsJsonObject();
            return LoadoutRow.fromJson(name, o);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static void writeFaction(FriendlyByteBuf buf, LoadoutMerge.Faction f) {
        buf.writeBoolean(f.managed);
        buf.writeBoolean(f.replace);
        buf.writeVarInt(f.rows.size());
        f.rows.forEach(r -> writeRow(buf, r));
        buf.writeVarInt(f.hidden.size());
        f.hidden.forEach(h -> buf.writeUtf(h, ID_LEN));
    }

    static LoadoutMerge.Faction readFaction(FriendlyByteBuf buf) {
        LoadoutMerge.Faction f = new LoadoutMerge.Faction();
        f.managed = buf.readBoolean();
        f.replace = buf.readBoolean();
        int rows = checked(buf.readVarInt(), MAX_ROWS);
        for (int i = 0; i < rows; i++) {
            LoadoutRow r = readRow(buf);
            if (r != null) f.rows.add(r);
        }
        int hidden = checked(buf.readVarInt(), MAX_HIDDEN);
        for (int i = 0; i < hidden; i++) f.hidden.add(buf.readUtf(ID_LEN));
        return f;
    }

    static void writeIds(FriendlyByteBuf buf, List<String> ids) {
        buf.writeVarInt(ids.size());
        ids.forEach(id -> buf.writeUtf(id, ID_LEN));
    }

    static List<String> readIds(FriendlyByteBuf buf) {
        int n = checked(buf.readVarInt(), MAX_CATALOG);
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(buf.readUtf(ID_LEN));
        return out;
    }

    static int checked(int n, int max) {
        if (n < 0 || n > max) throw new IllegalArgumentException("list size out of range: " + n);
        return n;
    }
}
