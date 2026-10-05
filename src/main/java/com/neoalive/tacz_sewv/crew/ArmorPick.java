package com.neoalive.tacz_sewv.crew;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;

import net.minecraft.world.entity.EquipmentSlot;

/**
 * Which piece of a faction's armor list a unit is issued, slot by slot.
 *
 * <p>Pieces that declare the SAME slot are alternatives, not a queue: the unit gets one of them,
 * chosen at random, so a list with three helmets is a 1-in-3 draw for each. (It used to be
 * first-listed-wins, which made every later helmet dead weight.) A slot with a single candidate is
 * simply always issued, so existing lists behave as before. Listing an id twice doubles its odds.
 *
 * <p>When the loadout contains two or more pieces of a known {@linkplain ArmorSets set}, one such
 * set is rolled first and its pieces are locked into their slots; any slot the set does not cover
 * still draws at random from that slot's pool (so a chest+legs kit with no matching helmet keeps
 * the kit and rolls a helmet).
 *
 * <p>Pure — no world state — so {@code selfCheckArmor} runs it headless.
 */
public final class ArmorPick {

    private ArmorPick() {}

    /**
     * @param ids     the configured list, in order
     * @param slotOf  the slot an id's item declares, or null when it is not a usable armor item
     * @param wanted  false for a slot that must stay untouched (already filled, or a support unit
     *                that only takes the helmet)
     * @param nextInt {@code nextInt(n)} in {@code [0, n)}, the unit's own random source
     * @return one chosen id per wanted slot
     */
    public static Map<EquipmentSlot, String> choose(List<? extends String> ids,
                                                    Function<String, EquipmentSlot> slotOf,
                                                    Predicate<EquipmentSlot> wanted,
                                                    IntUnaryOperator nextInt) {
        return choose(ids, slotOf, wanted, nextInt, ArmorSets.ALL);
    }

    /** Overload for self-check / callers that inject their own set table. */
    public static Map<EquipmentSlot, String> choose(List<? extends String> ids,
                                                    Function<String, EquipmentSlot> slotOf,
                                                    Predicate<EquipmentSlot> wanted,
                                                    IntUnaryOperator nextInt,
                                                    Collection<? extends List<String>> sets) {
        Map<EquipmentSlot, List<String>> bySlot = new EnumMap<>(EquipmentSlot.class);
        Set<String> present = new HashSet<>();
        for (String id : ids) {
            EquipmentSlot slot = slotOf.apply(id);
            if (slot == null || !wanted.test(slot)) continue;
            bySlot.computeIfAbsent(slot, s -> new ArrayList<>()).add(id);
            present.add(id);
        }

        Map<EquipmentSlot, String> locked = lockSet(present, slotOf, wanted, nextInt, sets);

        Map<EquipmentSlot, String> out = new EnumMap<>(EquipmentSlot.class);
        bySlot.forEach((slot, candidates) -> {
            String fromSet = locked.get(slot);
            if (fromSet != null && candidates.contains(fromSet)) {
                out.put(slot, fromSet);
                return;
            }
            out.put(slot, candidates.get(candidates.size() == 1 ? 0 : nextInt.applyAsInt(candidates.size())));
        });
        return out;
    }

    /**
     * Among sets with ≥2 ids present in the loadout, pick one uniformly and return its id per
     * wanted slot. Empty when nothing matches.
     */
    private static Map<EquipmentSlot, String> lockSet(Set<String> present,
                                                      Function<String, EquipmentSlot> slotOf,
                                                      Predicate<EquipmentSlot> wanted,
                                                      IntUnaryOperator nextInt,
                                                      Collection<? extends List<String>> sets) {
        List<List<String>> eligible = new ArrayList<>();
        for (List<String> set : sets) {
            int hits = 0;
            for (String id : set) {
                if (present.contains(id)) hits++;
            }
            if (hits >= 2) eligible.add(set);
        }
        if (eligible.isEmpty()) return Map.of();

        List<String> chosen = eligible.get(eligible.size() == 1 ? 0 : nextInt.applyAsInt(eligible.size()));
        Map<EquipmentSlot, String> locked = new EnumMap<>(EquipmentSlot.class);
        for (String id : chosen) {
            if (!present.contains(id)) continue;
            EquipmentSlot slot = slotOf.apply(id);
            if (slot == null || !wanted.test(slot)) continue;
            // One piece per slot per set by construction; first wins if a bad table double-books.
            locked.putIfAbsent(slot, id);
        }
        return locked;
    }
}
