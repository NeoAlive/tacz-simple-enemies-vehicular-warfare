package com.neoalive.tacz_sewv.client;

import com.neoalive.tacz_sewv.TaczSewv;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.nekoyuni.SimpleEnemyMod.registry.ModEntities;

/**
 * MOD bus, client dist. Hangs {@link BedrockArmorLayer} on all three SEM unit renderers.
 *
 * <p>Only PMC's renderer has any armor layer of SEM's own; RU and US add nothing but the gun layer,
 * so without this their armor would be equipped and invisible.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientModEvents {

    @SubscribeEvent
    public static void onAddLayers(EntityRenderersEvent.AddLayers event) {
        addArmorLayer(event, ModEntities.PMCUNIT.get());
        addArmorLayer(event, ModEntities.RUUNIT.get());
        addArmorLayer(event, ModEntities.USUNIT.get());
    }

    private static <T extends LivingEntity> void addArmorLayer(EntityRenderersEvent.AddLayers event, EntityType<T> type) {
        LivingEntityRenderer<T, EntityModel<T>> renderer = event.getRenderer(type);
        if (renderer != null) {
            renderer.addLayer(new BedrockArmorLayer<>(renderer));
        }
    }
}
