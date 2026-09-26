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
        sbwRowsNeverReachSem(raw);
        sbwShareMatchesWeights(raw);
        replaceWithOnlySbwKeepsSemAndTakesEverySpawn(raw);
        unmanagedOrNoSbwIsEmptyPool(raw);
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
        return LoadoutMerge.merge(raw, Map.of("ru_units", ru), ext, LoadoutMergeSelfCheck::isSbw);
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

    /** Stand-in for the registry test: anything in the superbwarfare namespace is an SBW gun here. */
    private static boolean isSbw(String gunId) {
        return gunId.startsWith("superbwarfare:");
    }

    private static LoadoutMerge.SbwPool pool(Map<String, JsonElement> raw, LoadoutMerge.Faction f, boolean ext) {
        return LoadoutMerge.sbwPool(run(raw, f, ext), "ru_units", f, ext, LoadoutMergeSelfCheck::isSbw);
    }

    private static void sbwRowsNeverReachSem(Map<String, JsonElement> raw) {
        Map<String, JsonElement> out = run(raw, layer(false, "tacz:x", "superbwarfare:ak_47"), false);
        check(!out.toString().contains("superbwarfare:"), "an SBW row must never reach SEM's files");
        check(out.containsKey(LoadoutMerge.layerFileId("ru_units")), "the TACZ row still makes a layer file");
        Map<String, JsonElement> sbwOnly = run(raw, layer(false, "superbwarfare:ak_47"), false);
        check(!sbwOnly.containsKey(LoadoutMerge.layerFileId("ru_units")), "SBW-only inherit: no layer file");
    }

    private static void sbwShareMatchesWeights(Map<String, JsonElement> raw) {
        // Inherit, no Extended: SEM rolls ak, rpk, m4 (1 each) + our TACZ row weight 2 → 2 duplicates = 5.
        LoadoutMerge.Faction f = layer(false, "tacz:x", "superbwarfare:m_60");
        f.rows.get(0).weight = 2;
        f.rows.get(1).weight = 5;
        LoadoutMerge.SbwPool p = pool(raw, f, false);
        check(p.rows().size() == 1 && Math.abs(p.share() - 5.0 / 10.0) < 1e-9, "share = 5 / (5 + 5), got " + p.share());
        // Extended: m4 weighs 3 and our row is one entry of weight 2 → SEM weight 1 + 1 + 3 + 2 = 7.
        LoadoutMerge.SbwPool e = pool(raw, f, true);
        check(Math.abs(e.share() - 5.0 / 12.0) < 1e-9, "Extended share = 5 / (5 + 7), got " + e.share());
        // A hidden inherited entry no longer counts.
        f.hidden.add(LoadoutMerge.hideKey(SEM_DEFAULT, "ak"));
        check(Math.abs(pool(raw, f, false).share() - 5.0 / 9.0) < 1e-9, "hidden entries leave the denominator");
    }

    private static void replaceWithOnlySbwKeepsSemAndTakesEverySpawn(Map<String, JsonElement> raw) {
        LoadoutMerge.Faction f = layer(true, "superbwarfare:ak_47");
        Map<String, JsonElement> out = run(raw, f, false);
        check(out.containsKey(SEM_DEFAULT) && out.containsKey(OTHER_PACK), "SBW-only REPLACE keeps SEM a non-empty pool");
        check(pool(raw, f, false).share() == 1.0, "SBW-only REPLACE: every spawn takes an SBW row");
        LoadoutMerge.Faction mixed = layer(true, "tacz:x", "superbwarfare:ak_47");
        check(!run(raw, mixed, false).containsKey(SEM_DEFAULT), "REPLACE with a TACZ row still drops SEM's files");
        check(Math.abs(pool(raw, mixed, false).share() - 0.5) < 1e-9, "mixed REPLACE: SBW 1 vs our TACZ 1");
    }

    private static void unmanagedOrNoSbwIsEmptyPool(Map<String, JsonElement> raw) {
        LoadoutMerge.Faction off = layer(false, "superbwarfare:ak_47");
        off.managed = false;
        check(pool(raw, off, false) == LoadoutMerge.SbwPool.EMPTY, "unmanaged faction has no SBW pool");
        check(pool(raw, layer(false, "tacz:x"), false) == LoadoutMerge.SbwPool.EMPTY, "no SBW rows, no pool");
        LoadoutMerge.Faction zero = layer(false, "superbwarfare:ak_47");
        zero.rows.get(0).weight = 0;
        check(pool(raw, zero, false) == LoadoutMerge.SbwPool.EMPTY, "weight-0 SBW row is off");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
