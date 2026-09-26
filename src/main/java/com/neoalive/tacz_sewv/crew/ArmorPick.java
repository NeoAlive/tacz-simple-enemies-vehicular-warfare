package com.neoalive.tacz_sewv.crew;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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
                                                    Predicate<EquipmentSlot> wanted, IntUnaryOperator nextInt) {
        Map<EquipmentSlot, List<String>> bySlot = new EnumMap<>(EquipmentSlot.class);
        for (String id : ids) {
            EquipmentSlot slot = slotOf.apply(id);
            if (slot == null || !wanted.test(slot)) continue;
            bySlot.computeIfAbsent(slot, s -> new ArrayList<>()).add(id);
        }
        Map<EquipmentSlot, String> out = new EnumMap<>(EquipmentSlot.class);
        bySlot.forEach((slot, candidates) ->
                out.put(slot, candidates.get(candidates.size() == 1 ? 0 : nextInt.applyAsInt(candidates.size()))));
        return out;
    }
}
