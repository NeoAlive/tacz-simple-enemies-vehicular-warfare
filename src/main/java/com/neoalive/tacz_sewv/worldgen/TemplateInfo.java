package com.neoalive.tacz_sewv.worldgen;

import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.resources.ResourceLocation;

import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;

/**
 * What {@link ProbeCatalog} learned about one structure template that carries spawn probes.
 *
 * @param factions      factions named by the probes (RU/US/PMC)
 * @param explicitOwner RU/US when the template path says so ({@code forest_ru_comms}), else null
 */
public record TemplateInfo(ResourceLocation id, int probes, Set<TankFaction> factions,
                           @Nullable TankFaction explicitOwner, int vehicleProbes, int chests) {
}
