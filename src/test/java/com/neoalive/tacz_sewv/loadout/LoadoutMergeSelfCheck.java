package com.neoalive.tacz_sewv.loadout;

import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Headless check of {@link LoadoutMerge} (run: {@code ./gradlew selfCheckLoadout}, needs {@code -ea}).
 * Covers the four mod combinations by construction: base SEM's file, the Berezka config mod's file
 * (same path, other content), our layer, with and without SEM Extended.
 */
public final class LoadoutMergeSelfCheck {

    private static final String SEM_DEFAULT = "simpleenemymod:ru_units/loadouts";
    private static final String OTHER_PACK = "somepack:ru_units/extra";

    public static void main(String[] args) {
        Map<String, JsonElement> raw = raw();
        String before = raw.toString();

        unmanagedIsUntouched(raw);
        inheritAddKeepsEverythingAndAddsLayerFile(raw);
        hidesRemoveOnlyTheNamedEntry(raw);
        replaceDropsEveryOtherFileOfThatFactionOnly(raw);
        emptyReplaceFallsBack(raw);
        weightIsDuplicatesWithoutExtendedAndAFieldWith(raw);
        unknownKeysSurviveAdopt();
        repeatedMergesNeverMutateTheCache(raw, before);
        System.out.println("LoadoutMergeSelfCheck OK");
    }

    private static Map<String, JsonElement> raw() {
        Map<String, JsonElement> raw = new LinkedHashMap<>();
        raw.put(SEM_DEFAULT, JsonParser.parseString("""
                {"loadouts":{
                  "ak":{"gun_id":"tacz:ak47","ammo_count":30,"fire_mode":"AUTO"},
                  "rpk":{"gun_id":"tacz:rpk","ammo_count":40,"fire_mode":"AUTO","scope_id":"tacz:sight_t2"}}}"""));
        raw.put(OTHER_PACK, JsonParser.parseString("""
                {"loadouts":{"m4":{"gun_id":"tacz:m4a1","ammo_count":30,"fire_mode":"SEMI","weight":3,"stock_id":"tacz:stock_x"}}}"""));
        raw.put("simpleenemymod:us_units/loadouts", JsonParser.parseString("""
                {"loadouts":{"us":{"gun_id":"tacz:m4a1","ammo_count":30,"fire_mode":"SEMI"}}}"""));
        return raw;
    }

    private static LoadoutMerge.Faction layer(boolean replace, String... gunIds) {
        LoadoutMerge.Faction f = new LoadoutMerge.Faction();
        f.managed = true;
        f.replace = replace;
        for (int i = 0; i < gunIds.length; i++) {
            LoadoutRow r = new LoadoutRow();
            r.name = "ours" + i;
            r.gunId = gunIds[i];
            f.rows.add(r);
        }
        return f;
    }

    private static Map<String, JsonElement> run(Map<String, JsonElement> raw, LoadoutMerge.Faction ru, boolean ext) {
        return LoadoutMerge.merge(raw, Map.of("ru_units", ru), ext);
    }

    private static void unmanagedIsUntouched(Map<String, JsonElement> raw) {
        LoadoutMerge.Faction f = layer(true, "tacz:x");
        f.managed = false;
        Map<String, JsonElement> out = run(raw, f, false);
        check(out.equals(raw), "unmanaged faction must be byte-identical");
    }

    private static void inheritAddKeepsEverythingAndAddsLayerFile(Map<String, JsonElement> raw) {
        Map<String, JsonElement> out = run(raw, layer(false, "tacz:x"), false);
        check(out.containsKey(SEM_DEFAULT) && out.containsKey(OTHER_PACK), "inherit keeps other files");
        String layerId = LoadoutMerge.layerFileId("ru_units");
        check(out.containsKey(layerId), "layer file added");
        check(!layerId.endsWith("/loadouts"), "layer file id never collides with loadouts.json");
    }

    private static void hidesRemoveOnlyTheNamedEntry(Map<String, JsonElement> raw) {
        LoadoutMerge.Faction f = layer(false);
        f.hidden.add(LoadoutMerge.hideKey(SEM_DEFAULT, "ak"));
        Map<String, JsonElement> out = run(raw, f, false);
        JsonObject kept = out.get(SEM_DEFAULT).getAsJsonObject().getAsJsonObject("loadouts");
        check(!kept.has("ak") && kept.has("rpk"), "hide removes only the named entry");
        check(out.get(OTHER_PACK) == raw.get(OTHER_PACK), "an untouched file is passed through as-is");
    }

    private static void replaceDropsEveryOtherFileOfThatFactionOnly(Map<String, JsonElement> raw) {
        Map<String, JsonElement> out = run(raw, layer(true, "tacz:x"), false);
        check(!out.containsKey(SEM_DEFAULT) && !out.containsKey(OTHER_PACK), "replace drops other ru files");
        check(out.containsKey("simpleenemymod:us_units/loadouts"), "replace never touches another faction");
    }

    private static void emptyReplaceFallsBack(Map<String, JsonElement> raw) {
        Map<String, JsonElement> out = run(raw, layer(true), false);
        check(out.containsKey(SEM_DEFAULT) && out.containsKey(OTHER_PACK), "empty REPLACE keeps SEM's pool");
        check(!out.containsKey(LoadoutMerge.layerFileId("ru_units")), "no layer file when there are no rows");
    }

    private static void weightIsDuplicatesWithoutExtendedAndAFieldWith(Map<String, JsonElement> raw) {
        LoadoutMerge.Faction f = layer(false, "tacz:x");
        f.rows.get(0).weight = 3;
        String layerId = LoadoutMerge.layerFileId("ru_units");
        JsonObject base = run(raw, f, false).get(layerId).getAsJsonObject().getAsJsonObject("loadouts");
        check(base.size() == 3 && !base.get("ours0#1").getAsJsonObject().has("weight"),
                "no Extended: weight 3 = three entries, no weight key");
        JsonObject ext = run(raw, f, true).get(layerId).getAsJsonObject().getAsJsonObject("loadouts");
        check(ext.size() == 1 && ext.get("ours0").getAsJsonObject().get("weight").getAsInt() == 3,
                "Extended: one entry carrying weight");
    }

    private static void unknownKeysSurviveAdopt() {
        JsonObject o = JsonParser.parseString("""
                {"gun_id":"tacz:ak47","ammo_count":30,"fire_mode":"AUTO","stock_id":"tacz:s","future_key":{"a":1}}""")
                .getAsJsonObject();
        LoadoutRow r = LoadoutRow.fromJson("ak", o);
        check(r != null && "tacz:s".equals(r.ids.get("stock_id")), "known id key modelled");
        JsonObject back = r.toJson();
        check(back.has("future_key") && back.getAsJsonObject("future_key").get("a").getAsInt() == 1,
                "unknown key preserved through adopt");
        check(LoadoutRow.fromJson("bad", JsonParser.parseString("{\"gun_id\":\"x\"}").getAsJsonObject()) == null,
                "row missing required fields is rejected like SEM does");
    }

    private static void repeatedMergesNeverMutateTheCache(Map<String, JsonElement> raw, String before) {
        LoadoutMerge.Faction f = layer(false, "tacz:x");
        f.hidden.add(LoadoutMerge.hideKey(SEM_DEFAULT, "ak"));
        for (int i = 0; i < 3; i++) run(raw, f, i % 2 == 0);
        check(raw.toString().equals(before), "merge must deep-copy: the cached raw map is never mutated");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
