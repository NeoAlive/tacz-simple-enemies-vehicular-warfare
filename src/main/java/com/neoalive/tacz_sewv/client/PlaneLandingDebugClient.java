package com.neoalive.tacz_sewv.client;

import java.util.List;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import com.neoalive.tacz_sewv.entity.ai.plane.DubinsPath;

/**
 * Client cache of active plane Dubins entry-arc debug state, filled by
 * {@link com.neoalive.tacz_sewv.network.PacketPlaneLandingDebug} and dropped by
 * {@link com.neoalive.tacz_sewv.network.PacketClearPlaneLandingDebug}. Same shape as
 * {@link HeliRunPhaseClient}: server owns the entry's lifecycle explicitly, no client-side wall-clock
 * expiry (that was tried for the map markers and reverted — see {@link MapMarkers}'s doc comment).
 */
public final class PlaneLandingDebugClient {

    /** One plane's debug snapshot: the "existing LERP curve" (fix->pad line) plus its Dubins arc. */
    public record State(double refY, Vec3 fix, Vec3 pad, Vec3 entry, Vec3 axisDir,
                        List<DubinsPath.Segment> segments) {}

    private static final Int2ObjectOpenHashMap<State> STATES = new Int2ObjectOpenHashMap<>();

    private PlaneLandingDebugClient() {}

    public static void put(int entityId, double refY, Vec3 fix, Vec3 pad, Vec3 entry, Vec3 axisDir,
                           List<DubinsPath.Segment> segments) {
        STATES.put(entityId, new State(refY, fix, pad, entry, axisDir, segments));
    }

    @Nullable
    public static State get(int entityId) {
        return STATES.get(entityId);
    }

    /** Every active snapshot — the renderer draws straight from these, not from a world entity scan,
     * since every point involved (fix, pad, entry, arc) is already carried on the wire. */
    public static Iterable<State> states() {
        return STATES.values();
    }

    public static void clear(int entityId) {
        STATES.remove(entityId);
    }
}
