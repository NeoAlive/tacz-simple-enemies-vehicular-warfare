package com.neoalive.tacz_sewv.client;

import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.block.RunwayBlockEntity;

/** Mast and dish sitting on top of the baked runway cube. */
public class RunwayBlockModel extends GeoModel<RunwayBlockEntity> {

    private static final ResourceLocation GEO =
            new ResourceLocation(TaczSewv.MODID, "geo/runway_block.geo.json");
    private static final ResourceLocation TEXTURE =
            new ResourceLocation(TaczSewv.MODID, "textures/block/runway_mast.png");
    private static final ResourceLocation ANIM =
            new ResourceLocation(TaczSewv.MODID, "animations/runway_block.animation.json");

    @Override
    public ResourceLocation getModelResource(RunwayBlockEntity animatable) {
        return GEO;
    }

    @Override
    public ResourceLocation getTextureResource(RunwayBlockEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(RunwayBlockEntity animatable) {
        return ANIM;
    }
}
