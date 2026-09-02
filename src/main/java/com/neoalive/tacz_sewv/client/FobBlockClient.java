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
        STOCKPILE("stockpile_block", ModBlocks.STOCKPILE_AMMO.get());

        final ResourceLocation geo;
        final ResourceLocation texture;
        final Block block;

        Kind(String name, Block block) {
            this.geo = new ResourceLocation(TaczSewv.MODID, "geo/" + name + ".geo.json");
            this.texture = new ResourceLocation(TaczSewv.MODID, "textures/block/" + name + ".png");
            this.block = block;
        }
    }

    private static final Map<Kind, BakedBedrockModel> baked = new EnumMap<>(Kind.class);
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

    public static ResourceLocation texture(Kind kind) {
        return kind.texture;
    }

    public static void rebake(ResourceManager manager) {
        baked.clear();
        instances.clear();
        for (Kind kind : Kind.values()) {
            try (InputStreamReader reader = new InputStreamReader(
                    manager.open(kind.geo), StandardCharsets.UTF_8)) {
                BedrockModelPOJO pojo = GsonUtil.CLIENT_GSON.fromJson(reader, BedrockModelPOJO.class);
                baked.put(kind, BakedBedrockModel.bake(pojo, BakerOptions.defaults()));
            } catch (IOException | JsonParseException | IllegalStateException e) {
                TaczSewv.LOGGER.error("Failed to load FOB model {}", kind.geo, e);
            }
        }
    }
}
