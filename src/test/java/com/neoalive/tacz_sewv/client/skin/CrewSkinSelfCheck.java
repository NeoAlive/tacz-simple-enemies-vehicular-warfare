package com.neoalive.tacz_sewv.client.skin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.neoalive.tacz_sewv.crew.CrewFacts;

/**
 * Asserts the crew-skin filename grammar and that every shipped default agrees with it.
 * Run with {@code ./gradlew selfCheck}.
 */
public final class CrewSkinSelfCheck {

    /** Which category each legacy skin-pool subfolder is expected to hold. */
    private static final Map<String, String> LEGACY_FOLDER_CATEGORY = Map.of(
            "infantry", "infantry",
            "medics", "medic",
            "combat_engineers", "combat_engineer",
            "mechanical_engineers", "mechanical_engineer");

    private static final List<String> SEM_VARIANT_ORDER = List.of(
            "default", "variant1", "variant2", "variant3", "variant4", "variant5");

    public static void main(String[] args) throws IOException {
        grammar();
        backwardsCompatible();
        roleFolderKeys();
        shippedDefaults();
        semVariantDefaults();
        wipe();
        System.out.println("CrewSkinSelfCheck OK");
    }

    private static void wipe() throws IOException {
        Path root = Files.createTempDirectory("sewv-wipe");
        try {
            Path nested = Files.createDirectories(root.resolve("infantry"));
            Path top = Files.writeString(root.resolve("us_chest_iotv_1_1.png"), "x");
            Path deep = Files.writeString(nested.resolve("us_infantry_1_1.png"), "x");
            Path keep = Files.writeString(nested.resolve("notes.txt"), "x");

            SkinFiles.wipe(root, "[selfcheck]");

            assert !Files.exists(top) : "top-level PNG survived";
            assert !Files.exists(deep) : "nested PNG survived";
            assert Files.exists(keep) : "non-PNG was deleted";
            assert Files.isDirectory(nested) : "folder was removed";
        } finally {
            try (Stream<Path> tree = Files.walk(root)) {
                for (Path p : tree.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    private static void grammar() {
        CrewSkinRegistry.Parsed both = parse("us_chest_iotv_2_3.png");
        assert both.faction() == CrewFacts.Faction.US : both.faction();
        assert both.kind().equals("chest_iotv") : both.kind();
        assert both.camo() == 2 && both.rng() == 3 : both.camo() + "/" + both.rng();

        CrewSkinRegistry.Parsed alnum = parse("ru_helmet_6b47_4_1.png");
        assert alnum.kind().equals("helmet_6b47") : alnum.kind();
        assert alnum.camo() == 4 && alnum.rng() == 1 : alnum.camo() + "/" + alnum.rng();

        CrewSkinRegistry.Parsed category = parse("pmc_mechanical_engineer_1_2.png");
        assert category.faction() == CrewFacts.Faction.PMC : category.faction();
        assert category.kind().equals("mechanical_engineer") : category.kind();
        assert category.camo() == 1 && category.rng() == 2 : category.camo() + "/" + category.rng();

        CrewSkinRegistry.Parsed plain = parse("us_chest_iotv.png");
        assert plain.camo() == -1 : plain.camo();

        assert CrewSkinRegistry.parseFilename("xx_chest_iotv_1_1.png") == null : "unknown faction";
        assert CrewSkinRegistry.parseFilename("us_chest_iotv_1_1.jpg") == null : "not a png";
        assert CrewSkinRegistry.parseFilename("us.png") == null : "no kind";
    }

    private static void backwardsCompatible() {
        CrewSkinRegistry.Parsed old = parse("us_chest_iotv_1.png");
        CrewSkinRegistry.Parsed renamed = parse("us_chest_iotv_1_1.png");
        assert old.kind().equals(renamed.kind()) : old.kind() + " != " + renamed.kind();
        assert old.camo() == renamed.camo() : old.camo() + " != " + renamed.camo();
        assert old.rng() == renamed.rng() : old.rng() + " != " + renamed.rng();
    }

    private static void roleFolderKeys() {
        assert "ru_medic".equals(CrewSkinRegistry.roleFolderKey("medic", CrewFacts.Faction.RU));
        assert "us_engineer".equals(CrewSkinRegistry.roleFolderKey("mechanical_engineer", CrewFacts.Faction.US));
        assert "pmc_commander".equals(CrewSkinRegistry.roleFolderKey("commander", CrewFacts.Faction.PMC));
        assert CrewSkinRegistry.roleFolderKey("infantry", CrewFacts.Faction.US) == null;
    }

    private static void shippedDefaults() throws IOException {
        List<String> problems = new ArrayList<>();
        Path assets = Path.of("src/main/resources/assets/tacz_sewv");

        checkCamo(assets.resolve("armor_skins_defaults"), problems, false);
        checkCamo(assets.resolve("unit_skins_defaults/camo"), problems, true);
        checkCamo(assets.resolve("skin_pools_defaults"), problems, true);

        if (!problems.isEmpty()) {
            throw new IllegalStateException("crew skin defaults: " + String.join("; ", problems));
        }
    }

    private static void semVariantDefaults() throws IOException {
        Path root = Path.of("src/main/resources/assets/tacz_sewv/unit_skins_defaults");
        for (String folder : List.of("ru_unit", "us_unit", "pmc_unit")) {
            Path dir = root.resolve(folder);
            if (!Files.isDirectory(dir)) {
                throw new IllegalStateException("missing SEM variant folder " + dir);
            }
            List<String> names;
            try (Stream<Path> stream = Files.list(dir)) {
                names = stream.filter(p -> p.getFileName().toString().endsWith(".png"))
                        .map(p -> p.getFileName().toString())
                        .sorted()
                        .toList();
            }
            int expected = CrewSkinRegistry.expectedVariantCount(folder);
            assert names.size() == expected : folder + " has " + names.size() + " skins, expected " + expected;
            for (int i = 0; i < names.size(); i++) {
                String expectedSuffix = SEM_VARIANT_ORDER.get(i);
                assert names.get(i).contains(expectedSuffix) :
                        folder + " index " + i + " is " + names.get(i) + ", expected *" + expectedSuffix + "*";
            }
        }
    }

    private static void checkCamo(Path root, List<String> problems, boolean categorised) throws IOException {
        if (!Files.isDirectory(root)) {
            problems.add("missing " + root);
            return;
        }
        try (Stream<Path> tree = Files.walk(root)) {
            List<Path> files = tree.filter(p -> p.getFileName().toString().endsWith(".png")).toList();
            if (files.isEmpty()) {
                problems.add("no PNGs under " + root);
            }
            for (Path file : files) {
                String name = file.getFileName().toString();
                CrewSkinRegistry.Parsed parsed = CrewSkinRegistry.parseFilename(name);
                if (parsed == null) {
                    problems.add(name + " does not parse");
                    continue;
                }
                if (!categorised) continue;

                String folder = file.getParent().getFileName().toString();
                String expected = LEGACY_FOLDER_CATEGORY.get(folder);
                if (expected == null) {
                    if ("camo".equals(folder)) continue;
                    problems.add(name + " is in unknown folder " + folder);
                } else if (!expected.equals(parsed.kind())) {
                    problems.add(name + " is category " + parsed.kind() + " but sits in " + folder);
                }
            }
        }
    }

    private static CrewSkinRegistry.Parsed parse(String filename) {
        CrewSkinRegistry.Parsed parsed = CrewSkinRegistry.parseFilename(filename);
        assert parsed != null : filename + " should parse";
        return parsed;
    }

    private CrewSkinSelfCheck() {
    }
}
