package com.neoalive.tacz_sewv.init;

import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.block.CapturePointBlock;
import com.neoalive.tacz_sewv.block.EmplacementBlock;
import com.neoalive.tacz_sewv.block.FoxholeBlock;
import com.neoalive.tacz_sewv.block.RunwayBlock;
import com.neoalive.tacz_sewv.block.SandbagBlock;
import com.neoalive.tacz_sewv.block.SpawnProbeBlock;
import com.neoalive.tacz_sewv.block.TeamBaseBlock;
import com.neoalive.tacz_sewv.block.TrenchBlock;
import com.neoalive.tacz_sewv.block.TrenchXCrossBlock;

public class ModBlocks {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, TaczSewv.MODID);

    public static final RegistryObject<Block> CAPTURE_POINT =
            BLOCKS.register("capture_point", CapturePointBlock::new);

    public static final RegistryObject<Block> TEAM_BASE =
            BLOCKS.register("team_base", TeamBaseBlock::new);

    public static final RegistryObject<Block> TRENCH =
            BLOCKS.register("trench", TrenchBlock::new);

    /** Manual {@code +} junction — not auto-selected by trench connections. */
    public static final RegistryObject<Block> TRENCH_X_CROSS =
            BLOCKS.register("trench_x_cross", TrenchXCrossBlock::new);

    public static final RegistryObject<Block> FOXHOLE =
            BLOCKS.register("foxhole", FoxholeBlock::new);

    /** Ammo pad under a mortar / TOW / FIXED mount. */
    public static final RegistryObject<Block> EMPLACEMENT =
            BLOCKS.register("emplacement_block", EmplacementBlock::new);

    /** Directional sandbag fighting position with a one-person seat. */
    public static final RegistryObject<Block> SANDBAG =
            BLOCKS.register("sandbag", SandbagBlock::new);

    /** PMC player-defined strip marker. */
    public static final RegistryObject<Block> RUNWAY =
            BLOCKS.register("runway_block", RunwayBlock::new);

    /** Structure-prep vehicle spawn marker (barrier-like, traversable). */
    public static final RegistryObject<Block> SPAWN_PROBE =
            BLOCKS.register("spawn_probe", SpawnProbeBlock::new);
}
