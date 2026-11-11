package com.neoalive.tacz_sewv.client;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

import javax.annotation.Nullable;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.resource.GsonUtil;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.resource.pojo.BedrockModelPOJO;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.baked.BakedBedrockModel;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.baked.BakerOptions;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BakedModelInstance;
import com.google.gson.JsonParseException;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.init.ModBlocks;

public final class FobBlockClient {

    public enum Kind {
        COMMAND("command_block", ModBlocks.QUARTERS_BENCH.get()),
        PARKING("parking_field", ModBlocks.PARKING_FIELD.get()),
        STOCKPILE("stockpile_block", ModBlocks.STOCKPILE_AMMO.get()),
        /** Kept live so {@link ZoneBeaconRenderer} can slew them. */
        // Not under geo/: GeckoLib parses every file there and crashes on Blockbench poly meshes.
        FPAS("models/bedrock/block/", "fpas", ModBlocks.ZONE_BEACON.get(), "radar", "dish");

        final ResourceLocation geo;
        final ResourceLocation texture;
        final Block block;
        final java.util.Set<String> animatedBones;

        Kind(String name, Block block) {
            this("geo/", name, block);
        }

        Kind(String geoDir, String name, Block block, String... animatedBones) {
            this.geo = new ResourceLocation(TaczSewv.MODID, geoDir + name + ".geo.json");
            this.texture = new ResourceLocation(TaczSewv.MODID, "textures/block/" + name + ".png");
            this.block = block;
            this.animatedBones = java.util.Set.of(animatedBones);
        }
    }

    private static final Map<Kind, BakedBedrockModel> baked = new EnumMap<>(Kind.class);
    private static final Map<Kind, BakedModelInstance> itemInstances = new EnumMap<>(Kind.class);
    private static final Map<BlockEntity, BakedModelInstance> instances = new java.util.WeakHashMap<>();

    private FobBlockClient() {}

    @Nullable
    public static Kind kindFor(BlockState state) {
        Block block = state.getBlock();
        for (Kind kind : Kind.values()) {
            if (kind.block == block) return kind;
        }
        return null;
    }

    @Nullable
    public static BakedModelInstance instance(BlockEntity be) {
        Kind kind = be.getLevel() == null ? null : kindFor(be.getBlockState());
        if (kind == null) return null;
        BakedBedrockModel model = baked.get(kind);
        if (model == null) return null;
        return instances.computeIfAbsent(be, key -> model.createInstance());
    }

    /** One shared instance per kind for item rendering; items are static so they never diverge. */
    @Nullable
    public static BakedModelInstance itemInstance(Kind kind) {
        BakedBedrockModel model = baked.get(kind);
        return model == null ? null : itemInstances.computeIfAbsent(kind, k -> model.createInstance());
    }

    public static ResourceLocation texture(Kind kind) {
        return kind.texture;
    }

    public static void rebake(ResourceManager manager) {
        baked.clear();
        instances.clear();
        itemInstances.clear();
        for (Kind kind : Kind.values()) {
            try (InputStreamReader reader = new InputStreamReader(
                    manager.open(kind.geo), StandardCharsets.UTF_8)) {
                BedrockModelPOJO pojo = GsonUtil.CLIENT_GSON.fromJson(reader, BedrockModelPOJO.class);
                baked.put(kind, BakedBedrockModel.bake(pojo, kind.animatedBones.isEmpty()
                        ? BakerOptions.defaults() : BakerOptions.ofAnimatedBones(kind.animatedBones)));
            } catch (IOException | JsonParseException | IllegalStateException e) {
                TaczSewv.LOGGER.error("Failed to load FOB model {}", kind.geo, e);
            }
        }
    }
}
