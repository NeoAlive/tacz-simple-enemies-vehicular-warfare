package com.neoalive.tacz_sewv.entity.client.pmc_commander;

import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.resources.ResourceLocation;

import com.neoalive.tacz_sewv.TaczSewv;

public final class PmcCommanderModelLayers {

    private PmcCommanderModelLayers() {}

    public static final ModelLayerLocation PMC_COMMANDER_LAYER =
            new ModelLayerLocation(new ResourceLocation(TaczSewv.MODID, "pmc_commander_layer"), "main");
}
