package com.neoalive.tacz_sewv;

import com.neoalive.tacz_sewv.command.SewvCommand;
import com.neoalive.tacz_sewv.compat.BerezkaStructureCompat;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.init.ModEntities;
import com.neoalive.tacz_sewv.init.ModItems;
import com.neoalive.tacz_sewv.init.ModSounds;
import com.neoalive.tacz_sewv.util.ChunkTicketSweep;
import com.neoalive.tacz_sewv.util.OwnedVehicleTracker;
import com.neoalive.tacz_sewv.util.SupportSpawner;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.procedural.events.AsymmetricInvasionEvent;
import com.neoalive.tacz_sewv.procedural.events.ConvoyEvent;
import com.neoalive.tacz_sewv.procedural.events.LargeCombatEvent;
import com.neoalive.tacz_sewv.procedural.events.NavalBattleEvent;
import com.neoalive.tacz_sewv.procedural.events.DerelictVehicleEvent;
import com.neoalive.tacz_sewv.procedural.events.MortarShellingEvent;
import com.neoalive.tacz_sewv.util.NpcArmor;
import com.mojang.logging.LogUtils;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.procedural.events.DynamicEventManager;
import net.minecraftforge.common.MinecraftForge;
import com.neoalive.tacz_sewv.entity.ai.VehicleTargeting;
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
import org.slf4j.Logger;

@Mod(TaczSewv.MODID)
public class TaczSewv {
    public static final String MODID = "tacz_sewv";
    private static final Logger LOGGER = LogUtils.getLogger();

    public TaczSewv() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::commonSetup);
        ModItems.ITEMS.register(modEventBus);
        ModSounds.SOUNDS.register(modEventBus);
        ModEntities.register(modEventBus);
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(ChunkTicketSweep.class);
        // Server-side half of the map markers: it only ever SENDS, so it is registered
        // unconditionally — a client with no map mod simply ignores the packet.
        MinecraftForge.EVENT_BUS.register(OwnedVehicleTracker.class);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SewvConfig.SPEC);
        // SEM is loaded before this bridge (see mods.toml), so these become normal
        // SEM dynamic events: they are listed, can be toggled, and can be forced by SEM.
        DynamicEventManager.registerEvent(new ConvoyEvent());
        DynamicEventManager.registerEvent(new MortarShellingEvent());
        DynamicEventManager.registerEvent(new DerelictVehicleEvent());
        DynamicEventManager.registerEvent(new LargeCombatEvent());
        DynamicEventManager.registerEvent(new NavalBattleEvent());
        DynamicEventManager.registerEvent(new AsymmetricInvasionEvent());
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
    event.enqueueWork(() -> {
        NetworkHandler.register();
    });
    ChunkTicketSweep.register(event);
    // Soft compat: only touch berezka_api's classes when it is actually present, so the
    // structure-vehicle listener never classloads its event type on a berezka-less install.
    if (ModList.get().isLoaded(BerezkaStructureCompat.MODID)) {
        BerezkaStructureCompat.register();
    }
    LOGGER.info("SEM<->SW vehicle bridge loading");
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        SewvCommand.register(event.getDispatcher());
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
    }

    // Every unit reaches the world through here, whichever door it came in by, which is what makes
    // this the one place that can armor all of them. See NpcArmor.
    @SubscribeEvent
    public void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide) return;
        if (event.getEntity() instanceof AbstractUnit unit) {
            NpcArmor.issue(unit);
            // Every spawn path surfaces a unit here, so this is also the one place a squad can pick up
            // a medic/engineer companion regardless of which door it came in by.
            SupportSpawner.maybeSpawnCompanions(unit);
        }
    }
}
