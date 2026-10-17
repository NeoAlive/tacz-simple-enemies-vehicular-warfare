package com.neoalive.tacz_sewv.entity.ai.formation;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import com.neoalive.tacz_sewv.entity.ai.support.WaterSupport;

/**
 * Drops a formation slot onto what it would stand on. SERVER ONLY: ClientLevel chunks carry only
 * MOTION_BLOCKING and WORLD_SURFACE, not the NO_LEAVES map this reads.
 */
public final class GroundSnap {

    /** A terrain probe further than this from the anchor's own level is not believable. */
    public static final int MAX_SLOT_RISE = 16;

    private GroundSnap() {}

    /** The surface a hull would come to rest on. NO_LEAVES because a canopy is not ground. */
    public static double groundY(Level level, double x, double z, double anchorY) {
        int probed = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(x), Mth.floor(z));
        return Math.abs(probed - anchorY) > MAX_SLOT_RISE ? anchorY : probed;
    }

    /**
     * Drive-to block for a slot centre. {@code water} pulls the slot onto the nearest navigable
     * column first (a ship slot on a beach becomes the water off it); otherwise, or when no water
     * is found, it is dropped onto the terrain under it.
     */
    public static BlockPos snap(Level level, Vec3 center, double anchorY, boolean water) {
        if (water) {
            BlockPos projected = WaterSupport.projectToWater(level, BlockPos.containing(center.x, anchorY, center.z));
            if (projected != null) return projected;
        }
        return BlockPos.containing(center.x, groundY(level, center.x, center.z, anchorY), center.z);
    }
}
