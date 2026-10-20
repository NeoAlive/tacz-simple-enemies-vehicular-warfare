package com.neoalive.tacz_sewv;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.logging.LogUtils;
import net.minecraft.world.level.pathfinder.PathType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.nekoyuni.SimpleEnemyMod.procedural.events.DynamicEventManager;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.block.TrenchPathTypes;
import com.neoalive.tacz_sewv.command.SewvCommand;
import com.neoalive.tacz_sewv.compat.BerezkaStructureCompat;
import com.neoalive.tacz_sewv.compat.CorpseCompat;
import com.neoalive.tacz_sewv.compat.OpenPacCompat;
import com.neoalive.tacz_sewv.compat.PlayerReviveCompat;
import com.neoalive.tacz_sewv.config.ClientConfig;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.crew.NpcArmor;
import com.neoalive.tacz_sewv.crew.NpcIdentity;
import com.neoalive.tacz_sewv.crew.NpcNvg;
import com.neoalive.tacz_sewv.crew.NpcSbwWeapon;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.utility.Doctrine;
import com.neoalive.tacz_sewv.entity.ai.utility.UtilityWeights;
import com.neoalive.tacz_sewv.fob.FobTickHandler;
import com.neoalive.tacz_sewv.grace.GracePeriodData;
import com.neoalive.tacz_sewv.grace.GraceTickHandler;
import com.neoalive.tacz_sewv.init.ModBlockEntities;
import com.neoalive.tacz_sewv.init.ModBlocks;
import com.neoalive.tacz_sewv.init.ModCreativeTabs;
import com.neoalive.tacz_sewv.init.ModEntities;
import com.neoalive.tacz_sewv.init.ModGameRules;
import com.neoalive.tacz_sewv.init.ModItems;
import com.neoalive.tacz_sewv.init.ModMenus;
import com.neoalive.tacz_sewv.init.ModSounds;
import com.neoalive.tacz_sewv.map.MapIdentity;
import com.neoalive.tacz_sewv.map.OwnedVehicleTracker;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.procedural.events.AsymmetricInvasionEvent;
import com.neoalive.tacz_sewv.procedural.events.ConvoyEvent;
import com.neoalive.tacz_sewv.procedural.events.DerelictVehicleEvent;
import com.neoalive.tacz_sewv.procedural.events.LargeCombatEvent;
import com.neoalive.tacz_sewv.procedural.events.MortarShellingEvent;
import com.neoalive.tacz_sewv.procedural.events.NavalBattleEvent;
import com.neoalive.tacz_sewv.procedural.events.OverflightEvent;
import com.neoalive.tacz_sewv.skin.CamoSupport;
import com.neoalive.tacz_sewv.spawn.SupportSpawner;
import com.neoalive.tacz_sewv.util.ChunkTicketSweep;
import com.neoalive.tacz_sewv.util.WorldTargetPriority;

@Mod(TaczSewv.MODID)
public class TaczSewv {
    public static final String MODID = "tacz_sewv";
    public static final Logger LOGGER = LogUtils.getLogger();

    public TaczSewv(IEventBus modEventBus, ModContainer modContainer) {
        // On main (Forge) this grows BlockPathTypes before any Mob.<init>; on NeoForge 1.21.1 TRENCH
        // borrows a vanilla PathType and this is a no-op — see TrenchPathTypes.
        TrenchPathTypes.bootstrap();

        modEventBus.addListener(this::commonSetup);
        // NeoForge port: payloads, chunk-ticket controllers and capabilities register on mod-bus events.
        modEventBus.addListener(com.neoalive.tacz_sewv.platform.SimpleChannel::onRegisterPayloads);
        modEventBus.addListener(com.neoalive.tacz_sewv.platform.ForgeChunkManager::onRegisterControllers);
        modEventBus.addListener(TaczSewv::registerCapabilities);
        ModBlocks.BLOCKS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        ModMenus.MENUS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModCreativeTabs.CREATIVE_TABS.register(modEventBus);
        ModSounds.SOUNDS.register(modEventBus);
        ModEntities.register(modEventBus);
        // Soft compat: MineColonies is compileOnly; only MineColoniesCompat and the two gated
        // mixins may touch com.minecolonies.*, and only after isLoaded(). It goes here rather
        // than in commonSetup because half of what it registers is the colony event type, and
        // RegisterEvent has already fired by the time common setup runs.
        if (ModList.get().isLoaded(com.neoalive.tacz_sewv.compat.MineColoniesCompat.MODID)) {
            com.neoalive.tacz_sewv.compat.MineColoniesCompat.register(modEventBus);
        }
        NeoForge.EVENT_BUS.register(this);
        NeoForge.EVENT_BUS.register(ChunkTicketSweep.class);
        NeoForge.EVENT_BUS.register(com.neoalive.tacz_sewv.util.ChunkTicketCoverage.class);
        NeoForge.EVENT_BUS.register(com.neoalive.tacz_sewv.entity.ai.core.ObbHitIndex.class);
        NeoForge.EVENT_BUS.register(com.neoalive.tacz_sewv.entity.ai.support.UnitLod.class);
        NeoForge.EVENT_BUS.register(com.neoalive.tacz_sewv.crew.UnitJoinBudget.class);
        // Server-side half of the map markers: it only ever SENDS, so it is registered
        // unconditionally — a client with no map mod simply ignores the packet.
        NeoForge.EVENT_BUS.register(OwnedVehicleTracker.class);
        NeoForge.EVENT_BUS.register(MapIdentity.class);
        NeoForge.EVENT_BUS.register(FobTickHandler.class);
        NeoForge.EVENT_BUS.register(GraceTickHandler.class);
        NeoForge.EVENT_BUS.register(com.neoalive.tacz_sewv.invasion.InvasionSession.class);
        NeoForge.EVENT_BUS.register(com.neoalive.tacz_sewv.invasion.InvasionHudTracker.class);
        NeoForge.EVENT_BUS.register(com.neoalive.tacz_sewv.entity.ai.command.CommandCoordinator.class);
        NeoForge.EVENT_BUS.register(com.neoalive.tacz_sewv.entity.ai.sensor.CombatantIndex.class);
        NeoForge.EVENT_BUS.register(com.neoalive.tacz_sewv.entity.ai.command.platoon.PlatoonRegistry.class);
        NeoForge.EVENT_BUS.register(com.neoalive.tacz_sewv.crew.PlayerJoinHandler.class);
        NeoForge.EVENT_BUS.register(com.neoalive.tacz_sewv.util.VehicleDrops.class);
        NeoForge.EVENT_BUS.register(com.neoalive.tacz_sewv.util.CreativeAmmoConsumable.class);
        NeoForge.EVENT_BUS.register(com.neoalive.tacz_sewv.territory.TerritoryManager.class);
        NeoForge.EVENT_BUS.register(com.neoalive.tacz_sewv.territory.AdvancePlanManager.class);
        NeoForge.EVENT_BUS.register(com.neoalive.tacz_sewv.territory.LegacySweepCleanup.class);
        NeoForge.EVENT_BUS.register(com.neoalive.tacz_sewv.order.OrderReport.class);
        // NeoForge port: ModContainer.registerConfig returns void; the tracker call it wraps hands back
        // the ModConfig, whose file path the debug dump reports (ConfigTracker no longer maps names).
        com.neoalive.tacz_sewv.config.ConfigPersistence.remember(ModConfig.Type.SERVER,
                net.neoforged.fml.config.ConfigTracker.INSTANCE.registerConfig(ModConfig.Type.SERVER, SewvConfig.SPEC, modContainer));
        modContainer.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
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

    /**
     * NeoForge port: capabilities are registered per type. SEM registers its PMC inventory for its own
     * {@code pmcunit} type only, so the commander (a {@code PmcUnitEntity} of our own type) needs the
     * same registration; the stockpile replaces its Forge {@code getCapability} override.
     */
    private static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerEntity(Capabilities.ItemHandler.ENTITY, ModEntities.PMC_COMMANDER.get(),
                (unit, context) -> unit.getInventory());
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.STOCKPILE.get(),
                (stockpile, side) -> stockpile.getItems());
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
    event.enqueueWork(() -> {
        ModGameRules.bootstrap();
        NetworkHandler.register();
        com.neoalive.tacz_sewv.command.quick.QuickCommandRegistry.init();
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
    // Soft compat: PlayerReviveMod is compileOnly; only PlayerReviveCompat may touch
    // team.creative.playerrevive.*, and only after isLoaded().
    PlayerReviveCompat.reportAvailability();
    // Soft compat: Corpse is compileOnly; only CorpseCompat.Access may touch
    // de.maxhenkel.corpse.*, and only after isLoaded().
    CorpseCompat.reportAvailability();
    // Limitless Vehicles: LvCompat imports no LV type, so this is safe with the mod absent.
    com.neoalive.tacz_sewv.compat.lv.LvCompat.reportAvailability();
    com.neoalive.tacz_sewv.compat.NeoArmsCompat.bootstrap();
    // MixinVehicleDamageRedirect yields the DamageModifier.compute call to DNV's own redirect
    // (two @Redirects on one call is a load-time crash), so say what that turns off.
    if (ModList.get().isLoaded("doggiesnewvehicle")) {
        LOGGER.warn("[sewv] Doggie's New Vehicle owns SBW's vehicle damage hook - TaCZ ballistic "
                + "translation and mob melee damage against vehicles are inactive on this install.");
    }
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
        // Helicopter airframe rows (physics rework); see com.neoalive.tacz_sewv.heli.data.HeliAirframes.
        event.addListener(new com.neoalive.tacz_sewv.heli.data.HeliAirframes.Loader());
        // Which formation each play role takes; see com.neoalive.tacz_sewv.entity.ai.formation.FormationTemplates.
        event.addListener(new com.neoalive.tacz_sewv.entity.ai.formation.FormationTemplates.Loader());
        // See com.neoalive.tacz_sewv.ballistics.TranslationTable.
        event.addListener(new com.neoalive.tacz_sewv.ballistics.TranslationTable.Loader());
        // See com.neoalive.tacz_sewv.crew.NamePools.
        event.addListener(new com.neoalive.tacz_sewv.crew.NamePools.Loader());
        event.addListener(new com.neoalive.tacz_sewv.crew.LogoPoolIndex.Loader());
        // Which structure templates carry spawn probes; see com.neoalive.tacz_sewv.worldgen.ProbeCatalog.
        event.addListener(new com.neoalive.tacz_sewv.worldgen.ProbeCatalog());
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
        WorldTargetPriority.refreshCatalog();
    }

    /**
     * SEM's loadout files are parsed before any level exists, so the world's loadout layer cannot be
     * merged in on that first pass — apply it now. See {@code loadout/LoadoutManager}.
     */
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        // Dev only (-Dsewv.mixinAudit, set by ./gradlew runServer -PmixinAudit): force-load every mixin
        // target so a bad injection fails here rather than the first time that class is touched.
        if (Boolean.getBoolean("sewv.mixinAudit")) {
            LOGGER.info("[sewv] mixin audit: loading every mixin target");
            org.spongepowered.asm.mixin.MixinEnvironment.getCurrentEnvironment().audit();
            LOGGER.info("[sewv] mixin audit: complete");
        }
        com.neoalive.tacz_sewv.loadout.LoadoutManager.reapply(event.getServer());
    }

    // Every unit reaches the world through here, whichever door it came in by, which is what makes
    // this the one place that can armor all of them. See NpcArmor.
    @SubscribeEvent
    public void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide) return;
        if (event.getEntity() instanceof AbstractUnit unit) {
            // Grace period: RU/US units baked into a Berezka structure never reach the level.
            if (GracePeriodData.active(event.getLevel())
                    && ModList.get().isLoaded(BerezkaStructureCompat.MODID)
                    && BerezkaStructureCompat.refusesOnJoin(event.getLevel(), unit)) {
                event.setCanceled(true);
                return;
            }
            // Prefer trench floors (TrenchPathTypes.TRENCH malus 0) over open ground.
            unit.setPathfindingMalus(PathType.WALKABLE, TrenchPathTypes.OPEN_GROUND_MALUS);
            NpcSbwWeapon.issue(unit, event.loadedFromDisk());
            NpcArmor.issue(unit);
            CamoSupport.issue(unit);
            NpcNvg.issue(unit);
            if (unit instanceof PmcUnitEntity pmc) NpcIdentity.issue(pmc);
            SupportSpawner.maybeSpawnCompanions(unit);
        } else if (event.getEntity() instanceof VehicleEntity hull) {
            CamoSupport.retrofitHull(hull);
        }
    }
}
