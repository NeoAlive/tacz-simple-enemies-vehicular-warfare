package com.neoalive.tacz_sewv.client;

import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.nekoyuni.SimpleEnemyMod.entity.client.us_unit.USunitRenderer;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;

import com.neoalive.tacz_sewv.client.skin.CrewSkinRegistry;

/** US counterpart of {@link RuSupportRenderer} — SEM's US renderer with a fixed support-unit skin. */
public class UsSupportRenderer extends USunitRenderer {

    private final ResourceLocation texture;

    public UsSupportRenderer(EntityRendererProvider.Context context, ResourceLocation texture) {
        super(context);
        this.texture = texture;
    }

    @Override
    public ResourceLocation getTextureLocation(USunitEntity entity) {
        ResourceLocation pooled = CrewSkinRegistry.bodySkin(entity);
        return pooled != null ? pooled : this.texture;
    }
}
