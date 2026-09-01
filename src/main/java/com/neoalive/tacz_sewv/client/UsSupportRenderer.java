package com.neoalive.tacz_sewv.client;

import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.nekoyuni.SimpleEnemyMod.entity.client.us_unit.USunitRenderer;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;

import com.neoalive.tacz_sewv.client.skin.CrewSkinRegistry;

/** US counterpart of {@link RuSupportRenderer}. */
public class UsSupportRenderer extends USunitRenderer {

    public UsSupportRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public ResourceLocation getTextureLocation(USunitEntity entity) {
        ResourceLocation skin = CrewSkinRegistry.bodySkin(entity);
        return skin != null ? skin : super.getTextureLocation(entity);
    }
}
