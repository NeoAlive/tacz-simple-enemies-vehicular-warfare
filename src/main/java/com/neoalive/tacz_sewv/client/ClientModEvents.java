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
 * MOD bus, client dist. Hangs this mod's two extra render layers on all three SEM unit renderers.
 *
 * <p>Both exist for the same reason: SEM's renderers only know how to draw SEM's own kit.
 * {@link BedrockArmorLayer} covers armor that supplies its own model — only PMC's renderer has any
 * armor layer of SEM's at all, so RU/US armor would otherwise be equipped and invisible.
 * {@link SmallArmsLayer} covers SuperbWarfare guns — SEM's one held-item layer returns immediately
 * unless the item is a <em>TACZ</em> gun, so an issued launcher would fire from an empty hand.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientModEvents {

    @SubscribeEvent
    public static void onAddLayers(EntityRenderersEvent.AddLayers event) {
        addUnitLayers(event, ModEntities.PMCUNIT.get());
        addUnitLayers(event, ModEntities.RUUNIT.get());
        addUnitLayers(event, ModEntities.USUNIT.get());
    }

    private static <T extends LivingEntity> void addUnitLayers(EntityRenderersEvent.AddLayers event, EntityType<T> type) {
        LivingEntityRenderer<T, EntityModel<T>> renderer = event.getRenderer(type);
        if (renderer != null) {
            renderer.addLayer(new BedrockArmorLayer<>(renderer));
            renderer.addLayer(new SmallArmsLayer<>(renderer));
        }
    }
}
