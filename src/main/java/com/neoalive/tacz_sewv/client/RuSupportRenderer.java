package com.neoalive.tacz_sewv.client;

import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.nekoyuni.SimpleEnemyMod.entity.client.ru_unit.RUunitRenderer;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;

import com.neoalive.tacz_sewv.client.skin.CrewSkinRegistry;

/**
 * SEM's RU unit renderer with a configurable support-unit skin via {@link CrewSkinRegistry}.
 */
public class RuSupportRenderer extends RUunitRenderer {

    public RuSupportRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    /**
     * Camo pool, then role default from {@code config/tacz_sewv/unit_skins/}. The
     * {@code MixinUnitRenderer} inject cannot cover these: this override never calls super.
     */
    @Override
    public ResourceLocation getTextureLocation(RUunitEntity entity) {
        ResourceLocation skin = CrewSkinRegistry.bodySkin(entity);
        return skin != null ? skin : super.getTextureLocation(entity);
    }
}
