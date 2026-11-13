package com.neoalive.tacz_sewv.init;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import com.neoalive.tacz_sewv.TaczSewv;

/** Radio call-outs and UI sounds registered by this mod. */
public final class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, TaczSewv.MODID);

    /**
     * Mounted-crew radio pools, keyed by file prefix ({@code <line>_<faction>[_panicked]}). Read
     * through {@link #crew(String)}; {@code crew/CrewRadio} owns the mapping from line to prefix.
     * The block between the markers is written by the crew audio script together with the .ogg
     * files and sounds.json, so counts, keys and files always agree. Do not hand-edit it.
     */
    private static final Map<String, SoundPool> CREW = crewPools(
            // BEGIN GENERATED CREW POOLS
            pool("ally_lost_pmc", 2),
            pool("ally_lost_ru", 4),
            pool("ally_lost_us", 1),
            pool("area_clear_ru", 1),
            pool("area_clear_us", 11),
            pool("crew_command_us", 9),
            pool("crew_down_pmc", 30),
            pool("crew_down_ru", 30),
            pool("crew_down_us", 30),
            pool("damage_engine_air_pmc", 20),
            pool("damage_engine_air_ru", 30),
            pool("damage_engine_air_us", 20),
            pool("damage_engine_pmc", 7),
            pool("damage_engine_ru", 30),
            pool("damage_engine_us", 30),
            pool("damage_repaired_pmc", 30),
            pool("damage_repaired_ru", 30),
            pool("damage_repaired_us", 30),
            pool("damage_track_pmc", 25),
            pool("damage_track_ru", 28),
            pool("damage_track_us", 30),
            pool("damage_turret_pmc", 30),
            pool("damage_turret_ru", 30),
            pool("damage_turret_us", 30),
            pool("engine_start_pmc", 22),
            pool("engine_start_ru", 16),
            pool("engine_start_us", 29),
            pool("gun_ready_pmc", 30),
            pool("gun_ready_ru", 30),
            pool("gun_ready_us", 30),
            pool("load_ap_pmc", 21),
            pool("load_ap_ru", 30),
            pool("load_ap_us", 30),
            pool("load_he_pmc", 4),
            pool("load_he_ru", 30),
            pool("load_he_us", 30),
            pool("load_heat_pmc", 9),
            pool("load_heat_ru", 30),
            pool("load_heat_us", 22),
            pool("load_missile_pmc", 17),
            pool("load_missile_ru", 24),
            pool("load_missile_us", 30),
            pool("objective_lost_us", 9),
            pool("objective_taken_us", 12),
            pool("order_dispatch_pmc", 8),
            pool("order_dispatch_ru", 8),
            pool("order_dispatch_us", 13),
            pool("plan_advance_pmc", 17),
            pool("plan_advance_ru", 10),
            pool("plan_advance_us", 24),
            pool("plan_flank_us", 6),
            pool("plan_hold_ru", 4),
            pool("plan_hold_us", 3),
            pool("plan_retreat_pmc", 1),
            pool("plan_retreat_ru", 1),
            pool("plan_retreat_us", 4),
            pool("pop_smoke_air_pmc", 2),
            pool("pop_smoke_air_ru", 1),
            pool("pop_smoke_air_us", 2),
            pool("pop_smoke_pmc", 16),
            pool("pop_smoke_ru", 30),
            pool("pop_smoke_us", 30),
            pool("support_ack_air_pmc", 10),
            pool("support_ack_air_ru", 4),
            pool("support_ack_air_us", 23),
            pool("support_ack_mortar_pmc", 18),
            pool("support_ack_mortar_ru", 8),
            pool("support_ack_mortar_us", 27),
            pool("target_destroyed_pmc", 30),
            pool("target_destroyed_ru", 30),
            pool("target_destroyed_us", 30),
            pool("target_generic_air_ru", 15),
            pool("target_generic_air_us", 17),
            pool("target_generic_pmc", 29),
            pool("target_generic_ru", 30),
            pool("target_generic_us", 30),
            pool("target_helicopter_pmc", 10),
            pool("target_helicopter_ru", 10),
            pool("target_helicopter_us", 11),
            pool("target_hit_pmc", 9),
            pool("target_hit_ru", 29),
            pool("target_hit_us", 20),
            pool("target_nopen_pmc", 8),
            pool("target_nopen_ru", 17),
            pool("target_nopen_us", 17),
            pool("target_plane_pmc", 13),
            pool("target_plane_ru", 8),
            pool("target_plane_us", 10),
            pool("target_ship_pmc", 12),
            pool("target_ship_ru", 10),
            pool("target_ship_us", 9),
            pool("target_tank_pmc", 25),
            pool("target_tank_ru", 13),
            pool("target_tank_us", 30),
            pool("traverse_left_us", 4),
            pool("traverse_right_us", 6),
            pool("unit_deploy_drone_pmc", 2),
            pool("unit_deploy_drone_ru", 2),
            pool("unit_deploy_drone_us", 2),
            pool("unit_dig_ru", 2),
            pool("unit_dig_us", 2),
            pool("unit_heal_pmc", 2),
            pool("unit_heal_ru", 2),
            pool("unit_heal_us", 2),
            pool("unit_repair_pmc", 4),
            pool("unit_repair_ru", 4),
            pool("unit_repair_us", 3),
            pool("vehicle_bail_air_pmc", 3),
            pool("vehicle_bail_air_ru", 2),
            pool("vehicle_bail_air_us", 3),
            pool("vehicle_bail_pmc", 4),
            pool("vehicle_bail_ru", 4),
            pool("vehicle_bail_us", 9),
            pool("vehicle_cannon_shoot_pmc", 30),
            pool("vehicle_cannon_shoot_pmc_panicked", 30),
            pool("vehicle_cannon_shoot_ru", 26),
            pool("vehicle_cannon_shoot_ru_panicked", 30),
            pool("vehicle_cannon_shoot_us", 30),
            pool("vehicle_cannon_shoot_us_panicked", 30),
            pool("vehicle_critical_air_pmc", 21),
            pool("vehicle_critical_air_ru", 12),
            pool("vehicle_critical_air_us", 21),
            pool("vehicle_critical_pmc", 30),
            pool("vehicle_critical_ru", 30),
            pool("vehicle_critical_us", 30),
            pool("vehicle_hit_air_ru", 1),
            pool("vehicle_hit_air_us", 4),
            pool("vehicle_hit_pmc", 30),
            pool("vehicle_hit_ru", 30),
            pool("vehicle_hit_us", 30),
            pool("vehicle_idle_air_ru", 30),
            pool("vehicle_idle_air_us", 30),
            pool("vehicle_idle_pmc", 30),
            pool("vehicle_idle_ru", 30),
            pool("vehicle_idle_us", 30),
            pool("vehicle_low_health_air_pmc", 10),
            pool("vehicle_low_health_air_ru", 1),
            pool("vehicle_low_health_air_us", 10),
            pool("vehicle_low_health_pmc", 5),
            pool("vehicle_low_health_ru", 5),
            pool("vehicle_low_health_us", 3),
            pool("vehicle_mg_shoot_pmc", 7),
            pool("vehicle_mg_shoot_pmc_panicked", 2),
            pool("vehicle_mg_shoot_ru", 7),
            pool("vehicle_mg_shoot_ru_panicked", 2),
            pool("vehicle_mg_shoot_us", 6),
            pool("vehicle_mg_shoot_us_panicked", 2),
            pool("vehicle_support_air_pmc", 2),
            pool("vehicle_support_air_us", 1),
            pool("vehicle_support_mortar_pmc", 13),
            pool("vehicle_support_mortar_ru", 19),
            pool("vehicle_support_mortar_us", 18),
            pool("vehicle_targeted_pmc", 4),
            pool("vehicle_targeted_ru", 11),
            pool("vehicle_targeted_us", 11),
            pool("warn_altitude_pmc", 19),
            pool("warn_altitude_ru", 7),
            pool("warn_altitude_us", 19),
            pool("warn_gear_pmc", 5),
            pool("warn_gear_ru", 3),
            pool("warn_gear_us", 5),
            pool("warn_lock_pmc", 8),
            pool("warn_lock_ru", 14),
            pool("warn_lock_us", 8),
            pool("warn_missile_pmc", 16),
            pool("warn_missile_ru", 10),
            pool("warn_missile_us", 16)
            // END GENERATED CREW POOLS
    );

    /**
     * Handheld-radio ambient traffic, keyed by prefix ({@code radio_chatter_<lang>}). Read through
     * {@link #chatter(String)}. The block between the markers is written by
     * {@code tools/crew_audio/chatter.py}. Do not hand-edit it.
     */
    private static final Map<String, SoundPool> CHATTER = crewPools(
            // BEGIN GENERATED CHATTER POOLS
            pool("radio_chatter_en", 20),
            pool("radio_chatter_ru", 20)
            // END GENERATED CHATTER POOLS
    );

    /** Handheld radio press-to-talk clicks and squelch tails: notification cues and voice key-up/key-down. */
    public static final SoundPool RADIO_CLICK = pool("radio_click", 10);
    public static final SoundPool RADIO_SQUELCH = pool("radio_squelch", 12);

    // PMC handheld-radio ordnance acks (faction-neutral pilot pools).
    public static final SoundPool PILOT_AGM = pool("pilot_agm", 2);
    public static final SoundPool PILOT_BOMB = pool("pilot_bomb", 2);
    public static final SoundPool PILOT_CANNON = pool("pilot_cannon", 2);

    /** UI click for the Tactical Data Terminal (and similar interactable panels). */
    public static final RegistryObject<SoundEvent> INTERACT_BEEP = register("interact_beep");
    /** Quick-command wheel: right-click / back (pair with {@link #INTERACT_BEEP} for left-click). */
    public static final RegistryObject<SoundEvent> INTERACT_BEEP_BACK = register("interact_beep_back");
    /** Quick-wheel formation WIDTH/LENGTH scroll tick. */
    public static final RegistryObject<SoundEvent> SCALE = register("scale");

    /** Vehicle lock / unlock. */
    public static final RegistryObject<SoundEvent> LOCK = register("lock");

    /** FOB scramble siren; one ~0.9 s clip, re-triggered every {@code ThreatEvaluator.ALARM_TICKS}. */
    public static final RegistryObject<SoundEvent> FOB_ALARM = register("fob_alarm");

    /** FPAS radar: azimuth slew loop, open/close hydraulics loop, the brake when either stops, new contact. */
    public static final RegistryObject<SoundEvent> FPAS_AZIMUTH = register("fpas_azimuth");
    public static final RegistryObject<SoundEvent> FPAS_HYDRAULICS = register("fpas_hydraulics");
    public static final RegistryObject<SoundEvent> FPAS_STOP = register("fpas_stop");
    public static final RegistryObject<SoundEvent> FPAS_TARGET = register("fpas_target");

    /** Grace period start popup sting. */
    public static final RegistryObject<SoundEvent> GRACE_START = register("grace_start");

    /** World-map Attack-this-unit confirm. */
    public static final RegistryObject<SoundEvent> ATTACK = register("attack");

    private ModSounds() {}

    private static RegistryObject<SoundEvent> register(String name) {
        ResourceLocation id = new ResourceLocation(TaczSewv.MODID, name);
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(id));
    }

    private static SoundPool pool(String prefix, int count) {
        List<RegistryObject<SoundEvent>> variants = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) variants.add(register(prefix + "_" + i));
        return new SoundPool(prefix, variants);
    }

    private static Map<String, SoundPool> crewPools(SoundPool... pools) {
        Map<String, SoundPool> map = new HashMap<>(pools.length * 2);
        for (SoundPool pool : pools) map.put(pool.prefix(), pool);
        return map;
    }

    /** The crew pool with this file prefix, or null when none ships. */
    public static SoundPool crew(String prefix) {
        return CREW.get(prefix);
    }

    /** The radio chatter pool for this language ({@code en}, {@code ru}), or null when none ships. */
    public static SoundPool chatter(String lang) {
        return CHATTER.get("radio_chatter_" + lang);
    }

    /**
     * Shuffle bag: hands out every clip once, in a fresh random order, before any repeat -- so a
     * pool never clumps the way raw random does, nor sounds cyclic. The seam between two bags is
     * de-duped so a reshuffle can't repeat the clip that just played. One bag per pool, shared by
     * every hull; {@code synchronized} because AI on different threads could draw at once, and
     * allocation-free after construction (the bag is shuffled in place with {@link ThreadLocalRandom}).
     */
    public static final class SoundPool {
        private final String prefix;
        private final List<RegistryObject<SoundEvent>> variants;
        private final int[] bag;
        private int cursor;
        private int last = -1;
        private short[] ticks;
        private static final int DEFAULT_CLIP_TICKS = 50;

        private SoundPool(String prefix, List<RegistryObject<SoundEvent>> variants) {
            this.prefix = prefix;
            this.variants = variants;
            this.bag = new int[variants.size()];
            for (int i = 0; i < bag.length; i++) bag[i] = i;
            this.cursor = bag.length; // empty: the first draw reshuffles
        }

        public String prefix() {
            return prefix;
        }

        public synchronized SoundEvent next() {
            return event(draw());
        }

        /** Next clip index from the shuffle bag. */
        public synchronized int draw() {
            if (cursor >= bag.length) {
                ThreadLocalRandom rng = ThreadLocalRandom.current();
                for (int i = bag.length - 1; i > 0; i--) { // Fisher-Yates
                    int j = rng.nextInt(i + 1);
                    int t = bag[i]; bag[i] = bag[j]; bag[j] = t;
                }
                if (bag.length > 1 && bag[0] == last) { // don't repeat across the seam
                    int t = bag[0]; bag[0] = bag[1]; bag[1] = t;
                }
                cursor = 0;
            }
            last = bag[cursor++];
            return last;
        }

        public SoundEvent event(int index) {
            return variants.get(index).get();
        }

        /** Game ticks the clip at this index lasts; a safe guess when no length was generated. */
        public int ticks(int index) {
            short[] t = ticks;
            if (t == null) {
                t = com.neoalive.tacz_sewv.crew.CrewClipLengths.of(prefix);
                ticks = t == null ? new short[0] : t;
                t = ticks;
            }
            return index < t.length ? t[index] : DEFAULT_CLIP_TICKS;
        }
    }
}
