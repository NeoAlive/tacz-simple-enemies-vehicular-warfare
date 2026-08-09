package com.neoalive.tacz_sewv.init;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.item.DoctrineLedgerItem;
import com.neoalive.tacz_sewv.item.HandheldRadioItem;
import com.neoalive.tacz_sewv.item.PoolClipboardItem;
import com.neoalive.tacz_sewv.item.TacticalDataTerminalItem;

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

    /** The doctrine editor's access key. Handed out once per player; craft another to respec. */
    public static final RegistryObject<Item> DOCTRINE_LEDGER =
            ITEMS.register("doctrine_ledger", DoctrineLedgerItem::new);

    /** Creative-tab admin clipboard for per-world vehicle spawn pools. Op-gated, uncraftable. */
    public static final RegistryObject<Item> POOL_CLIPBOARD =
            ITEMS.register("pool_clipboard", PoolClipboardItem::new);

    public static final RegistryObject<Item> CAPTURE_POINT = ITEMS.register("capture_point",
            () -> new BlockItem(ModBlocks.CAPTURE_POINT.get(), new Item.Properties()));

    public static final RegistryObject<Item> TEAM_BASE = ITEMS.register("team_base",
            () -> new BlockItem(ModBlocks.TEAM_BASE.get(), new Item.Properties()));

    public static final RegistryObject<Item> TRENCH = ITEMS.register("trench",
            () -> new BlockItem(ModBlocks.TRENCH.get(), new Item.Properties()));

    public static final RegistryObject<Item> TRENCH_X_CROSS = ITEMS.register("trench_x_cross",
            () -> new BlockItem(ModBlocks.TRENCH_X_CROSS.get(), new Item.Properties()));

    public static final RegistryObject<Item> FOXHOLE = ITEMS.register("foxhole",
            () -> new BlockItem(ModBlocks.FOXHOLE.get(), new Item.Properties()));

    public static final RegistryObject<Item> EMPLACEMENT = ITEMS.register("emplacement_block",
            () -> new BlockItem(ModBlocks.EMPLACEMENT.get(), new Item.Properties()));

    // Spawn eggs for the support units. Background = faction tint, highlight = role (white medic,
    // orange engineer, brown combat engineer).
    public static final RegistryObject<Item> RU_MEDIC_SPAWN_EGG = ITEMS.register("ru_medic_spawn_egg",
            () -> new ForgeSpawnEggItem(ModEntities.RU_MEDIC, 0x4b5320, 0xffffff, new Item.Properties()));
    public static final RegistryObject<Item> US_MEDIC_SPAWN_EGG = ITEMS.register("us_medic_spawn_egg",
            () -> new ForgeSpawnEggItem(ModEntities.US_MEDIC, 0x7a7250, 0xffffff, new Item.Properties()));
    public static final RegistryObject<Item> RU_ENGINEER_SPAWN_EGG = ITEMS.register("ru_engineer_spawn_egg",
            () -> new ForgeSpawnEggItem(ModEntities.RU_ENGINEER, 0x4b5320, 0xffa000, new Item.Properties()));
    public static final RegistryObject<Item> US_ENGINEER_SPAWN_EGG = ITEMS.register("us_engineer_spawn_egg",
            () -> new ForgeSpawnEggItem(ModEntities.US_ENGINEER, 0x7a7250, 0xffa000, new Item.Properties()));
    public static final RegistryObject<Item> RU_COMBAT_ENGINEER_SPAWN_EGG =
            ITEMS.register("ru_combat_engineer_spawn_egg",
                    () -> new ForgeSpawnEggItem(ModEntities.RU_COMBAT_ENGINEER, 0x4b5320, 0x8b4513,
                            new Item.Properties()));
    public static final RegistryObject<Item> US_COMBAT_ENGINEER_SPAWN_EGG =
            ITEMS.register("us_combat_engineer_spawn_egg",
                    () -> new ForgeSpawnEggItem(ModEntities.US_COMBAT_ENGINEER, 0x7a7250, 0x8b4513,
                            new Item.Properties()));

    @SubscribeEvent
    public static void addToCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(HANDHELD_RADIO);
            event.accept(TACTICAL_DATA_TERMINAL);
            event.accept(DOCTRINE_LEDGER);
            event.accept(POOL_CLIPBOARD);
        }
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(CAPTURE_POINT);
            event.accept(TEAM_BASE);
        }
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
            event.accept(TRENCH);
            event.accept(TRENCH_X_CROSS);
            event.accept(FOXHOLE);
            event.accept(EMPLACEMENT);
        }
        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            event.accept(RU_MEDIC_SPAWN_EGG);
            event.accept(US_MEDIC_SPAWN_EGG);
            event.accept(RU_ENGINEER_SPAWN_EGG);
            event.accept(US_ENGINEER_SPAWN_EGG);
            event.accept(RU_COMBAT_ENGINEER_SPAWN_EGG);
            event.accept(US_COMBAT_ENGINEER_SPAWN_EGG);
        }
    }
}
