package com.neoalive.tacz_sewv.worldgen;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.mojang.datafixers.util.Either;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.mixin.AccessorSinglePoolElement;
import com.neoalive.tacz_sewv.mixin.AccessorTemplateStructurePiece;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;

/**
 * Finds the generated structure around a position and answers who owns it, by way of the templates
 * its pieces were built from ({@link ProbeCatalog}). Server thread only.
 */
public final class StructureLocator {

    /**
     * @param owner      the faction that keeps its probes and chests, or null when the structure has none
     * @param identified false when no catalogued structure was found around the position at all
     */
    public record Ownership(@Nullable TankFaction owner, boolean identified) {}

    private static final Ownership UNKNOWN = new Ownership(null, false);
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<String, Ownership> CACHE = new HashMap<>();
    private static boolean warned;

    private StructureLocator() {}

    public static Ownership ownershipAt(ServerLevel level, BlockPos pos) {
        try {
            StructureManager manager = level.structureManager();
            Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
            for (Map.Entry<Structure, LongSet> ref : manager.getAllStructuresAt(pos).entrySet()) {
                List<StructureStart> starts = new ArrayList<>();
                manager.fillStartsForStructure(ref.getKey(), ref.getValue(), starts::add);
                for (StructureStart start : starts) {
                    if (!start.isValid() || !start.getBoundingBox().isInside(pos)) continue;
                    ResourceLocation key = registry.getKey(ref.getKey());
                    String cacheKey = level.dimension().location() + "|" + start.getChunkPos().toLong() + "|" + key;
                    Ownership cached = CACHE.get(cacheKey);
                    if (cached != null) return cached;
                    Ownership fresh = evaluate(level, start, key);
                    if (!fresh.identified()) continue;
                    if (CACHE.size() > 512) CACHE.clear();
                    CACHE.put(cacheKey, fresh);
                    return fresh;
                }
            }
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                LOGGER.warn("[tacz_sewv] structure ownership lookup failed; probes fall back to their own faction", t);
            }
        }
        return UNKNOWN;
    }

    private static Ownership evaluate(ServerLevel level, StructureStart start, @Nullable ResourceLocation key) {
        Set<TankFaction> present = EnumSet.noneOf(TankFaction.class);
        TankFaction explicit = null;
        boolean any = false;
        for (StructurePiece piece : start.getPieces()) {
            ResourceLocation template = templateOf(piece);
            TemplateInfo info = template == null ? null : ProbeCatalog.get(template);
            if (info == null) continue;
            any = true;
            present.addAll(info.factions());
            if (explicit == null) explicit = info.explicitOwner();
        }
        if (!any) return UNKNOWN;
        long seed = StructureOwner.seed(level.getSeed(), start.getChunkPos().toLong(), String.valueOf(key).hashCode());
        return new Ownership(StructureOwner.resolve(explicit, present, seed), true);
    }

    @Nullable
    private static ResourceLocation templateOf(StructurePiece piece) {
        if (piece instanceof PoolElementStructurePiece pool && pool.getElement() instanceof SinglePoolElement single) {
            Either<ResourceLocation, StructureTemplate> template = ((AccessorSinglePoolElement) single).tacz_sewv$template();
            return template.left().orElse(null);
        }
        if (piece instanceof TemplateStructurePiece template) {
            return ResourceLocation.tryParse(((AccessorTemplateStructurePiece) template).tacz_sewv$templateName());
        }
        return null;
    }
}
