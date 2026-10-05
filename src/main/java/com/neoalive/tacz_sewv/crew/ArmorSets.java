package com.neoalive.tacz_sewv.crew;

import java.util.List;

/**
 * Hardcoded armor-set groups: pieces that prefer to be issued together.
 *
 * <p>{@link ArmorPick} rolls a set when two or more of its ids are in the unit's loadout, then
 * fills any slot the set does not cover from the remaining per-slot pool. Incomplete sets are
 * fine — a chest+legs kit with no matching helmet still locks those two and rolls the helmet.
 *
 * <p>Ids are full registry strings. Unknown packs simply never hit a set and keep the old
 * independent per-slot draw.
 */
public final class ArmorSets {

    private static final String DR = "dragonrise_reforge:";

    /**
     * Matching groups. One piece per slot per set; overlapping ids across sets are allowed
     * (e.g. {@code pants21} in both Type-21 variants).
     */
    public static final List<List<String>> ALL = List.of(
            // DragonRise — Type 07 desert / ocean
            List.of(DR + "desert07_helmet", DR + "desert07_chest", DR + "desert07_pants"),
            List.of(DR + "ocean07_helmet", DR + "ocean07_chest", DR + "ocean07_pants"),
            // DragonRise — RU field kits
            List.of(DR + "msv_chest", DR + "msv_pants"),
            List.of(DR + "gorka3", DR + "gorka3_leggings"),
            // DragonRise — Type 21 family (CN rifleman + medic variant share pants)
            List.of(DR + "cn21", DR + "cnchest", DR + "pants21"),
            List.of(DR + "med21_chest", DR + "pants21")
    );

    private ArmorSets() {}
}
