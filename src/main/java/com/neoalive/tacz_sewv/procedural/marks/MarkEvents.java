package com.neoalive.tacz_sewv.procedural.marks;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.mojang.logging.LogUtils;
import net.nekoyuni.SimpleEnemyMod.procedural.events.DynamicEventManager;
import net.nekoyuni.SimpleEnemyMod.procedural.events.system.DynamicEvent;
import org.slf4j.Logger;

/**
 * Which registered SEM events run as marks, and what ground and room each needs. SEM keeps its
 * registry private, so it is read once by reflection; a SEM build that renames it leaves every
 * event on the legacy roller.
 */
public final class MarkEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Anchored to the player (a cave beside them, their base), so they keep SEM's roller. */
    private static final Set<String> ANCHORED = Set.of("cave_extraction", "mortar_shelling");

    /**
     * How far each wide event's pieces reach either side of its centre, laid across the line of
     * sight: side anchor plus vehicle backset for large_combat, the side offset for naval_battle,
     * the approach lane for asymmetric_invasion, a full column for convoy.
     */
    private static final Map<String, Integer> REACH = Map.of(
            "large_combat", 88, "naval_battle", 60, "asymmetric_invasion", 70, "convoy", 54);

    private static final Map<String, MarkRules.Profile> PROFILES = Map.of(
            "far_combat", MarkRules.Profile.VEHICLE,
            "large_combat", MarkRules.Profile.VEHICLE,
            "convoy", MarkRules.Profile.VEHICLE,
            "asymmetric_invasion", MarkRules.Profile.VEHICLE,
            "derelict_vehicle", MarkRules.Profile.VEHICLE,
            "military_patrol", MarkRules.Profile.INFANTRY,
            "naval_battle", MarkRules.Profile.WATER,
            "overflight", MarkRules.Profile.ANY);

    @Nullable
    private static Map<String, DynamicEvent> registry;
    private static boolean failed;

    private MarkEvents() {}

    /** Whether marks own this event (it is skipped by SEM's roller while the engine runs). */
    public static boolean marked(String id) {
        return !ANCHORED.contains(id);
    }

    public static int reach(String id) {
        return REACH.getOrDefault(id, 0);
    }

    /** Events from other mods default to riflemen on foot. */
    public static MarkRules.Profile profile(String id) {
        return PROFILES.getOrDefault(id, MarkRules.Profile.INFANTRY);
    }

    @Nullable
    public static DynamicEvent get(String id) {
        Map<String, DynamicEvent> all = all();
        return all == null ? null : all.get(id);
    }

    /** Every registered event marks own, or empty when the registry cannot be read. */
    public static Collection<DynamicEvent> markedEvents() {
        Map<String, DynamicEvent> all = all();
        if (all == null) return List.of();
        return all.values().stream().filter(e -> marked(e.getId())).toList();
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private static Map<String, DynamicEvent> all() {
        if (registry != null || failed) return registry;
        try {
            Field f = DynamicEventManager.class.getDeclaredField("REGISTERED_EVENTS");
            f.setAccessible(true);
            registry = (Map<String, DynamicEvent>) f.get(null);
        } catch (Throwable t) {
            failed = true;
            LOGGER.warn("[sewv] Event marks: SEM event registry not readable, using SEM's roller", t);
        }
        return registry;
    }

    /** True once the registry is known unreadable; the engine then stays off. */
    public static boolean unavailable() {
        all();
        return failed;
    }
}
