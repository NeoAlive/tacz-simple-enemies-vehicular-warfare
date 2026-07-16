package com.neoalive.tacz_sewv;

import com.neoalive.tacz_sewv.command.SewvCommand;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.init.ModItems;
import com.neoalive.tacz_sewv.init.ModSounds;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.procedural.events.ConvoyEvent;
import com.neoalive.tacz_sewv.procedural.events.MortarShellingEvent;
import com.mojang.logging.LogUtils;
import net.nekoyuni.SimpleEnemyMod.procedural.events.DynamicEventManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
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
        MinecraftForge.EVENT_BUS.register(this);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SewvConfig.SPEC);
        // SEM is loaded before this bridge (see mods.toml), so these become normal
        // SEM dynamic events: they are listed, can be toggled, and can be forced by SEM.
        DynamicEventManager.registerEvent(new ConvoyEvent());
        DynamicEventManager.registerEvent(new MortarShellingEvent());
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
    event.enqueueWork(() -> {
        NetworkHandler.register();
    });
    LOGGER.info("SEM<->SW vehicle bridge loading");
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        SewvCommand.register(event.getDispatcher());
    }
}
