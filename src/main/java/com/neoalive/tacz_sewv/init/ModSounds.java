package com.neoalive.tacz_sewv.init;

import java.util.ArrayList;
import java.util.List;
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

    // --- Crew / unit voicelines (CrewRadio) ---
    public static final SoundPool ORDER_DISPATCH_PMC = pool("order_dispatch_pmc", 2);
    public static final SoundPool ORDER_DISPATCH_RU = pool("order_dispatch_ru", 2);
    public static final SoundPool ORDER_DISPATCH_US = pool("order_dispatch_us", 2);

    public static final SoundPool TARGET_GENERIC_PMC = pool("target_generic_pmc", 10);
    public static final SoundPool TARGET_GENERIC_RU = pool("target_generic_ru", 10);
    public static final SoundPool TARGET_GENERIC_US = pool("target_generic_us", 10);
    public static final SoundPool TARGET_HELICOPTER_PMC = pool("target_helicopter_pmc", 10);
    public static final SoundPool TARGET_HELICOPTER_RU = pool("target_helicopter_ru", 10);
    public static final SoundPool TARGET_HELICOPTER_US = pool("target_helicopter_us", 10);
    public static final SoundPool TARGET_PLANE_PMC = pool("target_plane_pmc", 10);
    public static final SoundPool TARGET_PLANE_RU = pool("target_plane_ru", 10);
    public static final SoundPool TARGET_PLANE_US = pool("target_plane_us", 10);
    public static final SoundPool TARGET_SHIP_PMC = pool("target_ship_pmc", 10);
    public static final SoundPool TARGET_SHIP_RU = pool("target_ship_ru", 10);
    public static final SoundPool TARGET_SHIP_US = pool("target_ship_us", 9);
    public static final SoundPool TARGET_TANK_PMC = pool("target_tank_pmc", 10);
    public static final SoundPool TARGET_TANK_RU = pool("target_tank_ru", 9);
    public static final SoundPool TARGET_TANK_US = pool("target_tank_us", 10);

    public static final SoundPool UNIT_DEPLOY_DRONE_PMC = pool("unit_deploy_drone_pmc", 2);
    public static final SoundPool UNIT_DEPLOY_DRONE_RU = pool("unit_deploy_drone_ru", 2);
    public static final SoundPool UNIT_DEPLOY_DRONE_US = pool("unit_deploy_drone_us", 2);
    public static final SoundPool UNIT_DIG_RU = pool("unit_dig_ru", 2);
    public static final SoundPool UNIT_DIG_US = pool("unit_dig_us", 2);
    public static final SoundPool UNIT_HEAL_PMC = pool("unit_heal_pmc", 2);
    public static final SoundPool UNIT_HEAL_RU = pool("unit_heal_ru", 2);
    public static final SoundPool UNIT_HEAL_US = pool("unit_heal_us", 2);
    public static final SoundPool UNIT_REPAIR_PMC = pool("unit_repair_pmc", 2);
    public static final SoundPool UNIT_REPAIR_RU = pool("unit_repair_ru", 2);
    public static final SoundPool UNIT_REPAIR_US = pool("unit_repair_us", 2);

    public static final SoundPool VEHICLE_BAIL_PMC = pool("vehicle_bail_pmc", 3);
    public static final SoundPool VEHICLE_BAIL_RU = pool("vehicle_bail_ru", 3);
    public static final SoundPool VEHICLE_BAIL_US = pool("vehicle_bail_us", 3);
    public static final SoundPool VEHICLE_IDLE_PMC = pool("vehicle_idle_pmc", 10);
    public static final SoundPool VEHICLE_IDLE_RU = pool("vehicle_idle_ru", 10);
    public static final SoundPool VEHICLE_IDLE_US = pool("vehicle_idle_us", 9);
    public static final SoundPool VEHICLE_LOW_HEALTH_PMC = pool("vehicle_low_health_pmc", 4);
    public static final SoundPool VEHICLE_LOW_HEALTH_RU = pool("vehicle_low_health_ru", 2);
    public static final SoundPool VEHICLE_LOW_HEALTH_US = pool("vehicle_low_health_us", 3);

    public static final SoundPool VEHICLE_CANNON_SHOOT_PMC = pool("vehicle_cannon_shoot_pmc", 5);
    public static final SoundPool VEHICLE_CANNON_SHOOT_RU = pool("vehicle_cannon_shoot_ru", 3);
    public static final SoundPool VEHICLE_CANNON_SHOOT_US = pool("vehicle_cannon_shoot_us", 5);
    public static final SoundPool VEHICLE_CANNON_SHOOT_PMC_PANICKED = pool("vehicle_cannon_shoot_pmc_panicked", 2);
    public static final SoundPool VEHICLE_CANNON_SHOOT_RU_PANICKED = pool("vehicle_cannon_shoot_ru_panicked", 3);
    public static final SoundPool VEHICLE_CANNON_SHOOT_US_PANICKED = pool("vehicle_cannon_shoot_us_panicked", 2);

    public static final SoundPool VEHICLE_MG_SHOOT_PMC = pool("vehicle_mg_shoot_pmc", 4);
    public static final SoundPool VEHICLE_MG_SHOOT_RU = pool("vehicle_mg_shoot_ru", 2);
    public static final SoundPool VEHICLE_MG_SHOOT_US = pool("vehicle_mg_shoot_us", 4);
    public static final SoundPool VEHICLE_MG_SHOOT_PMC_PANICKED = pool("vehicle_mg_shoot_pmc_panicked", 2);
    public static final SoundPool VEHICLE_MG_SHOOT_RU_PANICKED = pool("vehicle_mg_shoot_ru_panicked", 2);
    public static final SoundPool VEHICLE_MG_SHOOT_US_PANICKED = pool("vehicle_mg_shoot_us_panicked", 2);

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
        return new SoundPool(variants);
    }

    /**
     * Shuffle bag: hands out every clip once, in a fresh random order, before any repeat -- so a
     * pool never clumps the way raw random does, nor sounds cyclic. The seam between two bags is
     * de-duped so a reshuffle can't repeat the clip that just played. One bag per pool, shared by
     * every hull; {@code synchronized} because AI on different threads could draw at once, and
     * allocation-free after construction (the bag is shuffled in place with {@link ThreadLocalRandom}).
     */
    public static final class SoundPool {
        private final List<RegistryObject<SoundEvent>> variants;
        private final int[] bag;
        private int cursor;
        private int last = -1;

        private SoundPool(List<RegistryObject<SoundEvent>> variants) {
            this.variants = variants;
            this.bag = new int[variants.size()];
            for (int i = 0; i < bag.length; i++) bag[i] = i;
            this.cursor = bag.length; // empty: the first draw reshuffles
        }

        public synchronized SoundEvent next() {
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
            return variants.get(last).get();
        }
    }
}
