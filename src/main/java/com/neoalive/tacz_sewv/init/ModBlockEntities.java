package com.neoalive.tacz_sewv.init;

import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.block.CapturePointBlockEntity;
import com.neoalive.tacz_sewv.block.EmplacementBlockEntity;
import com.neoalive.tacz_sewv.block.FobDecorBlockEntity;
import com.neoalive.tacz_sewv.block.RunwayBlockEntity;
import com.neoalive.tacz_sewv.block.SandbagBlockEntity;
import com.neoalive.tacz_sewv.block.SpawnProbeBlockEntity;
import com.neoalive.tacz_sewv.block.StockpileBlockEntity;
import com.neoalive.tacz_sewv.block.TeamBaseBlockEntity;

public class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, TaczSewv.MODID);

    public static final RegistryObject<BlockEntityType<CapturePointBlockEntity>> CAPTURE_POINT =
            BLOCK_ENTITIES.register("capture_point",
                    () -> BlockEntityType.Builder.of(CapturePointBlockEntity::new, ModBlocks.CAPTURE_POINT.get())
                            .build(null));

    public static final RegistryObject<BlockEntityType<TeamBaseBlockEntity>> TEAM_BASE =
            BLOCK_ENTITIES.register("team_base",
                    () -> BlockEntityType.Builder.of(TeamBaseBlockEntity::new, ModBlocks.TEAM_BASE.get())
                            .build(null));

    public static final RegistryObject<BlockEntityType<EmplacementBlockEntity>> EMPLACEMENT =
            BLOCK_ENTITIES.register("emplacement_block",
                    () -> BlockEntityType.Builder.of(EmplacementBlockEntity::new, ModBlocks.EMPLACEMENT.get())
                            .build(null));

    public static final RegistryObject<BlockEntityType<SandbagBlockEntity>> SANDBAG =
            BLOCK_ENTITIES.register("sandbag",
                    () -> BlockEntityType.Builder.of(SandbagBlockEntity::new, ModBlocks.SANDBAG.get())
                            .build(null));

    public static final RegistryObject<BlockEntityType<RunwayBlockEntity>> RUNWAY =
            BLOCK_ENTITIES.register("runway_block",
                    () -> BlockEntityType.Builder.of(RunwayBlockEntity::new, ModBlocks.RUNWAY.get())
                            .build(null));

    public static final RegistryObject<BlockEntityType<SpawnProbeBlockEntity>> SPAWN_PROBE =
            BLOCK_ENTITIES.register("spawn_probe",
                    () -> BlockEntityType.Builder.of(SpawnProbeBlockEntity::new, ModBlocks.SPAWN_PROBE.get())
                            .build(null));

    public static final RegistryObject<BlockEntityType<StockpileBlockEntity>> STOCKPILE =
            BLOCK_ENTITIES.register("stockpile_ammo",
                    () -> BlockEntityType.Builder.of(StockpileBlockEntity::new, ModBlocks.STOCKPILE_AMMO.get())
                            .build(null));

    public static final RegistryObject<BlockEntityType<FobDecorBlockEntity>> FOB_DECOR =
            BLOCK_ENTITIES.register("fob_decor",
                    () -> BlockEntityType.Builder.of(FobDecorBlockEntity::new,
                                    ModBlocks.QUARTERS_BENCH.get(), ModBlocks.PARKING_FIELD.get())
                            .build(null));
}
