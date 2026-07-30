package com.neoalive.tacz_sewv.init;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.block.CapturePointBlock;
import com.neoalive.tacz_sewv.block.TeamBaseBlock;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModBlocks {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, TaczSewv.MODID);

    public static final RegistryObject<Block> CAPTURE_POINT =
            BLOCKS.register("capture_point", CapturePointBlock::new);

    public static final RegistryObject<Block> TEAM_BASE =
            BLOCKS.register("team_base", TeamBaseBlock::new);
}
