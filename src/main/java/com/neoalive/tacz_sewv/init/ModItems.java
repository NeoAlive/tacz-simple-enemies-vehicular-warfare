package com.neoalive.tacz_sewv.init;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.item.DoctrineLedgerItem;
import com.neoalive.tacz_sewv.item.FobBlockItem;
import com.neoalive.tacz_sewv.item.HandheldRadioItem;
import com.neoalive.tacz_sewv.item.HelipadBlockItem;
import com.neoalive.tacz_sewv.item.LockItem;
import com.neoalive.tacz_sewv.item.MedalOfHonorItem;
import com.neoalive.tacz_sewv.item.PoolClipboardItem;
import com.neoalive.tacz_sewv.item.TacticalDataTerminalItem;
import com.neoalive.tacz_sewv.item.armor.PmcHelmetMichItem;

/**
 * The bridge's own items. Creative listing lives in {@link ModCreativeTabs} (one tab);
 * {@code trench_x_cross}, {@code spawn_probe}, and the FOB blocks stay registered for
 * {@code /give} but are omitted from that tab.
 */
public class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(BuiltInRegistries.ITEM, TaczSewv.MODID);

    public static final DeferredHolder<Item, Item> HANDHELD_RADIO =
            ITEMS.register("handheld_radio", HandheldRadioItem::new);

    /** PMC default helmet — PASGT-equivalent stats, own MICH bedrock model. */
    public static final DeferredHolder<Item, Item> PMC_HELMET_MICH =
            ITEMS.register("pmc_helmet_mich", PmcHelmetMichItem::new);

    public static final DeferredHolder<Item, Item> TACTICAL_DATA_TERMINAL =
            ITEMS.register("tactical_data_terminal", TacticalDataTerminalItem::new);

    /** The doctrine editor's access key. Handed out once per player; craft another to respec. */
    public static final DeferredHolder<Item, Item> DOCTRINE_LEDGER =
            ITEMS.register("doctrine_ledger", DoctrineLedgerItem::new);

    /** Promotes an owned PMC to Commander. Consumed on use. */
    public static final DeferredHolder<Item, Item> MEDAL_OF_HONOR =
            ITEMS.register("medal_of_honor", MedalOfHonorItem::new);

    /** Tags a vehicle so RU/US infantry will not board it. */
    public static final DeferredHolder<Item, Item> LOCK =
            ITEMS.register("lock", LockItem::new);

    /** Creative-tab admin clipboard for per-world vehicle spawn pools. Op-gated, uncraftable. */
    public static final DeferredHolder<Item, Item> POOL_CLIPBOARD =
            ITEMS.register("pool_clipboard", PoolClipboardItem::new);

    public static final DeferredHolder<Item, Item> CAPTURE_POINT = ITEMS.register("capture_point",
            () -> new BlockItem(ModBlocks.CAPTURE_POINT.get(), new Item.Properties()));

    public static final DeferredHolder<Item, Item> TEAM_BASE = ITEMS.register("team_base",
            () -> new BlockItem(ModBlocks.TEAM_BASE.get(), new Item.Properties()));

    public static final DeferredHolder<Item, Item> TRENCH = ITEMS.register("trench",
            () -> new BlockItem(ModBlocks.TRENCH.get(), new Item.Properties()));

    /** Junction mesh only — registered, not in the creative tab. */
    public static final DeferredHolder<Item, Item> TRENCH_X_CROSS = ITEMS.register("trench_x_cross",
            () -> new BlockItem(ModBlocks.TRENCH_X_CROSS.get(), new Item.Properties()));

    public static final DeferredHolder<Item, Item> FOXHOLE = ITEMS.register("foxhole",
            () -> new BlockItem(ModBlocks.FOXHOLE.get(), new Item.Properties()));

    public static final DeferredHolder<Item, Item> EMPLACEMENT = ITEMS.register("emplacement_block",
            () -> new BlockItem(ModBlocks.EMPLACEMENT.get(), new Item.Properties()));

    public static final DeferredHolder<Item, Item> SANDBAG = ITEMS.register("sandbag",
            () -> new BlockItem(ModBlocks.SANDBAG.get(), new Item.Properties()));

    public static final DeferredHolder<Item, Item> RUNWAY = ITEMS.register("runway_block",
            () -> new BlockItem(ModBlocks.RUNWAY.get(), new Item.Properties()));

    public static final DeferredHolder<Item, Item> SPAWN_PROBE = ITEMS.register("spawn_probe",
            () -> new BlockItem(ModBlocks.SPAWN_PROBE.get(), new Item.Properties()));

    public static final DeferredHolder<Item, Item> QUARTERS_BENCH = ITEMS.register("quarters_bench",
            () -> new FobBlockItem(ModBlocks.QUARTERS_BENCH.get(), new Item.Properties()));

    public static final DeferredHolder<Item, Item> PARKING_FIELD = ITEMS.register("parking_field",
            () -> new FobBlockItem(ModBlocks.PARKING_FIELD.get(), new Item.Properties()));

    public static final DeferredHolder<Item, Item> STOCKPILE_AMMO = ITEMS.register("stockpile_ammo",
            () -> new FobBlockItem(ModBlocks.STOCKPILE_AMMO.get(), new Item.Properties()));

    public static final DeferredHolder<Item, Item> HELIPAD = ITEMS.register("helipad",
            () -> new HelipadBlockItem(ModBlocks.HELIPAD.get(), new Item.Properties()));

    // Spawn eggs for the support units. Background = faction tint, highlight = role (white medic,
    // orange engineer, brown combat engineer).
    public static final DeferredHolder<Item, Item> RU_MEDIC_SPAWN_EGG = ITEMS.register("ru_medic_spawn_egg",
            () -> new DeferredSpawnEggItem(ModEntities.RU_MEDIC, 0x4b5320, 0xffffff, new Item.Properties()));
    public static final DeferredHolder<Item, Item> US_MEDIC_SPAWN_EGG = ITEMS.register("us_medic_spawn_egg",
            () -> new DeferredSpawnEggItem(ModEntities.US_MEDIC, 0x7a7250, 0xffffff, new Item.Properties()));
    public static final DeferredHolder<Item, Item> RU_ENGINEER_SPAWN_EGG = ITEMS.register("ru_engineer_spawn_egg",
            () -> new DeferredSpawnEggItem(ModEntities.RU_ENGINEER, 0x4b5320, 0xffa000, new Item.Properties()));
    public static final DeferredHolder<Item, Item> US_ENGINEER_SPAWN_EGG = ITEMS.register("us_engineer_spawn_egg",
            () -> new DeferredSpawnEggItem(ModEntities.US_ENGINEER, 0x7a7250, 0xffa000, new Item.Properties()));
    public static final DeferredHolder<Item, Item> RU_COMBAT_ENGINEER_SPAWN_EGG =
            ITEMS.register("ru_combat_engineer_spawn_egg",
                    () -> new DeferredSpawnEggItem(ModEntities.RU_COMBAT_ENGINEER, 0x4b5320, 0x8b4513,
                            new Item.Properties()));
    public static final DeferredHolder<Item, Item> US_COMBAT_ENGINEER_SPAWN_EGG =
            ITEMS.register("us_combat_engineer_spawn_egg",
                    () -> new DeferredSpawnEggItem(ModEntities.US_COMBAT_ENGINEER, 0x7a7250, 0x8b4513,
                            new Item.Properties()));
    /** Debug/testing only — the Commander never appears in a random faction spawn pool. */
    public static final DeferredHolder<Item, Item> PMC_COMMANDER_SPAWN_EGG =
            ITEMS.register("pmc_commander_spawn_egg",
                    () -> new DeferredSpawnEggItem(ModEntities.PMC_COMMANDER, 0x2b2b2b, 0xffd700,
                            new Item.Properties()));
}
