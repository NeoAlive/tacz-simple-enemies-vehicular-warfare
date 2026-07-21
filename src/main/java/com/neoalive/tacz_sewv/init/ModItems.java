package com.neoalive.tacz_sewv.init;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.item.HandheldRadioItem;
import com.neoalive.tacz_sewv.item.TacticalDataTerminalItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * The bridge's own items. It owns no entities or vehicles — this is only for tools
 * that command SEM units working SW hardware.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, TaczSewv.MODID);

    public static final RegistryObject<Item> HANDHELD_RADIO =
            ITEMS.register("handheld_radio", HandheldRadioItem::new);

    public static final RegistryObject<Item> TACTICAL_DATA_TERMINAL =
            ITEMS.register("tactical_data_terminal", TacticalDataTerminalItem::new);

    // Spawn eggs for the support units. Background = faction tint, highlight = role (white medic,
    // orange engineer).
    public static final RegistryObject<Item> RU_MEDIC_SPAWN_EGG = ITEMS.register("ru_medic_spawn_egg",
            () -> new ForgeSpawnEggItem(ModEntities.RU_MEDIC, 0x4b5320, 0xffffff, new Item.Properties()));
    public static final RegistryObject<Item> US_MEDIC_SPAWN_EGG = ITEMS.register("us_medic_spawn_egg",
            () -> new ForgeSpawnEggItem(ModEntities.US_MEDIC, 0x7a7250, 0xffffff, new Item.Properties()));
    public static final RegistryObject<Item> RU_ENGINEER_SPAWN_EGG = ITEMS.register("ru_engineer_spawn_egg",
            () -> new ForgeSpawnEggItem(ModEntities.RU_ENGINEER, 0x4b5320, 0xffa000, new Item.Properties()));
    public static final RegistryObject<Item> US_ENGINEER_SPAWN_EGG = ITEMS.register("us_engineer_spawn_egg",
            () -> new ForgeSpawnEggItem(ModEntities.US_ENGINEER, 0x7a7250, 0xffa000, new Item.Properties()));

    @SubscribeEvent
    public static void addToCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(HANDHELD_RADIO);
            event.accept(TACTICAL_DATA_TERMINAL);
        }
        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            event.accept(RU_MEDIC_SPAWN_EGG);
            event.accept(US_MEDIC_SPAWN_EGG);
            event.accept(RU_ENGINEER_SPAWN_EGG);
            event.accept(US_ENGINEER_SPAWN_EGG);
        }
    }
}
