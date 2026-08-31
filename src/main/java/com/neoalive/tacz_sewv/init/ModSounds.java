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

/** Radio call-outs played in the world at the calling observer's position. */
public final class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, TaczSewv.MODID);

    // Vehicle-crew voicelines, played by CrewRadio. Pools per faction -- DAMAGED (hull below 60% and
    // taking fire), SPOTTED (locked onto an enemy vehicle), ORDERS (player commanded the crew), BAIL
    // (crew abandoning a crippled hull), DECOY (popping smoke/flares), IFV (dropping its squad),
    // IDLE (chatter between fights) and TOW (a launcher crew firing a missile).
    // On-foot support lines: FIXING (engineer begins a repair), HEALING (medic begins treating),
    // DRONE (RU/US engineer deploys a drone — PMC has no autonomous drone path).
    // Each pool registers its variants as individual events so the code can pick a specific,
    // non-repeating clip; sounds.json maps each to one .ogg under sounds/<faction>/. RU/US ORDERS fire
    // on CommandCoordinator play commits; PMC player orders use the same line via PacketIssueOrder.
    // PMC IFV / PMC DRONE are absent.
    // PMC_MORTAR / PMC_CAS are not crew-radio pools: they are the handheld radio's acknowledgements
    // for a fire mission (HandheldRadioItem, RadioObserverGoal), picked by which support kinds
    // actually answered. PMC_TAKEOFF is a crew line, but only on a plane ordered to take off —
    // PacketHelicopterCommand, never idle flight / patrol / CAS / land.
    public static final SoundPool PMC_DAMAGED = pool("pmc_damaged", 6);
    public static final SoundPool PMC_SPOTTED = pool("pmc_spotted", 7);
    public static final SoundPool PMC_ORDERS  = pool("pmc_orders", 4);
    public static final SoundPool PMC_BAIL    = pool("pmc_bail", 3);
    public static final SoundPool PMC_DECOY   = pool("pmc_decoy", 2);
    public static final SoundPool PMC_IDLE    = pool("pmc_idle", 7);
    public static final SoundPool PMC_MORTAR  = pool("pmc_mortar", 5);
    public static final SoundPool PMC_CAS     = pool("pmc_cas", 5);
    public static final SoundPool PMC_TAKEOFF = pool("pmc_takeoff", 4);
    public static final SoundPool PMC_TOW     = pool("pmc_tow", 2);
    public static final SoundPool PMC_FIXING = pool("pmc_fixing", 2);
    public static final SoundPool PMC_HEALING = pool("pmc_healing", 3);
    public static final SoundPool PMC_NAVY_IDLE   = pool("pmc_navy_idle", 4);
    public static final SoundPool PMC_NAVY_TARGET = pool("pmc_navy_target", 5);
    public static final SoundPool RU_DAMAGED  = pool("ru_damaged", 3);
    public static final SoundPool RU_SPOTTED  = pool("ru_spotted", 11);
    public static final SoundPool RU_ORDERS   = pool("ru_orders", 4);
    public static final SoundPool RU_INVESTIGATING = pool("ru_investigating", 3);
    public static final SoundPool RU_AMMO_HEAT    = pool("ru_ammo_heat", 4);
    public static final SoundPool RU_AMMO_SABOT   = pool("ru_ammo_sabot", 4);
    public static final SoundPool RU_AMMO_GENERAL = pool("ru_ammo_general", 2);
    public static final SoundPool RU_BAIL     = pool("ru_bail", 2);
    public static final SoundPool RU_DECOY    = pool("ru_decoy", 3);
    public static final SoundPool RU_IFV      = pool("ru_ifv", 3);
    public static final SoundPool RU_IDLE     = pool("ru_idle", 7);
    public static final SoundPool RU_TOW      = pool("ru_tow", 3);
    public static final SoundPool RU_FIXING  = pool("ru_fixing", 3);
    public static final SoundPool RU_HEALING  = pool("ru_healing", 3);
    public static final SoundPool RU_DRONE    = pool("ru_drone", 3);
    public static final SoundPool RU_NAVY_IDLE    = pool("ru_navy_idle", 4);
    public static final SoundPool RU_NAVY_TARGET  = pool("ru_navy_target", 5);
    public static final SoundPool US_DAMAGED  = pool("us_damaged", 3);
    public static final SoundPool US_SPOTTED  = pool("us_spotted", 7);
    public static final SoundPool US_ORDERS   = pool("us_orders", 3);
    public static final SoundPool US_INVESTIGATING = pool("us_investigating", 3);
    public static final SoundPool US_AMMO_HEAT    = pool("us_ammo_heat", 2);
    public static final SoundPool US_AMMO_SABOT   = pool("us_ammo_sabot", 3);
    public static final SoundPool US_AMMO_GENERAL = pool("us_ammo_general", 1);
    public static final SoundPool US_BAIL     = pool("us_bail", 3);
    public static final SoundPool US_DECOY    = pool("us_decoy", 3);
    public static final SoundPool US_IFV      = pool("us_ifv", 3);
    public static final SoundPool US_IDLE     = pool("us_idle", 7);
    public static final SoundPool US_TOW      = pool("us_tow", 3);
    public static final SoundPool US_FIXING  = pool("us_fixing", 3);
    public static final SoundPool US_HEALING  = pool("us_healing", 2);
    public static final SoundPool US_DRONE    = pool("us_drone", 3);
    public static final SoundPool US_NAVY_IDLE    = pool("us_navy_idle", 5);
    public static final SoundPool US_NAVY_TARGET  = pool("us_navy_target", 5);

    // Ordnance acknowledgements for a radio fire mission that names a PlaneAttackMode. Separate from
    // PMC_CAS, which answers "aircraft are coming"; these answer "with THAT". AUTO gets none — it
    // picks per target, so there is no single thing to announce.
    public static final SoundPool PMC_ATS     = pool("pmc_ats", 2);
    public static final SoundPool PMC_BOMBING = pool("pmc_bombing", 2);
    public static final SoundPool PMC_CANNON  = pool("pmc_cannon", 2);

    // Refusal replies, one clip per reason, played by OrderFailure rather than through
    // CrewRadio.Line. They are single events and not pools because one line each is what was
    // recorded; a reason with no recording stays silent rather than borrowing another's clip.
    // PMC only: reporting needs an owner to report to, and RU/US have none.
    // Note PMC_UNDERGROUND breaks the pmc_target_* pattern the other five follow — the name here
    // must match the file on disk, not the pattern.
    public static final RegistryObject<SoundEvent> PMC_TARGET_GONE = register("pmc_target_gone");
    public static final RegistryObject<SoundEvent> PMC_TARGET_NOT_VEHICLE = register("pmc_target_not_vehicle");
    public static final RegistryObject<SoundEvent> PMC_TARGET_FRIENDLY = register("pmc_target_friendly");
    public static final RegistryObject<SoundEvent> PMC_TARGET_OBSTRUCTED = register("pmc_target_obstructed");
    public static final RegistryObject<SoundEvent> PMC_UNDERGROUND = register("pmc_underground");
    public static final RegistryObject<SoundEvent> PMC_SELF_IS_MEDIC = register("pmc_self_is_medic");

    /** UI click for the Tactical Data Terminal (and similar interactable panels). */
    public static final RegistryObject<SoundEvent> INTERACT_BEEP = register("interact_beep");

    /** Vehicle lock / unlock. */
    public static final RegistryObject<SoundEvent> LOCK = register("lock");

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
