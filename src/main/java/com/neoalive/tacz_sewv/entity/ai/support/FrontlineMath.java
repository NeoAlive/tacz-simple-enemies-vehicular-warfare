package com.neoalive.tacz_sewv.entity.ai.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Territory Mode frontline maths. Pure: ints and doubles only, so it runs headless
 * ({@code ./gradlew selfCheckTerritory}) and the server manager owns every world read.
 *
 * <p>Chunks are packed exactly like {@code ChunkPos.asLong} so the OpenPAC claim enumeration can
 * be handed over without conversion.
 */
public final class FrontlineMath {

    private FrontlineMath() {}

    public record Chunk(int x, int z) {
        public double centreX() { return x * 16 + 8; }
        public double centreZ() { return z * 16 + 8; }
    }

    /** {@code inside} = standing in self-owned territory; units outside it sort last. */
    public record Unit(int id, double x, double z, boolean inside) {}

    public record Assignment(int unitId, Chunk chunk) {}

    public static long pack(int x, int z) {
        return (x & 0xFFFFFFFFL) | ((long) z << 32);
    }

    public static Chunk unpack(long packed) {
        return new Chunk((int) packed, (int) (packed >> 32));
    }

    /**
     * Chunks a segment crosses, in order from start to end (a chunk equal to the previous one is collapsed). Stepping is
     * 4 blocks, far inside a 16-block chunk, so a fast mouse move between two frames cannot skip a chunk it crossed.
     */
    public static List<Long> chunksAlong(double x0, double z0, double x1, double z1) {
        double dx = x1 - x0;
        double dz = z1 - z0;
        int steps = Math.max(1, (int) Math.ceil(Math.max(Math.abs(dx), Math.abs(dz)) / 4.0));
        List<Long> out = new ArrayList<>();
        long last = Long.MIN_VALUE;
        for (int i = 0; i <= steps; i++) {
            double x = x0 + dx * i / steps;
            double z = z0 + dz * i / steps;
            long key = pack((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
            if (key != last) {
                out.add(key);
                last = key;
            }
        }
        return out;
    }

    /**
     * A drag's visited chunks reduced to the manual fill order: front chunks only, each once, first visit wins.
     * Non-contiguous drags are fine; a front chunk the drag never touched is simply absent (and stays uncovered).
     * The result goes to {@link #assign} exactly as the auto path's origin-sorted front does.
     */
    public static List<Chunk> drawnOrder(Set<Long> front, Collection<Long> visited) {
        LinkedHashSet<Long> keep = new LinkedHashSet<>();
        for (long key : visited) {
            if (front.contains(key)) keep.add(key);
        }
        List<Chunk> out = new ArrayList<>(keep.size());
        for (long key : keep) out.add(unpack(key));
        return out;
    }

    /** Front chunks in no particular order (for rendering / coverage; no origin needed). */
    public static List<Chunk> frontChunks(Set<Long> selfOwned) {
        List<Chunk> out = new ArrayList<>();
        for (long packed : selfOwned) {
            Chunk c = unpack(packed);
            if (!selfOwned.contains(pack(c.x() + 1, c.z()))
                    || !selfOwned.contains(pack(c.x() - 1, c.z()))
                    || !selfOwned.contains(pack(c.x(), c.z() + 1))
                    || !selfOwned.contains(pack(c.x(), c.z() - 1))) {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * Front = self-owned chunks with at least one non-self 4-neighbour (ally counts as non-self),
     * ordered by chunk-centre distance from {@code origin}, ties by (x, z).
     */
    public static List<Chunk> front(Set<Long> selfOwned, Chunk origin) {
        return sortByDistance(frontChunks(selfOwned), origin);
    }

    /** {@code chunks} sorted nearest-first from {@code origin} by chunk-centre distance, ties by (x, z). Sorts in place. */
    public static List<Chunk> sortByDistance(List<Chunk> chunks, Chunk origin) {
        chunks.sort(Comparator
                .comparingDouble((Chunk c) -> chunkDistSq(c, origin))
                .thenComparingInt(Chunk::x)
                .thenComparingInt(Chunk::z));
        return chunks;
    }

    /**
     * Assigns {@code units} onto {@code orderedFront} (already nearest-origin-first, as {@link #front}
     * returns it). Empty front or no units yields no assignments.
     *
     * @param priorCoverage live stamped units already posted per chunk (missing = 0); only the density
     *                      case reads it, so a re-run stacks on real coverage, not this run's count
     */
    public static List<Assignment> assign(List<Chunk> orderedFront, List<Unit> units, Chunk origin,
            Map<Chunk, Integer> priorCoverage) {
        int m = orderedFront.size();
        int n = units.size();
        if (m == 0 || n == 0) return List.of();

        List<Unit> ordered = new ArrayList<>(units);
        ordered.sort(Comparator
                .comparingDouble((Unit u) -> u.inside() ? unitOriginDistSq(u, origin) : Double.MAX_VALUE)
                .thenComparingInt(Unit::id));

        return n <= m ? spread(orderedFront, ordered) : density(orderedFront, ordered, priorCoverage);
    }

    /** n <= m: evenly spaced targets, first unit on the first chunk and last on the last. */
    private static List<Assignment> spread(List<Chunk> front, List<Unit> units) {
        int m = front.size();
        int n = units.size();
        List<Integer> targets = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            // round(i*(m-1)/(n-1)) in integer arithmetic; n=1 -> first chunk. Spacing is >= 1, so distinct.
            targets.add(n == 1 ? 0 : (2 * i * (m - 1) + (n - 1)) / (2 * (n - 1)));
        }
        List<Assignment> out = new ArrayList<>();
        for (Unit u : units) {
            int best = -1;
            double bestD = Double.MAX_VALUE;
            for (int t = 0; t < targets.size(); t++) {
                double d = unitChunkDistSq(u, front.get(targets.get(t)));
                if (d < bestD) { bestD = d; best = t; }
            }
            out.add(new Assignment(u.id(), front.get(targets.remove(best))));
        }
        return out;
    }

    /**
     * n > m: one unit per chunk, then stack leftovers on the chunk with the least
     * {@code prior + this-run} coverage, ties by distance.
     */
    private static List<Assignment> density(List<Chunk> front, List<Unit> units, Map<Chunk, Integer> prior) {
        int m = front.size();
        List<Unit> free = new ArrayList<>(units);
        int[] count = new int[m];
        for (int c = 0; c < m; c++) count[c] = prior.getOrDefault(front.get(c), 0);
        List<Assignment> out = new ArrayList<>();

        for (int c = 0; c < m; c++) {
            int best = nearestUnit(free, front.get(c));
            out.add(new Assignment(free.remove(best).id(), front.get(c)));
            count[c]++;
        }
        for (Unit u : free) {
            int best = 0;
            for (int c = 1; c < m; c++) {
                if (count[c] < count[best]
                        || (count[c] == count[best]
                                && unitChunkDistSq(u, front.get(c)) < unitChunkDistSq(u, front.get(best)))) {
                    best = c;
                }
            }
            out.add(new Assignment(u.id(), front.get(best)));
            count[best]++;
        }
        return out;
    }

    private static int nearestUnit(List<Unit> free, Chunk c) {
        int best = 0;
        for (int i = 1; i < free.size(); i++) {
            if (unitChunkDistSq(free.get(i), c) < unitChunkDistSq(free.get(best), c)) best = i;
        }
        return best;
    }

    private static double chunkDistSq(Chunk a, Chunk b) {
        double dx = a.x() - b.x(), dz = a.z() - b.z();
        return dx * dx + dz * dz;
    }

    private static double unitOriginDistSq(Unit u, Chunk origin) {
        return unitChunkDistSq(u, origin);
    }

    private static double unitChunkDistSq(Unit u, Chunk c) {
        double dx = u.x() - c.centreX(), dz = u.z() - c.centreZ();
        return dx * dx + dz * dz;
    }
}
