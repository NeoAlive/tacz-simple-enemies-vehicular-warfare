package com.neoalive.tacz_sewv.mixin.gemrender;

import java.util.List;
import java.util.Map;

import com.wf.gemrender.gltf.GltfPaletteLayout;
import com.wf.gemrender.gltf.NodeTable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = GltfPaletteLayout.class, remap = false)
public interface GltfPaletteLayoutAccessor {

    @SuppressWarnings("rawtypes")
    @Invoker(value = "<init>", remap = false)
    static GltfPaletteLayout tacz_sewv$create(
            List nodes, Map nodeSlots, List skins, Map skinBlocks, int size, NodeTable table) {
        throw new AssertionError();
    }
}
