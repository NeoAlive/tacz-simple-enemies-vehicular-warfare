package com.neoalive.tacz_sewv.command;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * Rechecks every block-lit position in a square of loaded chunks, a few sections per tick. The
 * light engine drops whatever no real emitter supports, which clears light left behind by
 * flashes that ended without a recheck. Runs on the thread that owns the level.
 */
public final class RelightJob {

    private final Level level;
    private final int centerX;
    private final int centerZ;
    private final int radius;
    private final int minSection;
    private final int maxSection;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    private int dx;
    private int dz;
    private int sy;
    private long checked;
    private int sections;

    public RelightJob(Level level, int chunkX, int chunkZ, int radius) {
        this.level = level;
        this.centerX = chunkX;
        this.centerZ = chunkZ;
        this.radius = radius;
        // Light sections reach one past the build height on each side.
        this.minSection = level.getMinSection() - 1;
        this.maxSection = level.getMaxSection() + 1;
        this.dx = -radius;
        this.dz = -radius;
        this.sy = minSection;
    }

    public Level level() {
        return level;
    }

    public long checked() {
        return checked;
    }

    public int sections() {
        return sections;
    }

    /** Scans up to {@code sectionBudget} sections. True when the whole square is done. */
    public boolean step(int sectionBudget) {
        LevelLightEngine engine = level.getLightEngine();
        LayerLightEventListener blockLight = engine.getLayerListener(LightLayer.BLOCK);
        while (sectionBudget-- > 0) {
            if (dx > radius) return true;
            int cx = centerX + dx;
            int cz = centerZ + dz;
            if (level.hasChunk(cx, cz)) {
                DataLayer data = blockLight.getDataLayerData(SectionPos.of(cx, sy, cz));
                if (data != null && !data.isEmpty()) {
                    scan(engine, data, cx, sy, cz);
                    sections++;
                }
                advanceSection();
            } else {
                sy = maxSection;
                advanceSection();
            }
        }
        return dx > radius;
    }

    private void scan(LevelLightEngine engine, DataLayer data, int cx, int cy, int cz) {
        int baseX = cx << 4;
        int baseY = cy << 4;
        int baseZ = cz << 4;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (data.get(x, y, z) > 0) {
                        engine.checkBlock(cursor.set(baseX + x, baseY + y, baseZ + z));
                        checked++;
                    }
                }
            }
        }
    }

    private void advanceSection() {
        if (++sy < maxSection) return;
        sy = minSection;
        if (++dz <= radius) return;
        dz = -radius;
        dx++;
    }
}
