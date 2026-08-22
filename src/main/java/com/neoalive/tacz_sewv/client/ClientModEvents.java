package com.neoalive.tacz_sewv.client;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.client.skin.CrewSkinRegistry;
import com.neoalive.tacz_sewv.client.skin.VehicleSkinRegistry;
import com.neoalive.tacz_sewv.client.xaero.XaeroMapCompat;
import com.neoalive.tacz_sewv.entity.client.pmc_commander.PmcCommanderModel;
import com.neoalive.tacz_sewv.entity.client.pmc_commander.PmcCommanderModelLayers;
import com.neoalive.tacz_sewv.entity.client.pmc_commander.PmcCommanderRenderer;
import com.neoalive.tacz_sewv.init.ModBlockEntities;
import com.neoalive.tacz_sewv.init.ModEntities;

/**
 * MOD bus, client dist. Registers renderers for this mod's support-unit entities (reusing SEM's own
 * faction renderers — a renderer for the supertype draws the subtype fine) and hangs the extra
 * render layers on all of them plus SEM's three unit renderers.
 *
 * <p>The layers exist because SEM's renderers only know how to draw SEM's own kit.
 * {@link BedrockArmorLayer} covers armor that supplies its own model (SBW's kit) — without it RU/US
 * armor is equipped and invisible; {@link SmallArmsLayer} covers SuperbWarfare guns, which is what
 * draws an engineer's repair tool (SEM's held-item layer returns immediately unless the item is a
 * TACZ gun); {@link HolsterLayer} draws a TACZ body-holstered gun via FIXED/{@code offhand_show};
 * {@link CuriosHeadLayer} draws Curios head items (thermal goggles) that Curios' own
 * player-only layer never reaches on SEM units.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientModEvents {

    // Support-unit skins. These sit under assets/tacz_sewv/skins/ rather than textures/entity/ — a
    // texture ResourceLocation is just a path under the namespace, so the folder is free choice.
    private static final ResourceLocation RU_MEDIC_SKIN = skin("ru_squad_medic");
    private static final ResourceLocation US_MEDIC_SKIN = skin("us_squad_medic");
    private static final ResourceLocation RU_ENGINEER_SKIN = skin("ru_engineer");
    private static final ResourceLocation US_ENGINEER_SKIN = skin("us_engineer");
    private static final ResourceLocation RU_COMBAT_ENGINEER_SKIN = skin("ru_combat_engineer");
    private static final ResourceLocation US_COMBAT_ENGINEER_SKIN = skin("us_combat_engineer");

    private static ResourceLocation skin(String name) {
        return new ResourceLocation(TaczSewv.MODID, "skins/" + name + ".png");
    }

    /**
     * Soft compat: only touch Xaero's classes when the map mod is actually present, so its element
     * framework is never classloaded on an install without it. Same gate as berezka in
     * {@link com.neoalive.tacz_sewv.TaczSewv}.
     */
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        if (ModList.get().isLoaded(XaeroMapCompat.MODID)) {
            XaeroMapCompat.register();
        }
    }

    /**
     * Skins load as a reload listener rather than at client setup because seeding the config
     * folders enumerates the jar defaults through the {@link net.minecraft.server.packs.resources.ResourceManager},
     * which is not reliably populated that early. F3+T picking up edited PNGs comes free with it.
     */
    @SubscribeEvent
    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new SandbagSeatPose.Loader());
        event.registerReloadListener(new DownedUnitPose.Loader());
        event.registerReloadListener((ResourceManagerReloadListener) resources -> {
            VehicleSkinRegistry.reload(resources);
            CrewSkinRegistry.reload(resources);
        });
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.RUNWAY.get(), RunwayBlockRenderer::new);
        event.registerEntityRenderer(ModEntities.SANDBAG_SEAT.get(), NoopRenderer::new);
        event.registerEntityRenderer(ModEntities.RU_MEDIC.get(), ctx -> new RuSupportRenderer(ctx, RU_MEDIC_SKIN));
        event.registerEntityRenderer(ModEntities.US_MEDIC.get(), ctx -> new UsSupportRenderer(ctx, US_MEDIC_SKIN));
        event.registerEntityRenderer(ModEntities.RU_ENGINEER.get(), ctx -> new RuSupportRenderer(ctx, RU_ENGINEER_SKIN));
        event.registerEntityRenderer(ModEntities.US_ENGINEER.get(), ctx -> new UsSupportRenderer(ctx, US_ENGINEER_SKIN));
        event.registerEntityRenderer(ModEntities.RU_COMBAT_ENGINEER.get(),
                ctx -> new RuSupportRenderer(ctx, RU_COMBAT_ENGINEER_SKIN));
        event.registerEntityRenderer(ModEntities.US_COMBAT_ENGINEER.get(),
                ctx -> new UsSupportRenderer(ctx, US_COMBAT_ENGINEER_SKIN));
        event.registerEntityRenderer(ModEntities.PMC_COMMANDER.get(), PmcCommanderRenderer::new);
    }

    /** {@code PmcCommanderModel} is a genuinely new model, unlike the support units above (which
     * reuse SEM's own baked RU/US layers) — nothing else in this mod has needed its own layer
     * definition registration point before. */
    @SubscribeEvent
    public static void onRegisterLayers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(PmcCommanderModelLayers.PMC_COMMANDER_LAYER, PmcCommanderModel::createBodyLayer);
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
        addUnitLayers(event, ModEntities.RU_COMBAT_ENGINEER.get());
        addUnitLayers(event, ModEntities.US_COMBAT_ENGINEER.get());
        addUnitLayers(event, ModEntities.PMC_COMMANDER.get());
    }

    private static <T extends LivingEntity> void addUnitLayers(EntityRenderersEvent.AddLayers event, EntityType<T> type) {
        LivingEntityRenderer<T, EntityModel<T>> renderer = event.getRenderer(type);
        if (renderer != null) {
            renderer.addLayer(new BedrockArmorLayer<>(renderer));
            renderer.addLayer(new SmallArmsLayer<>(renderer));
            renderer.addLayer(new HolsterLayer<>(renderer));
            renderer.addLayer(new CuriosHeadLayer<>(renderer));
        }
    }
}
