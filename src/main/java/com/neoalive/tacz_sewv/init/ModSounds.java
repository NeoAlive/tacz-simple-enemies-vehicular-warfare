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
    // PLACEHOLDERS: all three currently point at .ogg files this mod already ships for the radio
    // item, so the feature is audible and testable without new audio. Swapping in real recordings
    // is one line each in assets/tacz_sewv/sounds.json and touches no Java.
    public static final RegistryObject<SoundEvent> RADIO_HURT = register("radio_hurt");
    public static final RegistryObject<SoundEvent> RADIO_DEATH = register("radio_death");
    public static final RegistryObject<SoundEvent> RADIO_CONTACT = register("radio_contact");

    private ModSounds() {}

    private static RegistryObject<SoundEvent> register(String name) {
        ResourceLocation id = new ResourceLocation(TaczSewv.MODID, name);
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(id));
    }
}
