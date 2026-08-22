package com.neoalive.tacz_sewv.ballistics;

import java.util.Map;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import com.neoalive.tacz_sewv.ballistics.BallisticClassifier.Category;

/**
 * Self-check for the TaCZ-to-SBW ballistic translation layer. Run via {@code ./gradlew selfCheck}.
 *
 * <p>Plain {@code main} + {@code assert}, same harness shape as {@code VehicleWeaponsClassifySelfCheck}.
 * Runs with no live Minecraft registries (no bootstrap in this JVM), so it covers the pure classifier
 * and the datapack table parser directly; live {@code DamageSource}/registry routing (which of
 * {@code gunfire}/{@code gunfire_absolute}/{@code projectile_hit}/{@code custom_explosion} a hit
 * actually lands on) needs a running game and is documented in the plan's in-game verification
 * matrix instead, not asserted here.
 */
public final class BallisticsSelfCheck {

    public static void main(String[] args) {
        boolean assertionsOn = false;
        assert assertionsOn = true;
        if (!assertionsOn) throw new IllegalStateException("run with -ea, or this checks nothing");

        classifierAnchors();
        pelletGunStaysLight();
        cueTierNeverBeatsDecisiveSignal();
        unknownGunPassesThrough();
        factorMathAndClamp();
        identityFallbackSemantics();
        penRemapDistinguishesHalves();
        engineTypeOverrideMultiplies();
        unnamedCategoryKeepsIdentity();
        explosiveHasIndependentAoeFactor();

        System.out.println("ballistics self-check: OK");
    }

    private static void classifierAnchors() {
        // Rifle-like: moderate damage, no armor ignore, automatic rate of fire -> LIGHT
        assertCategory(Category.LIGHT_AUTOMATIC,
                classify("weapon.example.ak", 30F, 0.0F, 5F, 650, 1, false), "rifle-like");

        // General-purpose machine gun: mid-band damage, high rpm but still under the HMG/AM split -> HMG
        assertCategory(Category.HEAVY_MACHINE_GUN,
                classify("weapon.example.m240", 80F, 0.2F, 6F, 550, 1, false), "machine-gun-like");

        // NTW-20-like anti-materiel rifle: very high per-shot damage, high armor ignore, slow bolt action -> AM
        assertCategory(Category.ANTI_MATERIEL,
                classify("weapon.example.ntw20", 200F, 0.8F, 15F, 30, 1, false), "NTW-like");

        // Explosive bullet (grenade launcher / underbarrel round) -> EXPLOSIVE outright, regardless
        // of how small its kinetic damage number is.
        assertCategory(Category.EXPLOSIVE,
                classify("weapon.example.grenade", 10F, 0F, 3F, 60, 1, true), "explosive round");
    }

    private static void pelletGunStaysLight() {
        // Shotgun: each pellet is individually weak, but bulletAmount > 1 pins it to LIGHT even if
        // total potential damage across all pellets would otherwise read differently.
        assertCategory(Category.LIGHT_AUTOMATIC,
                classify("weapon.example.shotgun", 8F, 0F, 4F, 80, 9, false), "shotgun pellets");
    }

    private static void cueTierNeverBeatsDecisiveSignal() {
        // Numeric signals decisively pick LIGHT (low damage + automatic rpm = 5.0 + 2.0 = 7.0), but
        // the name spells out an anti-materiel cue needle. The cue tier (capped at 0.5 per category)
        // must not be able to flip a decisive numeric verdict.
        assertCategory(Category.LIGHT_AUTOMATIC,
                classify("weapon.example.barrett_reskin_12_7_lookalike", 20F, 0F, 5F, 700, 1, false),
                "cue can't beat decisive numeric signal");
    }

    private static void unknownGunPassesThrough() {
        // No TaCZ resource pack is loaded in this headless JVM, so every gunId resolves to nothing -
        // exercising exactly the "unresolvable gunId" passthrough path callers rely on.
        BulletFacts facts = BulletFacts.of(new ResourceLocation("tacz_sewv", "definitely_not_a_gun"));
        if (facts != null) {
            throw new AssertionError("expected null BulletFacts for an unresolvable gunId");
        }
        // Cache must not remember a positive result for a gun that never resolved.
        BulletFacts.clearCache();
    }

    private static void factorMathAndClamp() {
        TranslationTable table = TranslationTable.parse(Map.of(
                new ResourceLocation("tacz_sewv", "test_factor"),
                category("light_automatic", 35.0, 8.25, null, null)));
        double factor = table.factorFor(Category.LIGHT_AUTOMATIC, false, null);
        assertClose(8.25 / 35.0, factor, 1e-6, "light automatic factor");

        // A category with taczBaselineDamage <= 0 must not divide by zero - identity instead.
        TranslationTable degenerate = TranslationTable.parse(Map.of(
                new ResourceLocation("tacz_sewv", "test_degenerate"),
                category("heavy_machine_gun", 0.0, 40.0, null, null)));
        assertClose(1.0, degenerate.factorFor(Category.HEAVY_MACHINE_GUN, false, null), 1e-9,
                "zero baseline stays identity");

        // Factor must clamp to a sane ceiling even if the datapack asks for an ridiculous ratio.
        TranslationTable extreme = TranslationTable.parse(Map.of(
                new ResourceLocation("tacz_sewv", "test_extreme"),
                category("anti_materiel", 1.0, 1_000_000.0, null, null)));
        if (extreme.factorFor(Category.ANTI_MATERIEL, false, null) > 100.0) {
            throw new AssertionError("factor was not clamped to the documented ceiling");
        }
    }

    private static void identityFallbackSemantics() {
        TranslationTable fallback = TranslationTable.fallback();
        for (Category c : Category.values()) {
            assertClose(1.0, fallback.factorFor(c, false, null), 1e-9, "fallback identity for " + c);
            assertClose(1.0, fallback.factorFor(c, true, null), 1e-9, "fallback identity (AP half) for " + c);
            assertClose(1.0, fallback.aoeFactorFor(c, null), 1e-9, "fallback AoE identity for " + c);
        }

        // A file with zero usable entries must fall back the same way, not produce an empty table.
        TranslationTable empty = TranslationTable.parse(Map.of(
                new ResourceLocation("tacz_sewv", "test_empty"), new JsonObject()));
        assertClose(1.0, empty.factorFor(Category.LIGHT_AUTOMATIC, false, null), 1e-9,
                "no usable entries falls back to identity");
    }

    private static void penRemapDistinguishesHalves() {
        TranslationTable table = TranslationTable.parse(Map.of(
                new ResourceLocation("tacz_sewv", "test_penremap"),
                category("anti_materiel", 200.0, 140.0, 0.9, null)));
        double normalHalf = table.factorFor(Category.ANTI_MATERIEL, false, null);
        double apHalf = table.factorFor(Category.ANTI_MATERIEL, true, null);
        if (normalHalf == apHalf) {
            throw new AssertionError("penRemapFactor must give the armor-ignoring half a distinct factor");
        }
        assertClose(0.9, apHalf, 1e-9, "penRemapFactor value used directly for the AP half");

        // Identity: omitting penRemapFactor must make both halves equal.
        TranslationTable noRemap = TranslationTable.parse(Map.of(
                new ResourceLocation("tacz_sewv", "test_no_penremap"),
                category("anti_materiel", 200.0, 140.0, null, null)));
        assertClose(noRemap.factorFor(Category.ANTI_MATERIEL, false, null),
                noRemap.factorFor(Category.ANTI_MATERIEL, true, null), 1e-9,
                "no penRemapFactor means both halves share the base factor");
    }

    private static void engineTypeOverrideMultiplies() {
        JsonObject overrides = new JsonObject();
        overrides.addProperty("SHIP", 1.5);
        TranslationTable table = TranslationTable.parse(Map.of(
                new ResourceLocation("tacz_sewv", "test_engine_override"),
                category("light_automatic", 35.0, 8.25, null, overrides)));

        double base = table.factorFor(Category.LIGHT_AUTOMATIC, false, EngineType.TRACK);
        double ship = table.factorFor(Category.LIGHT_AUTOMATIC, false, EngineType.SHIP);
        assertClose(base * 1.5, ship, 1e-6, "engineTypeOverrides multiplies the base factor");
    }

    private static void unnamedCategoryKeepsIdentity() {
        // A file naming only one category must not zero out the others.
        TranslationTable table = TranslationTable.parse(Map.of(
                new ResourceLocation("tacz_sewv", "test_partial"),
                category("light_automatic", 35.0, 8.25, null, null)));
        assertClose(1.0, table.factorFor(Category.ANTI_MATERIEL, false, null), 1e-9,
                "category the pack never named stays identity");
    }

    private static void explosiveHasIndependentAoeFactor() {
        // Anchored on the real numbers: TaCZ RPG-7 (bullet.damage=20, explosion.damage=120) vs SBW's
        // own RpgRocketStandardEntity (damageValue=340 direct via projectile_hit, explosionDamageValue=80
        // AoE via custom_explosion) - the shipped defaults, also asserted here so a future edit to
        // translation.json's anchors doesn't silently break this expectation.
        TranslationTable table = TranslationTable.parse(Map.of(
                new ResourceLocation("tacz_sewv", "test_explosive_dual"),
                explosiveCategory(20.0, 340.0, 120.0, 80.0)));

        double directFactor = table.factorFor(Category.EXPLOSIVE, false, null);
        double aoeFactor = table.aoeFactorFor(Category.EXPLOSIVE, null);
        assertClose(340.0 / 20.0, directFactor, 1e-6, "explosive direct-hit factor");
        assertClose(80.0 / 120.0, aoeFactor, 1e-6, "explosive AoE factor");
        if (directFactor == aoeFactor) {
            throw new AssertionError("direct-hit and AoE factors must be independent, not aliased");
        }

        // Omitting the AoE pair entirely must default to identity, same as the direct-hit pair does.
        TranslationTable noAoe = TranslationTable.parse(Map.of(
                new ResourceLocation("tacz_sewv", "test_explosive_no_aoe"),
                category("explosive", 20.0, 340.0, null, null)));
        assertClose(1.0, noAoe.aoeFactorFor(Category.EXPLOSIVE, null), 1e-9,
                "AoE factor defaults to identity when the pack doesn't set it");
    }

    private static JsonObject category(String key, double taczBaseline, double sbwReference,
                                       Double penRemap, JsonObject engineOverrides) {
        JsonObject row = new JsonObject();
        row.addProperty("taczBaselineDamage", taczBaseline);
        row.addProperty("sbwReferenceDamage", sbwReference);
        if (penRemap != null) row.addProperty("penRemapFactor", penRemap);
        if (engineOverrides != null) row.add("engineTypeOverrides", engineOverrides);
        JsonObject root = new JsonObject();
        root.add(key, row);
        return root;
    }

    private static JsonObject explosiveCategory(double taczBaseline, double sbwReference,
                                                double taczAoeBaseline, double sbwAoeReference) {
        JsonObject row = new JsonObject();
        row.addProperty("taczBaselineDamage", taczBaseline);
        row.addProperty("sbwReferenceDamage", sbwReference);
        row.addProperty("taczAoeBaselineDamage", taczAoeBaseline);
        row.addProperty("sbwAoeReferenceDamage", sbwAoeReference);
        JsonObject root = new JsonObject();
        root.add("explosive", row);
        return root;
    }

    private static Category classify(String nameHint, float damage, float armorIgnore, float speed,
                                     int rpm, int pellets, boolean explosive) {
        return BallisticClassifier.classify(nameHint, damage, armorIgnore, speed, rpm, pellets, explosive);
    }

    private static void assertCategory(Category expected, Category actual, String label) {
        if (actual != expected) {
            throw new AssertionError(label + ": expected " + expected + " got " + actual);
        }
    }

    private static void assertClose(double expected, double actual, double tolerance, String label) {
        if (Math.abs(expected - actual) > tolerance) {
            throw new AssertionError(label + ": expected " + expected + " got " + actual);
        }
    }

    private BallisticsSelfCheck() {}
}
