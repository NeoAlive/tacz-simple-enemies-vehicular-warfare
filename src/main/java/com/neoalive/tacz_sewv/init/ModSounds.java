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

    private ModSounds() {}

    private static RegistryObject<SoundEvent> register(String name) {
        ResourceLocation id = new ResourceLocation(TaczSewv.MODID, name);
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(id));
    }
}
