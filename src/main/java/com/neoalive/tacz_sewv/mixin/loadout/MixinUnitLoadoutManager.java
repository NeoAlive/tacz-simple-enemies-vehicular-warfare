package com.neoalive.tacz_sewv.mixin.loadout;

import java.util.Map;

import com.google.gson.JsonElement;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.nekoyuni.SimpleEnemyMod.event.common.UnitLoadoutManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.loadout.IReapplier;
import com.neoalive.tacz_sewv.loadout.LoadoutManager;

/**
 * The single seam of the loadout manager: SEM's raw {@code unit_loadouts} files, before ANY parser
 * reads them. Deliberately upstream of SEM Extended (which cancels the per-entry parse lambda and
 * {@code getRandomLoadout}) and of the Berezka config mod (which registers a virtual file at
 * SEM's own path) — both end up in this map, so both are seen and neither is fought.
 *
 * <p>Targets SEM's typed {@code apply}, not the SRG bridge {@code m_5787_}; {@code remap = false}
 * because it is SEM's own method, exactly as SEM Extended does it. Lives in its own optional
 * config so a SEM build without this class logs a warning instead of failing to start.
 */
@Mixin(value = UnitLoadoutManager.class, remap = false)
public abstract class MixinUnitLoadoutManager implements IReapplier {

    @Shadow
    protected abstract void apply(Map<ResourceLocation, JsonElement> files, ResourceManager resources,
                                  ProfilerFiller profiler);

    @Inject(method = "apply", at = @At("HEAD"), remap = false)
    private void tacz_sewv$layer(Map<ResourceLocation, JsonElement> files, ResourceManager resources,
                                 ProfilerFiller profiler, CallbackInfo ci) {
        LoadoutManager.onApply(this, files, resources);
    }

    @Override
    public void sewv$reapply(Map<ResourceLocation, JsonElement> files, ResourceManager resources) {
        apply(files, resources, InactiveProfiler.INSTANCE);
    }
}
