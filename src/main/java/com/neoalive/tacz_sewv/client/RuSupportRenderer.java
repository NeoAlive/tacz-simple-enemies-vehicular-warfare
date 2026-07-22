package com.neoalive.tacz_sewv.client;

import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.nekoyuni.SimpleEnemyMod.entity.client.ru_unit.RUunitRenderer;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;

/**
 * SEM's RU unit renderer with a fixed skin, so a medic or engineer is distinguishable from the
 * riflemen around it.
 *
 * <p>Subclassing rather than reimplementing keeps SEM's model, death animation and cull distance;
 * the only thing a support unit needs to differ on is {@code getTextureLocation}, which SEM
 * otherwise resolves from its own per-variant texture array.
 */
public class RuSupportRenderer extends RUunitRenderer {

    private final ResourceLocation texture;

    public RuSupportRenderer(EntityRendererProvider.Context context, ResourceLocation texture) {
        super(context);
        this.texture = texture;
    }

    @Override
    public ResourceLocation getTextureLocation(RUunitEntity entity) {
        return this.texture;
    }
}
