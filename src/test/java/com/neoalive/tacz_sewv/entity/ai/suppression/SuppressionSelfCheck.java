package com.neoalive.tacz_sewv.entity.ai.suppression;

import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;

import com.neoalive.tacz_sewv.entity.ai.suppression.SuppressionTable.Kind;

/**
 * Self-check for the suppression table and the bus's pure maths: falloff shape, segment distance,
 * key mapping, clamps, pack-override semantics and the do-nothing fallback.
 * Run via {@code ./gradlew selfCheckSuppression}.
 */
public final class SuppressionSelfCheck {

    public static void main(String[] args) {
        boolean assertionsOn = false;
        assert assertionsOn = true;
        if (!assertionsOn) throw new IllegalStateException("run with -ea, or this checks nothing");

        falloff();
        segmentDistance();
        keys();
        fallbackIsInert();
        clamps();
        packOverride();
        shippedTable();

        System.out.println("suppression self-check: OK");
    }

    private static void falloff() {
        assert SuppressionBus.falloff(0.0, 4.0) == 1.0;
        assert SuppressionBus.falloff(4.5, 4.0) == 0.0 : "outside the radius";
        assert SuppressionBus.falloff(4.0, 4.0) > 0.0 : "the edge still counts (SEM's floor)";
        assert SuppressionBus.falloff(1.0, 4.0) > SuppressionBus.falloff(3.0, 4.0) : "monotonic";
        assert SuppressionBus.falloff(1.0, 0.0) == 0.0 : "zero radius suppresses nothing";
    }

    private static void segmentDistance() {
        // Point beside the middle of a 10-block segment along X.
        assert near(SuppressionBus.segmentDistanceSq(0, 0, 0, 10, 0, 0, 5, 2, 0), 4.0);
        // Behind the start clamps to the start.
        assert near(SuppressionBus.segmentDistanceSq(0, 0, 0, 10, 0, 0, -3, 0, 4), 25.0);
        // Past the end clamps to the end.
        assert near(SuppressionBus.segmentDistanceSq(0, 0, 0, 10, 0, 0, 13, 0, 0), 9.0);
        // Degenerate segment is a point.
        assert near(SuppressionBus.segmentDistanceSq(1, 1, 1, 1, 1, 1, 1, 4, 1), 9.0);
    }

    private static void keys() {
        for (Kind k : Kind.values()) {
            assert SuppressionTable.byKey(k.name().toLowerCase()) == k : k;
        }
        assert SuppressionTable.byKey("artillery") == null;
    }

    private static void fallbackIsInert() {
        SuppressionTable t = SuppressionTable.fallback();
        for (Kind k : Kind.values()) {
            assert t.row(k).amount() == 0.0f : "fallback must suppress nothing: " + k;
        }
        assert SuppressionTable.parse(Map.of()).row(Kind.MG).amount() == 0.0f : "no files -> fallback";
    }

    private static void clamps() {
        SuppressionTable t = parse("a", "{\"mg\":{\"amount\":2.0,\"radius\":500},"
                + "\"explosion\":{\"amount\":0.2,\"radius\":9}}");
        assert t.row(Kind.MG).amount() == SuppressionTable.MAX_AMOUNT : "amount capped at SEM's per-add cap";
        assert t.row(Kind.MG).radius() <= 32.0 : "radius capped";
        assert t.row(Kind.EXPLOSION).radius() == SuppressionTable.MAX_EXPLOSION_RADIUS_MULT
                : "explosion radius is a blast multiple, capped at Detonate's reach";
        assert t.row(Kind.CANNON).amount() == 0.0f : "an unnamed kind suppresses nothing";
    }

    private static void packOverride() {
        Map<ResourceLocation, JsonElement> files = new HashMap<>();
        files.put(new ResourceLocation("tacz_sewv", "suppression"),
                JsonParser.parseString("{\"mg\":{\"amount\":0.2,\"radius\":4},\"cannon\":{\"amount\":0.3,\"radius\":6}}"));
        files.put(new ResourceLocation("zpack", "suppression"),
                JsonParser.parseString("{\"mg\":{\"amount\":0.1}}"));
        SuppressionTable t = SuppressionTable.parse(files);
        // The later file names mg, so it replaces mg's WHOLE row: its radius is gone, not inherited.
        assert t.row(Kind.MG).amount() == 0.1f;
        assert t.row(Kind.MG).radius() == 0.0 : "a named row is replaced wholesale";
        assert t.row(Kind.CANNON).amount() == 0.3f : "an unnamed row survives";
    }

    /** The shipped file names every kind, and the heavier the weapon the harder it suppresses. */
    private static void shippedTable() {
        JsonElement json;
        try (var in = SuppressionSelfCheck.class.getResourceAsStream(
                "/data/tacz_sewv/sewv/suppression/suppression.json")) {
            if (in == null) throw new AssertionError("shipped suppression.json is not on the classpath");
            json = JsonParser.parseReader(new java.io.InputStreamReader(in));
        } catch (java.io.IOException e) {
            throw new AssertionError("shipped suppression.json unreadable", e);
        }
        for (Map.Entry<String, JsonElement> e : json.getAsJsonObject().entrySet()) {
            assert SuppressionTable.byKey(e.getKey()) != null : "shipped file names an unknown kind: " + e.getKey();
        }
        SuppressionTable t = SuppressionTable.parse(Map.of(new ResourceLocation("tacz_sewv", "suppression"), json));
        for (Kind k : Kind.values()) {
            assert t.row(k).amount() > 0.0f && t.row(k).radius() > 0.0 : "shipped row missing or inert: " + k;
        }
        assert t.row(Kind.SMALL_ARMS).amount() <= t.row(Kind.MG).amount();
        assert t.row(Kind.MG).amount() <= t.row(Kind.CANNON).amount();
        assert t.row(Kind.MG).radius() <= t.row(Kind.CANNON).radius();
    }

    private static SuppressionTable parse(String name, String json) {
        return SuppressionTable.parse(Map.of(new ResourceLocation("tacz_sewv", name), JsonParser.parseString(json)));
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1.0e-9;
    }
}
