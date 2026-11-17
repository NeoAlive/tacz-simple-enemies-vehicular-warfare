package com.neoalive.tacz_sewv.init;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import com.neoalive.tacz_sewv.TaczSewv;

/** Radio call-outs and UI sounds registered by this mod. */
public final class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(BuiltInRegistries.SOUND_EVENT, TaczSewv.MODID);

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
            pool("area_clear_pmc_de", 27),
            pool("area_clear_pmc_fr", 2),
            pool("area_clear_ru", 1),
            pool("area_clear_us", 11),
            pool("crew_command_pmc_de", 7),
            pool("crew_command_pmc_fi", 2),
            pool("crew_command_pmc_fr", 4),
            pool("crew_command_pmc_ro", 1),
            pool("crew_command_pmc_zh", 1),
            pool("crew_command_us", 9),
            pool("crew_down_pmc", 30),
            pool("crew_down_pmc_cs", 30),
            pool("crew_down_pmc_de", 30),
            pool("crew_down_pmc_el", 30),
            pool("crew_down_pmc_es", 30),
            pool("crew_down_pmc_fi", 30),
            pool("crew_down_pmc_fr", 30),
            pool("crew_down_pmc_he", 30),
            pool("crew_down_pmc_hu", 13),
            pool("crew_down_pmc_it", 30),
            pool("crew_down_pmc_ja", 12),
            pool("crew_down_pmc_nl", 30),
            pool("crew_down_pmc_pl", 30),
            pool("crew_down_pmc_ro", 30),
            pool("crew_down_pmc_sv", 30),
            pool("crew_down_pmc_th", 30),
            pool("crew_down_pmc_tr", 30),
            pool("crew_down_pmc_uk", 30),
            pool("crew_down_pmc_zh", 30),
            pool("crew_down_ru", 30),
            pool("crew_down_us", 30),
            pool("damage_engine_air_pmc", 20),
            pool("damage_engine_air_ru", 30),
            pool("damage_engine_air_us", 20),
            pool("damage_engine_pmc", 7),
            pool("damage_engine_pmc_cs", 30),
            pool("damage_engine_pmc_de", 30),
            pool("damage_engine_pmc_el", 13),
            pool("damage_engine_pmc_es", 15),
            pool("damage_engine_pmc_fi", 25),
            pool("damage_engine_pmc_fr", 30),
            pool("damage_engine_pmc_he", 19),
            pool("damage_engine_pmc_hu", 26),
            pool("damage_engine_pmc_it", 30),
            pool("damage_engine_pmc_ja", 10),
            pool("damage_engine_pmc_pl", 21),
            pool("damage_engine_pmc_ro", 23),
            pool("damage_engine_pmc_sv", 12),
            pool("damage_engine_pmc_th", 8),
            pool("damage_engine_pmc_tr", 21),
            pool("damage_engine_pmc_uk", 16),
            pool("damage_engine_pmc_zh", 18),
            pool("damage_engine_ru", 30),
            pool("damage_engine_us", 30),
            pool("damage_repaired_pmc", 30),
            pool("damage_repaired_pmc_cs", 30),
            pool("damage_repaired_pmc_de", 30),
            pool("damage_repaired_pmc_el", 10),
            pool("damage_repaired_pmc_es", 20),
            pool("damage_repaired_pmc_fi", 30),
            pool("damage_repaired_pmc_fr", 15),
            pool("damage_repaired_pmc_he", 24),
            pool("damage_repaired_pmc_hu", 16),
            pool("damage_repaired_pmc_it", 30),
            pool("damage_repaired_pmc_ja", 4),
            pool("damage_repaired_pmc_nl", 16),
            pool("damage_repaired_pmc_pl", 30),
            pool("damage_repaired_pmc_ro", 26),
            pool("damage_repaired_pmc_sv", 19),
            pool("damage_repaired_pmc_th", 17),
            pool("damage_repaired_pmc_tr", 23),
            pool("damage_repaired_pmc_uk", 20),
            pool("damage_repaired_pmc_zh", 30),
            pool("damage_repaired_ru", 30),
            pool("damage_repaired_us", 30),
            pool("damage_track_pmc", 25),
            pool("damage_track_pmc_cs", 21),
            pool("damage_track_pmc_de", 30),
            pool("damage_track_pmc_el", 17),
            pool("damage_track_pmc_es", 10),
            pool("damage_track_pmc_fi", 14),
            pool("damage_track_pmc_fr", 13),
            pool("damage_track_pmc_he", 9),
            pool("damage_track_pmc_hu", 10),
            pool("damage_track_pmc_it", 15),
            pool("damage_track_pmc_pl", 8),
            pool("damage_track_pmc_ro", 9),
            pool("damage_track_pmc_sv", 6),
            pool("damage_track_pmc_th", 7),
            pool("damage_track_pmc_tr", 11),
            pool("damage_track_pmc_uk", 14),
            pool("damage_track_pmc_zh", 5),
            pool("damage_track_ru", 28),
            pool("damage_track_us", 30),
            pool("damage_turret_pmc", 30),
            pool("damage_turret_pmc_cs", 30),
            pool("damage_turret_pmc_de", 30),
            pool("damage_turret_pmc_el", 25),
            pool("damage_turret_pmc_es", 30),
            pool("damage_turret_pmc_fi", 30),
            pool("damage_turret_pmc_fr", 30),
            pool("damage_turret_pmc_he", 30),
            pool("damage_turret_pmc_hu", 22),
            pool("damage_turret_pmc_it", 30),
            pool("damage_turret_pmc_ja", 5),
            pool("damage_turret_pmc_nl", 30),
            pool("damage_turret_pmc_pl", 30),
            pool("damage_turret_pmc_ro", 30),
            pool("damage_turret_pmc_sv", 30),
            pool("damage_turret_pmc_th", 6),
            pool("damage_turret_pmc_tr", 30),
            pool("damage_turret_pmc_uk", 25),
            pool("damage_turret_pmc_zh", 26),
            pool("damage_turret_ru", 30),
            pool("damage_turret_us", 30),
            pool("engine_start_pmc", 22),
            pool("engine_start_pmc_cs", 30),
            pool("engine_start_pmc_de", 30),
            pool("engine_start_pmc_el", 15),
            pool("engine_start_pmc_es", 17),
            pool("engine_start_pmc_fi", 23),
            pool("engine_start_pmc_fr", 30),
            pool("engine_start_pmc_he", 13),
            pool("engine_start_pmc_hu", 19),
            pool("engine_start_pmc_it", 19),
            pool("engine_start_pmc_ja", 17),
            pool("engine_start_pmc_nl", 16),
            pool("engine_start_pmc_pl", 30),
            pool("engine_start_pmc_ro", 17),
            pool("engine_start_pmc_sv", 15),
            pool("engine_start_pmc_th", 11),
            pool("engine_start_pmc_tr", 20),
            pool("engine_start_pmc_uk", 15),
            pool("engine_start_pmc_zh", 17),
            pool("engine_start_ru", 16),
            pool("engine_start_us", 29),
            pool("gun_ready_pmc", 30),
            pool("gun_ready_pmc_cs", 30),
            pool("gun_ready_pmc_de", 30),
            pool("gun_ready_pmc_el", 30),
            pool("gun_ready_pmc_es", 29),
            pool("gun_ready_pmc_fi", 30),
            pool("gun_ready_pmc_fr", 30),
            pool("gun_ready_pmc_he", 30),
            pool("gun_ready_pmc_hu", 30),
            pool("gun_ready_pmc_it", 30),
            pool("gun_ready_pmc_ja", 1),
            pool("gun_ready_pmc_nl", 29),
            pool("gun_ready_pmc_pl", 30),
            pool("gun_ready_pmc_ro", 30),
            pool("gun_ready_pmc_sv", 30),
            pool("gun_ready_pmc_th", 27),
            pool("gun_ready_pmc_tr", 30),
            pool("gun_ready_pmc_uk", 30),
            pool("gun_ready_pmc_zh", 18),
            pool("gun_ready_ru", 30),
            pool("gun_ready_us", 30),
            pool("load_ap_pmc", 21),
            pool("load_ap_pmc_cs", 30),
            pool("load_ap_pmc_de", 30),
            pool("load_ap_pmc_el", 13),
            pool("load_ap_pmc_es", 23),
            pool("load_ap_pmc_fi", 30),
            pool("load_ap_pmc_fr", 27),
            pool("load_ap_pmc_he", 30),
            pool("load_ap_pmc_hu", 13),
            pool("load_ap_pmc_it", 30),
            pool("load_ap_pmc_ja", 3),
            pool("load_ap_pmc_nl", 30),
            pool("load_ap_pmc_pl", 30),
            pool("load_ap_pmc_ro", 22),
            pool("load_ap_pmc_sv", 30),
            pool("load_ap_pmc_tr", 30),
            pool("load_ap_pmc_uk", 18),
            pool("load_ap_pmc_zh", 10),
            pool("load_ap_ru", 30),
            pool("load_ap_us", 30),
            pool("load_he_pmc", 4),
            pool("load_he_pmc_cs", 30),
            pool("load_he_pmc_de", 30),
            pool("load_he_pmc_el", 20),
            pool("load_he_pmc_es", 20),
            pool("load_he_pmc_fi", 30),
            pool("load_he_pmc_fr", 28),
            pool("load_he_pmc_he", 30),
            pool("load_he_pmc_hu", 20),
            pool("load_he_pmc_it", 30),
            pool("load_he_pmc_ja", 6),
            pool("load_he_pmc_nl", 30),
            pool("load_he_pmc_pl", 30),
            pool("load_he_pmc_ro", 30),
            pool("load_he_pmc_sv", 30),
            pool("load_he_pmc_th", 6),
            pool("load_he_pmc_tr", 30),
            pool("load_he_pmc_uk", 26),
            pool("load_he_pmc_zh", 9),
            pool("load_he_ru", 30),
            pool("load_he_us", 30),
            pool("load_heat_pmc", 9),
            pool("load_heat_pmc_cs", 30),
            pool("load_heat_pmc_de", 30),
            pool("load_heat_pmc_el", 25),
            pool("load_heat_pmc_es", 12),
            pool("load_heat_pmc_fi", 14),
            pool("load_heat_pmc_fr", 19),
            pool("load_heat_pmc_he", 15),
            pool("load_heat_pmc_hu", 18),
            pool("load_heat_pmc_it", 25),
            pool("load_heat_pmc_ja", 3),
            pool("load_heat_pmc_nl", 24),
            pool("load_heat_pmc_pl", 23),
            pool("load_heat_pmc_ro", 26),
            pool("load_heat_pmc_sv", 23),
            pool("load_heat_pmc_th", 7),
            pool("load_heat_pmc_tr", 18),
            pool("load_heat_pmc_uk", 15),
            pool("load_heat_pmc_zh", 12),
            pool("load_heat_ru", 30),
            pool("load_heat_us", 22),
            pool("load_missile_pmc", 17),
            pool("load_missile_pmc_cs", 30),
            pool("load_missile_pmc_de", 30),
            pool("load_missile_pmc_el", 9),
            pool("load_missile_pmc_es", 9),
            pool("load_missile_pmc_fi", 12),
            pool("load_missile_pmc_fr", 17),
            pool("load_missile_pmc_he", 20),
            pool("load_missile_pmc_hu", 18),
            pool("load_missile_pmc_it", 23),
            pool("load_missile_pmc_ja", 3),
            pool("load_missile_pmc_nl", 24),
            pool("load_missile_pmc_pl", 23),
            pool("load_missile_pmc_ro", 17),
            pool("load_missile_pmc_sv", 30),
            pool("load_missile_pmc_th", 9),
            pool("load_missile_pmc_tr", 18),
            pool("load_missile_pmc_uk", 14),
            pool("load_missile_pmc_zh", 11),
            pool("load_missile_ru", 24),
            pool("load_missile_us", 30),
            pool("objective_lost_us", 9),
            pool("objective_taken_pmc_de", 4),
            pool("objective_taken_us", 12),
            pool("order_dispatch_pmc", 8),
            pool("order_dispatch_pmc_cs", 10),
            pool("order_dispatch_pmc_de", 6),
            pool("order_dispatch_pmc_el", 9),
            pool("order_dispatch_pmc_es", 2),
            pool("order_dispatch_pmc_fi", 2),
            pool("order_dispatch_pmc_fr", 12),
            pool("order_dispatch_pmc_he", 4),
            pool("order_dispatch_pmc_pl", 2),
            pool("order_dispatch_pmc_ro", 2),
            pool("order_dispatch_pmc_sv", 2),
            pool("order_dispatch_pmc_tr", 3),
            pool("order_dispatch_pmc_uk", 1),
            pool("order_dispatch_pmc_zh", 1),
            pool("order_dispatch_ru", 8),
            pool("order_dispatch_us", 13),
            pool("plan_advance_pmc", 17),
            pool("plan_advance_pmc_cs", 3),
            pool("plan_advance_pmc_de", 24),
            pool("plan_advance_pmc_es", 2),
            pool("plan_advance_pmc_fr", 21),
            pool("plan_advance_pmc_he", 4),
            pool("plan_advance_pmc_hu", 2),
            pool("plan_advance_pmc_it", 4),
            pool("plan_advance_pmc_ja", 3),
            pool("plan_advance_pmc_nl", 2),
            pool("plan_advance_pmc_pl", 6),
            pool("plan_advance_pmc_ro", 5),
            pool("plan_advance_pmc_sv", 2),
            pool("plan_advance_pmc_th", 2),
            pool("plan_advance_pmc_tr", 5),
            pool("plan_advance_pmc_uk", 6),
            pool("plan_advance_pmc_zh", 3),
            pool("plan_advance_ru", 10),
            pool("plan_advance_us", 24),
            pool("plan_flank_pmc_de", 4),
            pool("plan_flank_us", 6),
            pool("plan_hold_pmc_de", 6),
            pool("plan_hold_pmc_fr", 10),
            pool("plan_hold_pmc_it", 3),
            pool("plan_hold_ru", 4),
            pool("plan_hold_us", 3),
            pool("plan_retreat_pmc", 1),
            pool("plan_retreat_pmc_cs", 10),
            pool("plan_retreat_pmc_de", 30),
            pool("plan_retreat_pmc_el", 1),
            pool("plan_retreat_pmc_es", 1),
            pool("plan_retreat_pmc_fi", 1),
            pool("plan_retreat_pmc_fr", 18),
            pool("plan_retreat_pmc_he", 4),
            pool("plan_retreat_pmc_hu", 4),
            pool("plan_retreat_pmc_it", 3),
            pool("plan_retreat_pmc_ja", 3),
            pool("plan_retreat_pmc_nl", 3),
            pool("plan_retreat_pmc_pl", 4),
            pool("plan_retreat_pmc_ro", 2),
            pool("plan_retreat_pmc_sv", 4),
            pool("plan_retreat_pmc_th", 5),
            pool("plan_retreat_pmc_tr", 8),
            pool("plan_retreat_pmc_uk", 5),
            pool("plan_retreat_pmc_zh", 8),
            pool("plan_retreat_ru", 1),
            pool("plan_retreat_us", 4),
            pool("pop_smoke_air_pmc", 2),
            pool("pop_smoke_air_ru", 1),
            pool("pop_smoke_air_us", 2),
            pool("pop_smoke_pmc", 16),
            pool("pop_smoke_pmc_de", 2),
            pool("pop_smoke_ru", 30),
            pool("pop_smoke_us", 30),
            pool("support_ack_air_pmc", 10),
            pool("support_ack_air_pmc_de", 9),
            pool("support_ack_air_pmc_el", 3),
            pool("support_ack_air_pmc_fi", 3),
            pool("support_ack_air_pmc_fr", 10),
            pool("support_ack_air_pmc_hu", 1),
            pool("support_ack_air_pmc_ro", 4),
            pool("support_ack_air_pmc_tr", 1),
            pool("support_ack_air_ru", 4),
            pool("support_ack_air_us", 23),
            pool("support_ack_mortar_pmc", 18),
            pool("support_ack_mortar_pmc_cs", 3),
            pool("support_ack_mortar_pmc_de", 30),
            pool("support_ack_mortar_pmc_el", 4),
            pool("support_ack_mortar_pmc_es", 6),
            pool("support_ack_mortar_pmc_fi", 6),
            pool("support_ack_mortar_pmc_fr", 30),
            pool("support_ack_mortar_pmc_he", 9),
            pool("support_ack_mortar_pmc_hu", 6),
            pool("support_ack_mortar_pmc_it", 17),
            pool("support_ack_mortar_pmc_ja", 8),
            pool("support_ack_mortar_pmc_nl", 8),
            pool("support_ack_mortar_pmc_pl", 8),
            pool("support_ack_mortar_pmc_ro", 3),
            pool("support_ack_mortar_pmc_sv", 7),
            pool("support_ack_mortar_pmc_th", 5),
            pool("support_ack_mortar_pmc_tr", 6),
            pool("support_ack_mortar_pmc_uk", 20),
            pool("support_ack_mortar_pmc_zh", 10),
            pool("support_ack_mortar_ru", 8),
            pool("support_ack_mortar_us", 27),
            pool("target_destroyed_pmc", 30),
            pool("target_destroyed_pmc_cs", 30),
            pool("target_destroyed_pmc_de", 30),
            pool("target_destroyed_pmc_el", 14),
            pool("target_destroyed_pmc_es", 3),
            pool("target_destroyed_pmc_fi", 19),
            pool("target_destroyed_pmc_fr", 30),
            pool("target_destroyed_pmc_he", 15),
            pool("target_destroyed_pmc_hu", 23),
            pool("target_destroyed_pmc_it", 30),
            pool("target_destroyed_pmc_ja", 4),
            pool("target_destroyed_pmc_nl", 29),
            pool("target_destroyed_pmc_pl", 30),
            pool("target_destroyed_pmc_ro", 15),
            pool("target_destroyed_pmc_sv", 13),
            pool("target_destroyed_pmc_th", 5),
            pool("target_destroyed_pmc_tr", 12),
            pool("target_destroyed_pmc_uk", 24),
            pool("target_destroyed_pmc_zh", 16),
            pool("target_destroyed_ru", 30),
            pool("target_destroyed_us", 30),
            pool("target_generic_air_ru", 15),
            pool("target_generic_air_us", 17),
            pool("target_generic_pmc", 29),
            pool("target_generic_pmc_cs", 30),
            pool("target_generic_pmc_de", 30),
            pool("target_generic_pmc_el", 18),
            pool("target_generic_pmc_es", 28),
            pool("target_generic_pmc_fi", 23),
            pool("target_generic_pmc_fr", 30),
            pool("target_generic_pmc_he", 21),
            pool("target_generic_pmc_hu", 17),
            pool("target_generic_pmc_it", 29),
            pool("target_generic_pmc_ja", 13),
            pool("target_generic_pmc_nl", 30),
            pool("target_generic_pmc_pl", 30),
            pool("target_generic_pmc_ro", 16),
            pool("target_generic_pmc_sv", 13),
            pool("target_generic_pmc_tr", 23),
            pool("target_generic_pmc_uk", 19),
            pool("target_generic_pmc_zh", 6),
            pool("target_generic_ru", 30),
            pool("target_generic_us", 30),
            pool("target_helicopter_pmc", 10),
            pool("target_helicopter_pmc_de", 1),
            pool("target_helicopter_pmc_es", 5),
            pool("target_helicopter_pmc_fr", 10),
            pool("target_helicopter_pmc_he", 5),
            pool("target_helicopter_pmc_ja", 2),
            pool("target_helicopter_pmc_pl", 2),
            pool("target_helicopter_pmc_sv", 3),
            pool("target_helicopter_ru", 10),
            pool("target_helicopter_us", 11),
            pool("target_hit_pmc", 9),
            pool("target_hit_pmc_cs", 12),
            pool("target_hit_pmc_de", 23),
            pool("target_hit_pmc_el", 5),
            pool("target_hit_pmc_es", 11),
            pool("target_hit_pmc_fi", 5),
            pool("target_hit_pmc_fr", 30),
            pool("target_hit_pmc_he", 10),
            pool("target_hit_pmc_hu", 3),
            pool("target_hit_pmc_it", 25),
            pool("target_hit_pmc_ja", 5),
            pool("target_hit_pmc_nl", 17),
            pool("target_hit_pmc_pl", 7),
            pool("target_hit_pmc_ro", 5),
            pool("target_hit_pmc_sv", 4),
            pool("target_hit_pmc_th", 2),
            pool("target_hit_pmc_tr", 8),
            pool("target_hit_pmc_uk", 15),
            pool("target_hit_pmc_zh", 8),
            pool("target_hit_ru", 29),
            pool("target_hit_us", 20),
            pool("target_nopen_pmc", 8),
            pool("target_nopen_pmc_cs", 4),
            pool("target_nopen_pmc_de", 30),
            pool("target_nopen_pmc_es", 14),
            pool("target_nopen_pmc_fr", 30),
            pool("target_nopen_pmc_he", 4),
            pool("target_nopen_pmc_it", 17),
            pool("target_nopen_pmc_ja", 2),
            pool("target_nopen_pmc_nl", 3),
            pool("target_nopen_pmc_ro", 4),
            pool("target_nopen_pmc_sv", 1),
            pool("target_nopen_pmc_th", 1),
            pool("target_nopen_pmc_tr", 2),
            pool("target_nopen_ru", 17),
            pool("target_nopen_us", 17),
            pool("target_plane_pmc", 13),
            pool("target_plane_pmc_cs", 8),
            pool("target_plane_pmc_de", 29),
            pool("target_plane_pmc_el", 4),
            pool("target_plane_pmc_es", 8),
            pool("target_plane_pmc_fi", 4),
            pool("target_plane_pmc_fr", 2),
            pool("target_plane_pmc_he", 2),
            pool("target_plane_pmc_hu", 4),
            pool("target_plane_pmc_it", 3),
            pool("target_plane_pmc_nl", 4),
            pool("target_plane_pmc_pl", 4),
            pool("target_plane_pmc_sv", 4),
            pool("target_plane_pmc_th", 3),
            pool("target_plane_pmc_tr", 4),
            pool("target_plane_pmc_uk", 7),
            pool("target_plane_pmc_zh", 7),
            pool("target_plane_ru", 8),
            pool("target_plane_us", 10),
            pool("target_ship_pmc", 12),
            pool("target_ship_ru", 10),
            pool("target_ship_us", 9),
            pool("target_tank_pmc", 25),
            pool("target_tank_pmc_cs", 22),
            pool("target_tank_pmc_de", 25),
            pool("target_tank_pmc_el", 6),
            pool("target_tank_pmc_es", 4),
            pool("target_tank_pmc_fi", 9),
            pool("target_tank_pmc_fr", 5),
            pool("target_tank_pmc_he", 15),
            pool("target_tank_pmc_hu", 12),
            pool("target_tank_pmc_it", 9),
            pool("target_tank_pmc_ja", 5),
            pool("target_tank_pmc_nl", 10),
            pool("target_tank_pmc_pl", 19),
            pool("target_tank_pmc_ro", 7),
            pool("target_tank_pmc_tr", 12),
            pool("target_tank_pmc_uk", 7),
            pool("target_tank_pmc_zh", 6),
            pool("target_tank_ru", 13),
            pool("target_tank_us", 30),
            pool("traverse_left_pmc_de", 3),
            pool("traverse_left_pmc_es", 1),
            pool("traverse_left_us", 4),
            pool("traverse_right_pmc_de", 3),
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
            pool("unit_repair_pmc_fi", 1),
            pool("unit_repair_pmc_fr", 3),
            pool("unit_repair_pmc_he", 4),
            pool("unit_repair_ru", 4),
            pool("unit_repair_us", 3),
            pool("vehicle_bail_air_pmc", 3),
            pool("vehicle_bail_air_ru", 2),
            pool("vehicle_bail_air_us", 3),
            pool("vehicle_bail_pmc", 4),
            pool("vehicle_bail_pmc_de", 14),
            pool("vehicle_bail_pmc_es", 5),
            pool("vehicle_bail_pmc_fr", 6),
            pool("vehicle_bail_pmc_it", 1),
            pool("vehicle_bail_ru", 4),
            pool("vehicle_bail_us", 9),
            pool("vehicle_cannon_shoot_pmc", 30),
            pool("vehicle_cannon_shoot_pmc_cs", 25),
            pool("vehicle_cannon_shoot_pmc_cs_panicked", 30),
            pool("vehicle_cannon_shoot_pmc_de", 30),
            pool("vehicle_cannon_shoot_pmc_de_panicked", 30),
            pool("vehicle_cannon_shoot_pmc_el", 7),
            pool("vehicle_cannon_shoot_pmc_el_panicked", 11),
            pool("vehicle_cannon_shoot_pmc_es", 13),
            pool("vehicle_cannon_shoot_pmc_es_panicked", 16),
            pool("vehicle_cannon_shoot_pmc_fi", 16),
            pool("vehicle_cannon_shoot_pmc_fi_panicked", 16),
            pool("vehicle_cannon_shoot_pmc_fr", 30),
            pool("vehicle_cannon_shoot_pmc_fr_panicked", 30),
            pool("vehicle_cannon_shoot_pmc_he", 6),
            pool("vehicle_cannon_shoot_pmc_he_panicked", 19),
            pool("vehicle_cannon_shoot_pmc_hu", 12),
            pool("vehicle_cannon_shoot_pmc_hu_panicked", 23),
            pool("vehicle_cannon_shoot_pmc_it", 14),
            pool("vehicle_cannon_shoot_pmc_it_panicked", 30),
            pool("vehicle_cannon_shoot_pmc_ja", 6),
            pool("vehicle_cannon_shoot_pmc_ja_panicked", 6),
            pool("vehicle_cannon_shoot_pmc_nl", 22),
            pool("vehicle_cannon_shoot_pmc_nl_panicked", 30),
            pool("vehicle_cannon_shoot_pmc_panicked", 30),
            pool("vehicle_cannon_shoot_pmc_pl", 30),
            pool("vehicle_cannon_shoot_pmc_pl_panicked", 30),
            pool("vehicle_cannon_shoot_pmc_ro", 4),
            pool("vehicle_cannon_shoot_pmc_ro_panicked", 9),
            pool("vehicle_cannon_shoot_pmc_sv", 13),
            pool("vehicle_cannon_shoot_pmc_sv_panicked", 14),
            pool("vehicle_cannon_shoot_pmc_th_panicked", 2),
            pool("vehicle_cannon_shoot_pmc_tr", 30),
            pool("vehicle_cannon_shoot_pmc_tr_panicked", 16),
            pool("vehicle_cannon_shoot_pmc_uk", 12),
            pool("vehicle_cannon_shoot_pmc_uk_panicked", 8),
            pool("vehicle_cannon_shoot_pmc_zh_panicked", 13),
            pool("vehicle_cannon_shoot_ru", 26),
            pool("vehicle_cannon_shoot_ru_panicked", 30),
            pool("vehicle_cannon_shoot_us", 30),
            pool("vehicle_cannon_shoot_us_panicked", 30),
            pool("vehicle_critical_air_pmc", 21),
            pool("vehicle_critical_air_ru", 12),
            pool("vehicle_critical_air_us", 21),
            pool("vehicle_critical_pmc", 30),
            pool("vehicle_critical_pmc_cs", 30),
            pool("vehicle_critical_pmc_de", 30),
            pool("vehicle_critical_pmc_el", 30),
            pool("vehicle_critical_pmc_es", 30),
            pool("vehicle_critical_pmc_fi", 30),
            pool("vehicle_critical_pmc_fr", 30),
            pool("vehicle_critical_pmc_he", 30),
            pool("vehicle_critical_pmc_hu", 30),
            pool("vehicle_critical_pmc_it", 30),
            pool("vehicle_critical_pmc_ja", 17),
            pool("vehicle_critical_pmc_nl", 30),
            pool("vehicle_critical_pmc_pl", 30),
            pool("vehicle_critical_pmc_ro", 30),
            pool("vehicle_critical_pmc_sv", 30),
            pool("vehicle_critical_pmc_th", 30),
            pool("vehicle_critical_pmc_tr", 30),
            pool("vehicle_critical_pmc_uk", 30),
            pool("vehicle_critical_pmc_zh", 30),
            pool("vehicle_critical_ru", 30),
            pool("vehicle_critical_us", 30),
            pool("vehicle_hit_air_ru", 1),
            pool("vehicle_hit_air_us", 4),
            pool("vehicle_hit_pmc", 30),
            pool("vehicle_hit_pmc_cs", 19),
            pool("vehicle_hit_pmc_de", 30),
            pool("vehicle_hit_pmc_el", 8),
            pool("vehicle_hit_pmc_es", 8),
            pool("vehicle_hit_pmc_fi", 13),
            pool("vehicle_hit_pmc_fr", 19),
            pool("vehicle_hit_pmc_he", 7),
            pool("vehicle_hit_pmc_hu", 7),
            pool("vehicle_hit_pmc_it", 24),
            pool("vehicle_hit_pmc_ja", 8),
            pool("vehicle_hit_pmc_nl", 2),
            pool("vehicle_hit_pmc_pl", 30),
            pool("vehicle_hit_pmc_ro", 21),
            pool("vehicle_hit_pmc_sv", 14),
            pool("vehicle_hit_pmc_th", 2),
            pool("vehicle_hit_pmc_tr", 13),
            pool("vehicle_hit_pmc_uk", 7),
            pool("vehicle_hit_pmc_zh", 9),
            pool("vehicle_hit_ru", 30),
            pool("vehicle_hit_us", 30),
            pool("vehicle_idle_air_ru", 30),
            pool("vehicle_idle_air_us", 30),
            pool("vehicle_idle_pmc", 30),
            pool("vehicle_idle_pmc_cs", 7),
            pool("vehicle_idle_pmc_de", 8),
            pool("vehicle_idle_pmc_el", 5),
            pool("vehicle_idle_pmc_fi", 6),
            pool("vehicle_idle_pmc_fr", 2),
            pool("vehicle_idle_pmc_he", 6),
            pool("vehicle_idle_pmc_hu", 6),
            pool("vehicle_idle_pmc_it", 6),
            pool("vehicle_idle_pmc_ja", 2),
            pool("vehicle_idle_pmc_pl", 6),
            pool("vehicle_idle_pmc_ro", 6),
            pool("vehicle_idle_pmc_sv", 1),
            pool("vehicle_idle_pmc_th", 4),
            pool("vehicle_idle_pmc_tr", 6),
            pool("vehicle_idle_pmc_uk", 6),
            pool("vehicle_idle_pmc_zh", 6),
            pool("vehicle_idle_ru", 30),
            pool("vehicle_idle_us", 30),
            pool("vehicle_low_health_air_pmc", 10),
            pool("vehicle_low_health_air_ru", 1),
            pool("vehicle_low_health_air_us", 10),
            pool("vehicle_low_health_pmc", 5),
            pool("vehicle_low_health_pmc_fr", 1),
            pool("vehicle_low_health_pmc_it", 2),
            pool("vehicle_low_health_pmc_uk", 4),
            pool("vehicle_low_health_ru", 5),
            pool("vehicle_low_health_us", 3),
            pool("vehicle_mg_shoot_pmc", 7),
            pool("vehicle_mg_shoot_pmc_panicked", 2),
            pool("vehicle_mg_shoot_ru", 7),
            pool("vehicle_mg_shoot_ru_panicked", 2),
            pool("vehicle_mg_shoot_us", 6),
            pool("vehicle_mg_shoot_us_panicked", 2),
            pool("vehicle_support_air_pmc", 2),
            pool("vehicle_support_air_pmc_de", 6),
            pool("vehicle_support_air_pmc_fr", 9),
            pool("vehicle_support_air_us", 1),
            pool("vehicle_support_mortar_pmc", 13),
            pool("vehicle_support_mortar_pmc_cs", 15),
            pool("vehicle_support_mortar_pmc_de", 30),
            pool("vehicle_support_mortar_pmc_el", 12),
            pool("vehicle_support_mortar_pmc_es", 7),
            pool("vehicle_support_mortar_pmc_fi", 7),
            pool("vehicle_support_mortar_pmc_fr", 29),
            pool("vehicle_support_mortar_pmc_he", 7),
            pool("vehicle_support_mortar_pmc_hu", 7),
            pool("vehicle_support_mortar_pmc_it", 12),
            pool("vehicle_support_mortar_pmc_ja", 1),
            pool("vehicle_support_mortar_pmc_nl", 14),
            pool("vehicle_support_mortar_pmc_pl", 19),
            pool("vehicle_support_mortar_pmc_ro", 7),
            pool("vehicle_support_mortar_pmc_sv", 7),
            pool("vehicle_support_mortar_pmc_th", 5),
            pool("vehicle_support_mortar_pmc_tr", 7),
            pool("vehicle_support_mortar_pmc_uk", 10),
            pool("vehicle_support_mortar_pmc_zh", 6),
            pool("vehicle_support_mortar_ru", 19),
            pool("vehicle_support_mortar_us", 18),
            pool("vehicle_targeted_pmc", 4),
            pool("vehicle_targeted_pmc_cs", 2),
            pool("vehicle_targeted_pmc_de", 12),
            pool("vehicle_targeted_pmc_el", 5),
            pool("vehicle_targeted_pmc_es", 6),
            pool("vehicle_targeted_pmc_fr", 10),
            pool("vehicle_targeted_pmc_he", 4),
            pool("vehicle_targeted_pmc_hu", 1),
            pool("vehicle_targeted_pmc_ja", 4),
            pool("vehicle_targeted_pmc_nl", 2),
            pool("vehicle_targeted_pmc_pl", 5),
            pool("vehicle_targeted_pmc_ro", 2),
            pool("vehicle_targeted_pmc_sv", 6),
            pool("vehicle_targeted_pmc_th", 1),
            pool("vehicle_targeted_pmc_tr", 5),
            pool("vehicle_targeted_pmc_uk", 6),
            pool("vehicle_targeted_pmc_zh", 5),
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
    public static final DeferredHolder<SoundEvent, SoundEvent> INTERACT_BEEP = register("interact_beep");
    /** Quick-command wheel: right-click / back (pair with {@link #INTERACT_BEEP} for left-click). */
    public static final DeferredHolder<SoundEvent, SoundEvent> INTERACT_BEEP_BACK = register("interact_beep_back");
    /** Quick-wheel formation WIDTH/LENGTH scroll tick. */
    public static final DeferredHolder<SoundEvent, SoundEvent> SCALE = register("scale");

    /** Vehicle lock / unlock. */
    public static final DeferredHolder<SoundEvent, SoundEvent> LOCK = register("lock");

    /** FOB scramble siren; one ~0.9 s clip, re-triggered every {@code ThreatEvaluator.ALARM_TICKS}. */
    public static final DeferredHolder<SoundEvent, SoundEvent> FOB_ALARM = register("fob_alarm");

    /** FPAS radar: azimuth slew loop, open/close hydraulics loop, the brake when either stops, new contact. */
    public static final DeferredHolder<SoundEvent, SoundEvent> FPAS_AZIMUTH = register("fpas_azimuth");
    public static final DeferredHolder<SoundEvent, SoundEvent> FPAS_HYDRAULICS = register("fpas_hydraulics");
    public static final DeferredHolder<SoundEvent, SoundEvent> FPAS_STOP = register("fpas_stop");
    public static final DeferredHolder<SoundEvent, SoundEvent> FPAS_TARGET = register("fpas_target");

    /** Grace period start popup sting. */
    public static final DeferredHolder<SoundEvent, SoundEvent> GRACE_START = register("grace_start");

    /** World-map Attack-this-unit confirm. */
    public static final DeferredHolder<SoundEvent, SoundEvent> ATTACK = register("attack");

    private ModSounds() {}

    private static DeferredHolder<SoundEvent, SoundEvent> register(String name) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(TaczSewv.MODID, name);
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(id));
    }

    private static SoundPool pool(String prefix, int count) {
        List<DeferredHolder<SoundEvent, SoundEvent>> variants = new ArrayList<>(count);
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
        private final List<DeferredHolder<SoundEvent, SoundEvent>> variants;
        private final int[] bag;
        private int cursor;
        private int last = -1;
        private short[] ticks;
        private static final int DEFAULT_CLIP_TICKS = 50;

        private SoundPool(String prefix, List<DeferredHolder<SoundEvent, SoundEvent>> variants) {
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
