package com.neoalive.tacz_sewv.init;

import com.neoalive.tacz_sewv.TaczSewv;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Radio call-outs played in the world at the calling observer's position. */
public final class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, TaczSewv.MODID);

    public static final RegistryObject<SoundEvent> MORTAR_AFFIRMATIVE = register("mortar_affirmative");
    public static final RegistryObject<SoundEvent> FREE_FIRE = register("free_fire");

    // Radio traffic from inside a vehicle, substituted for SimpleEnemyMod's shouted infantry
    // voicelines by MixinUnitVoicelines. A closed hull should sound like a radio, not like a man
    // standing in a field.
    //
    // Two pools per faction, matching the audio that exists: DAMAGED (the unit is hit) and
    // IDENTIFIED (it has spotted a target). Each event lists several .ogg variants in sounds.json,
    // which is how Minecraft gives one SoundEvent random variation per play — the same trick SEM
    // uses for its own voicelines.
    //
    // There is deliberately NO death event: no death audio was recorded, and a crew dying inside a
    // vehicle is simply silent (see MixinUnitVoicelines). PMC has no lines of its own and uses the
    // US pool.
    public static final RegistryObject<SoundEvent> RADIO_RU_DAMAGED = register("radio_ru_damaged");
    public static final RegistryObject<SoundEvent> RADIO_RU_IDENTIFIED = register("radio_ru_identified");
    public static final RegistryObject<SoundEvent> RADIO_US_DAMAGED = register("radio_us_damaged");
    public static final RegistryObject<SoundEvent> RADIO_US_IDENTIFIED = register("radio_us_identified");

    private ModSounds() {}

    private static RegistryObject<SoundEvent> register(String name) {
        ResourceLocation id = new ResourceLocation(TaczSewv.MODID, name);
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(id));
    }
}
