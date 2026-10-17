package com.neoalive.tacz_sewv.mixin;

import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Which template a non-jigsaw template piece was built from, for {@code worldgen.StructureLocator}. */
@Mixin(TemplateStructurePiece.class)
public interface AccessorTemplateStructurePiece {
    @Accessor("templateName")
    String tacz_sewv$templateName();
}
