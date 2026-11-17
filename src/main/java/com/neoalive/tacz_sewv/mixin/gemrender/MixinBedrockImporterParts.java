package com.neoalive.tacz_sewv.mixin.gemrender;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.wf.gemrender.bedrock.BedrockImporter;
import com.wf.gemrender.gltf.GltfPaletteLayout;
import com.wf.gemrender.gltf.NodeTable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.config.PerfFlags;

/**
 * GemRender caps every Bedrock import at 256 palette slots, one per bone in the file. Rigid parts
 * never write a palette index into a vertex (each part is one Flywheel instance), so the cap only
 * guards the skinned path. Tracked hulls carry about 200 track bones and fail it, which sends
 * them back to SBW's own renderer. Only the parts import is lifted; {@code load} keeps the check.
 */
@Mixin(value = BedrockImporter.class, remap = false)
public abstract class MixinBedrockImporterParts {

    private static final int VERTEX_PALETTE_SLOTS = 256;
    private static final AtomicBoolean LOGGED = new AtomicBoolean();

    // A bare name only selects the first loadParts, the one-arg overload that just delegates, so
    // match every overload; only the one that builds the layout holds the call.
    @Redirect(
            method = "loadParts*",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/wf/gemrender/gltf/GltfPaletteLayout;ofNodes(Lcom/wf/gemrender/gltf/NodeTable;)Lcom/wf/gemrender/gltf/GltfPaletteLayout;",
                    remap = false),
            remap = false,
            require = 0)
    private static GltfPaletteLayout tacz_sewv$partsLayout(NodeTable table) {
        if (table.nodeCount() <= VERTEX_PALETTE_SLOTS || !PerfFlags.gemrenderPartsPaletteLift()) {
            return GltfPaletteLayout.ofNodes(table);
        }
        if (LOGGED.compareAndSet(false, true)) {
            TaczSewv.LOGGER.info("[sewv] GemRender parts bone cap lifted ({} bones)", table.nodeCount());
        }
        return GltfPaletteLayoutAccessor.tacz_sewv$create(
                List.of(), Map.of(), List.of(), Map.of(), table.nodeCount(), table);
    }
}
