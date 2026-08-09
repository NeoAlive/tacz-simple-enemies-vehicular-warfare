package com.neoalive.tacz_sewv;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.procedural.events.DynamicEventManager;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.command.SewvCommand;
import com.neoalive.tacz_sewv.compat.BerezkaStructureCompat;
import com.neoalive.tacz_sewv.compat.OpenPacCompat;
import com.neoalive.tacz_sewv.config.ClientConfig;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.crew.NpcArmor;
import com.neoalive.tacz_sewv.crew.NpcNvg;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.utility.Doctrine;
import com.neoalive.tacz_sewv.entity.ai.utility.UtilityWeights;
import com.neoalive.tacz_sewv.init.ModBlockEntities;
import com.neoalive.tacz_sewv.init.ModBlocks;
import com.neoalive.tacz_sewv.init.ModEntities;
import com.neoalive.tacz_sewv.init.ModGameRules;
import com.neoalive.tacz_sewv.init.ModItems;
import com.neoalive.tacz_sewv.init.ModSounds;
import com.neoalive.tacz_sewv.map.OwnedVehicleTracker;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.procedural.events.AsymmetricInvasionEvent;
import com.neoalive.tacz_sewv.procedural.events.ConvoyEvent;
import com.neoalive.tacz_sewv.procedural.events.DerelictVehicleEvent;
import com.neoalive.tacz_sewv.procedural.events.LargeCombatEvent;
import com.neoalive.tacz_sewv.procedural.events.MortarShellingEvent;
import com.neoalive.tacz_sewv.procedural.events.NavalBattleEvent;
import com.neoalive.tacz_sewv.procedural.events.OverflightEvent;
import com.neoalive.tacz_sewv.spawn.SupportSpawner;
import com.neoalive.tacz_sewv.util.ChunkTicketSweep;

@Mod(TaczSewv.MODID)
public class TaczSewv {
    public static final String MODID = "tacz_sewv";
    private static final Logger LOGGER = LogUtils.getLogger();

    public TaczSewv() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::commonSetup);
        ModBlocks.BLOCKS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModSounds.SOUNDS.register(modEventBus);
        ModEntities.register(modEventBus);
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(ChunkTicketSweep.class);
        // Server-side half of the map markers: it only ever SENDS, so it is registered
        // unconditionally — a client with no map mod simply ignores the packet.
        MinecraftForge.EVENT_BUS.register(OwnedVehicleTracker.class);
        MinecraftForge.EVENT_BUS.register(com.neoalive.tacz_sewv.map.TrenchNetworksTracker.class);
        MinecraftForge.EVENT_BUS.register(com.neoalive.tacz_sewv.invasion.InvasionSession.class);
        MinecraftForge.EVENT_BUS.register(com.neoalive.tacz_sewv.invasion.InvasionHudTracker.class);
        MinecraftForge.EVENT_BUS.register(com.neoalive.tacz_sewv.entity.ai.command.CommandCoordinator.class);
        MinecraftForge.EVENT_BUS.register(com.neoalive.tacz_sewv.crew.PlayerJoinHandler.class);
        MinecraftForge.EVENT_BUS.register(com.neoalive.tacz_sewv.util.VehicleDrops.class);
        MinecraftForge.EVENT_BUS.register(com.neoalive.tacz_sewv.invasion.SweepAdvancement.class);
        com.neoalive.tacz_sewv.debug.GunCacheProbe.registerBootProbe();
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, SewvConfig.SPEC);
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
        // SEM is loaded before this bridge (see mods.toml), so these become normal
        // SEM dynamic events: they are listed, can be toggled, and can be forced by SEM.
        DynamicEventManager.registerEvent(new ConvoyEvent());
        DynamicEventManager.registerEvent(new MortarShellingEvent());
        DynamicEventManager.registerEvent(new DerelictVehicleEvent());
        DynamicEventManager.registerEvent(new LargeCombatEvent());
        DynamicEventManager.registerEvent(new NavalBattleEvent());
        DynamicEventManager.registerEvent(new AsymmetricInvasionEvent());
        DynamicEventManager.registerEvent(new OverflightEvent());
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
    event.enqueueWork(() -> {
        ModGameRules.bootstrap();
        NetworkHandler.register();
    });
    ChunkTicketSweep.register(event);
    // Soft compat: only touch berezka_api's classes when it is actually present, so the
    // structure-vehicle listener never classloads its event type on a berezka-less install.
    if (ModList.get().isLoaded(BerezkaStructureCompat.MODID)) {
        BerezkaStructureCompat.register();
    }
    // Soft compat: OpenPAC is compileOnly; only the facade may touch xaero.pac.*, and only
    // after isLoaded(). reportAvailability() never classloads Access when the mod is absent.
    OpenPacCompat.reportAvailability();
    LOGGER.info("SEM<->SW vehicle bridge loading");
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        SewvCommand.register(event.getDispatcher());
    }

    /**
     * The vehicle AI's utility weights are a datapack file, so they load and reload with the rest
     * of the server's data. See {@link com.neoalive.tacz_sewv.entity.ai.utility.UtilityWeights}.
     */
    @SubscribeEvent
    public void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new UtilityWeights.Loader());
    }

    /**
     * Snapshot SimpleEnemyMod's faction-friendly toggles before anything can tick.
     *
     * <p>This is the one safe moment to read another mod's config: every mod's config is baked by
     * now, and no entity has ticked yet. It cannot be done from {@code ModConfigEvent} — that fires
     * on the owning mod's bus, and the config is SEM's — and it must not be done from the AI itself,
     * because {@code ConfigValue.get()} throws while a config is unbaked and a modpack that defers
     * startup work (ModernFix et al.) can order things so the crew scans first. See
     * {@code VehicleTargeting.refreshFactionFriendlyFlags}.
     */
    @SubscribeEvent
    public void onServerAboutToStart(ServerAboutToStartEvent event) {
        VehicleTargeting.refreshFactionFriendlyFlags();
        // Same moment, same reason: doctrine presets are read from our own config, which is
        // equally unsafe to touch from an AI tick before it has been baked.
        Doctrine.refreshPresets();
    }

    // Every unit reaches the world through here, whichever door it came in by, which is what makes
    // this the one place that can armor all of them. See NpcArmor.
    @SubscribeEvent
    public void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide) return;
        if (event.getEntity() instanceof AbstractUnit unit) {
            NpcArmor.issue(unit);
            NpcNvg.issue(unit);
            // Every spawn path surfaces a unit here, so this is also the one place a squad can pick up
            // a medic/engineer companion regardless of which door it came in by.
            SupportSpawner.maybeSpawnCompanions(unit);
        }
    }
}
