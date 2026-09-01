package com.neoalive.tacz_sewv.init;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.item.DoctrineLedgerItem;
import com.neoalive.tacz_sewv.item.HandheldRadioItem;
import com.neoalive.tacz_sewv.item.LockItem;
import com.neoalive.tacz_sewv.item.MedalOfHonorItem;
import com.neoalive.tacz_sewv.item.PoolClipboardItem;
import com.neoalive.tacz_sewv.item.TacticalDataTerminalItem;

/**
 * The bridge's own items. Creative listing lives in {@link ModCreativeTabs} (one tab);
 * {@code trench_x_cross} stays registered for junctions but is omitted from that tab.
 */
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

    /** Promotes an owned PMC to Commander. Consumed on use. */
    public static final RegistryObject<Item> MEDAL_OF_HONOR =
            ITEMS.register("medal_of_honor", MedalOfHonorItem::new);

    /** Tags a vehicle so RU/US infantry will not board it. */
    public static final RegistryObject<Item> LOCK =
            ITEMS.register("lock", LockItem::new);

    /** Creative-tab admin clipboard for per-world vehicle spawn pools. Op-gated, uncraftable. */
    public static final RegistryObject<Item> POOL_CLIPBOARD =
            ITEMS.register("pool_clipboard", PoolClipboardItem::new);

    public static final RegistryObject<Item> CAPTURE_POINT = ITEMS.register("capture_point",
            () -> new BlockItem(ModBlocks.CAPTURE_POINT.get(), new Item.Properties()));

    public static final RegistryObject<Item> TEAM_BASE = ITEMS.register("team_base",
            () -> new BlockItem(ModBlocks.TEAM_BASE.get(), new Item.Properties()));

    public static final RegistryObject<Item> TRENCH = ITEMS.register("trench",
            () -> new BlockItem(ModBlocks.TRENCH.get(), new Item.Properties()));

    /** Junction mesh only — registered, not in the creative tab. */
    public static final RegistryObject<Item> TRENCH_X_CROSS = ITEMS.register("trench_x_cross",
            () -> new BlockItem(ModBlocks.TRENCH_X_CROSS.get(), new Item.Properties()));

    public static final RegistryObject<Item> FOXHOLE = ITEMS.register("foxhole",
            () -> new BlockItem(ModBlocks.FOXHOLE.get(), new Item.Properties()));

    public static final RegistryObject<Item> EMPLACEMENT = ITEMS.register("emplacement_block",
            () -> new BlockItem(ModBlocks.EMPLACEMENT.get(), new Item.Properties()));

    public static final RegistryObject<Item> SANDBAG = ITEMS.register("sandbag",
            () -> new BlockItem(ModBlocks.SANDBAG.get(), new Item.Properties()));

    public static final RegistryObject<Item> RUNWAY = ITEMS.register("runway_block",
            () -> new BlockItem(ModBlocks.RUNWAY.get(), new Item.Properties()));

    public static final RegistryObject<Item> SPAWN_PROBE = ITEMS.register("spawn_probe",
            () -> new BlockItem(ModBlocks.SPAWN_PROBE.get(), new Item.Properties()));

    public static final RegistryObject<Item> QUARTERS_BENCH = ITEMS.register("quarters_bench",
            () -> new BlockItem(ModBlocks.QUARTERS_BENCH.get(), new Item.Properties()));

    public static final RegistryObject<Item> PARKING_FIELD = ITEMS.register("parking_field",
            () -> new BlockItem(ModBlocks.PARKING_FIELD.get(), new Item.Properties()));

    public static final RegistryObject<Item> STOCKPILE_AMMO = ITEMS.register("stockpile_ammo",
            () -> new BlockItem(ModBlocks.STOCKPILE_AMMO.get(), new Item.Properties()));

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
    /** Debug/testing only — the Commander never appears in a random faction spawn pool. */
    public static final RegistryObject<Item> PMC_COMMANDER_SPAWN_EGG =
            ITEMS.register("pmc_commander_spawn_egg",
                    () -> new ForgeSpawnEggItem(ModEntities.PMC_COMMANDER, 0x2b2b2b, 0xffd700,
                            new Item.Properties()));
}
