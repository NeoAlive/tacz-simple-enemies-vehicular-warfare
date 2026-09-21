package com.neoalive.tacz_sewv.mixin;

import com.mojang.datafixers.util.Either;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Which template a jigsaw piece was built from, for {@code worldgen.StructureLocator}. */
@Mixin(SinglePoolElement.class)
public interface AccessorSinglePoolElement {
    @Accessor("template")
    Either<ResourceLocation, StructureTemplate> tacz_sewv$template();
}
