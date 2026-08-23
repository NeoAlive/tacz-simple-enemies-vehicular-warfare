package com.neoalive.tacz_sewv.client;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.WeakHashMap;

import javax.annotation.Nullable;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.resource.GsonUtil;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.resource.pojo.BedrockModelPOJO;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.baked.BakedBedrockModel;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.baked.BakerOptions;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BakedModelInstance;
import com.google.gson.JsonParseException;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.block.RunwayBlockEntity;

/**
 * Client-side state for the runway radar mast, replacing the old GeckoLib block renderer: the
 * baked SBM model (rebaked on resource reload so F3+T picks up edited art), one render instance
 * per block entity, and a per-BE spin angle advanced only from {@link RunwayBlockRenderer#render}
 * — a frustum miss freezes it exactly like the GeckoLib animation controller did.
 *
 * <p>The dish spin is code-driven rotation of the {@code spinning_radar} bone (the animation file
 * only spun that one bone 360° over 3.25 s), applied after {@code resetPose()} each frame.
 */
public final class RunwayBlockClient {

    public static final ResourceLocation TEXTURE =
            new ResourceLocation(TaczSewv.MODID, "textures/block/runway_mast.png");
    private static final ResourceLocation GEO =
            new ResourceLocation(TaczSewv.MODID, "geo/runway_block.geo.json");
    /** One full turn per 3.25 s, the period of the old GeckoLib spin animation. */
    private static final float SPIN_DEGREES_PER_TICK = 360.0F / (3.25F * 20.0F);

    /** Baked once per resource reload; null until the first reload listener pass. */
    private static volatile BakedBedrockModel bakedModel;
    /**
     * Per-BE instances live here rather than on the BE so a resource-reload rebake never has to
     * touch block-entity fields, and a BE that unloads without a chunk re-render leaks nothing
     * (WeakHashMap keyed on the BE).
     */
    private static final Map<RunwayBlockEntity, BakedModelInstance> instances = new WeakHashMap<>();
    private static final Map<BlockPos, Float> spinAngles = new WeakHashMap<>();

    private RunwayBlockClient() {
    }

    @Nullable
    public static BakedBedrockModel bakedModel() {
        return bakedModel;
    }

    @Nullable
    public static BakedModelInstance instance(RunwayBlockEntity be) {
        return instances.computeIfAbsent(be, key -> {
            BakedBedrockModel model = bakedModel;
            return model == null ? null : model.createInstance();
        });
    }

    public static void clearSpin(BlockPos pos) {
        spinAngles.remove(pos);
    }

    /**
     * Advance and read the accumulated spin angle for this mast. Advanced only while actually
     * rendered (the BER calls this), so a culled dish does not tick — same contract as the old
     * GeckoLib predicate, which was also evaluated from the BER.
     */
    public static float spinAngleDeg(RunwayBlockEntity be) {
        if (!be.hasCachedAirport()) {
            spinAngles.remove(be.getBlockPos());
            return 0.0F;
        }
        float next = spinAngles.merge(be.getBlockPos(), SPIN_DEGREES_PER_TICK, Float::sum);
        if (next >= 360.0F) {
            next -= 360.0F;
            spinAngles.put(be.getBlockPos(), next);
        }
        return next;
    }

    /**
     * Reload-listener entry point: reparse the geo json and rebake. Called on the main thread
     * during resource reload.
     */
    public static void rebake(ResourceManager manager) {
        try (InputStreamReader reader = new InputStreamReader(
                manager.open(GEO), StandardCharsets.UTF_8)) {
            BedrockModelPOJO pojo = GsonUtil.CLIENT_GSON.fromJson(reader, BedrockModelPOJO.class);
            // Keep spinning_radar as a live bone so the BER can rotate it each frame; without
            // this, BakerOptions.defaults() may fold it into static geometry.
            bakedModel = BakedBedrockModel.bake(pojo,
                    BakerOptions.ofAnimatedBones(java.util.Set.of("spinning_radar")));
            instances.clear();
        } catch (IOException | JsonParseException | IllegalStateException e) {
            TaczSewv.LOGGER.error("Failed to load runway mast model {}", GEO, e);
            bakedModel = null;
        }
    }
}
