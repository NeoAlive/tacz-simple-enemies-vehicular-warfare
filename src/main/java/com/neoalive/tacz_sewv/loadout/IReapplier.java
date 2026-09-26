package com.neoalive.tacz_sewv.loadout;

import java.util.Map;

import com.google.gson.JsonElement;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

/** Mixed onto SEM's {@code UnitLoadoutManager} so the layer can be re-applied without a {@code /reload}. */
public interface IReapplier {

    void sewv$reapply(Map<ResourceLocation, JsonElement> files, ResourceManager resources);
}
