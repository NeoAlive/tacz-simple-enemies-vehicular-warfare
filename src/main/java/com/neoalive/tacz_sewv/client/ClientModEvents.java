package com.neoalive.tacz_sewv.client;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.init.ModEntities;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.nekoyuni.SimpleEnemyMod.entity.client.ru_unit.RUunitRenderer;
import net.nekoyuni.SimpleEnemyMod.entity.client.us_unit.USunitRenderer;

/**
 * MOD bus, client dist. Registers renderers for this mod's support-unit entities (reusing SEM's own
 * faction renderers — a renderer for the supertype draws the subtype fine) and hangs the two extra
 * render layers on all of them plus SEM's three unit renderers.
 *
 * <p>Both layers exist because SEM's renderers only know how to draw SEM's own kit.
 * {@link BedrockArmorLayer} covers armor that supplies its own model (SBW's kit) — without it RU/US
 * armor is equipped and invisible; {@link SmallArmsLayer} covers SuperbWarfare guns, which is what
 * draws an engineer's repair tool (SEM's held-item layer returns immediately unless the item is a
 * TACZ gun).
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientModEvents {

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.RU_MEDIC.get(), RUunitRenderer::new);
        event.registerEntityRenderer(ModEntities.US_MEDIC.get(), USunitRenderer::new);
        event.registerEntityRenderer(ModEntities.RU_ENGINEER.get(), RUunitRenderer::new);
        event.registerEntityRenderer(ModEntities.US_ENGINEER.get(), USunitRenderer::new);
    }

    @SubscribeEvent
    public static void onAddLayers(EntityRenderersEvent.AddLayers event) {
        addUnitLayers(event, net.nekoyuni.SimpleEnemyMod.registry.ModEntities.PMCUNIT.get());
        addUnitLayers(event, net.nekoyuni.SimpleEnemyMod.registry.ModEntities.RUUNIT.get());
        addUnitLayers(event, net.nekoyuni.SimpleEnemyMod.registry.ModEntities.USUNIT.get());
        addUnitLayers(event, ModEntities.RU_MEDIC.get());
        addUnitLayers(event, ModEntities.US_MEDIC.get());
        addUnitLayers(event, ModEntities.RU_ENGINEER.get());
        addUnitLayers(event, ModEntities.US_ENGINEER.get());
    }

    private static <T extends LivingEntity> void addUnitLayers(EntityRenderersEvent.AddLayers event, EntityType<T> type) {
        LivingEntityRenderer<T, EntityModel<T>> renderer = event.getRenderer(type);
        if (renderer != null) {
            renderer.addLayer(new BedrockArmorLayer<>(renderer));
            renderer.addLayer(new SmallArmsLayer<>(renderer));
        }
    }
}
