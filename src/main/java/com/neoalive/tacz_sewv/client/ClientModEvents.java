package com.neoalive.tacz_sewv.client;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.init.ModEntities;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

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

    // Support-unit skins. These sit under assets/tacz_sewv/skins/ rather than textures/entity/ — a
    // texture ResourceLocation is just a path under the namespace, so the folder is free choice.
    private static final ResourceLocation RU_MEDIC_SKIN = skin("ru_squad_medic");
    private static final ResourceLocation US_MEDIC_SKIN = skin("us_squad_medic");
    private static final ResourceLocation RU_ENGINEER_SKIN = skin("ru_engineer");
    private static final ResourceLocation US_ENGINEER_SKIN = skin("us_engineer");

    private static ResourceLocation skin(String name) {
        return new ResourceLocation(TaczSewv.MODID, "skins/" + name + ".png");
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.RU_MEDIC.get(), ctx -> new RuSupportRenderer(ctx, RU_MEDIC_SKIN));
        event.registerEntityRenderer(ModEntities.US_MEDIC.get(), ctx -> new UsSupportRenderer(ctx, US_MEDIC_SKIN));
        event.registerEntityRenderer(ModEntities.RU_ENGINEER.get(), ctx -> new RuSupportRenderer(ctx, RU_ENGINEER_SKIN));
        event.registerEntityRenderer(ModEntities.US_ENGINEER.get(), ctx -> new UsSupportRenderer(ctx, US_ENGINEER_SKIN));
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
