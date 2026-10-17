package com.neoalive.tacz_sewv.mixin;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.config.SewvConfig;

/**
 * The switch for this mod's own structures ({@code structuresGenerate}). Structure sets are
 * datapack JSON with no toggle, and Forge 1.20.1 has no event for structure placement, so the
 * one seam is the per-candidate attempt in {@link ChunkGenerator}. Answering {@code false} is the
 * ordinary "this candidate did not place" result, so the region simply generates nothing.
 *
 * <p>Only affects chunks generated <b>after</b> the flip; structures already in the world stay.
 * Reads the config defensively — worldgen can run before the spec is baked.
 */
@Mixin(ChunkGenerator.class)
public abstract class MixinChunkGeneratorStructures {

    @Inject(method = "tryGenerateStructure", at = @At("HEAD"), cancellable = true)
    private void tacz_sewv$structuresSwitch(StructureSet.StructureSelectionEntry entry,
            StructureManager structureManager, RegistryAccess registryAccess, RandomState random,
            StructureTemplateManager templates, long seed, ChunkAccess chunk, ChunkPos chunkPos,
            SectionPos sectionPos, CallbackInfoReturnable<Boolean> cir) {
        boolean ours = entry.structure().unwrapKey()
                .map(k -> TaczSewv.MODID.equals(k.location().getNamespace()))
                .orElse(false);
        if (!ours) return;
        boolean on;
        try {
            on = SewvConfig.STRUCTURES_GENERATE.get();
        } catch (IllegalStateException unbaked) {
            return;
        }
        if (!on) cir.setReturnValue(false);
    }
}
