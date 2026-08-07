package com.neoalive.tacz_sewv.init;

import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.block.CapturePointBlockEntity;
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
}
