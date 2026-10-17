package com.neoalive.tacz_sewv.entity.ai.support;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.neoalive.tacz_sewv.entity.ai.support.FrontlineMath.Chunk;

/**
 * Advance Plan geometry. Pure: packed chunk keys ({@link FrontlineMath#pack}) and ints only, so it runs headless
 * ({@code ./gradlew selfCheckTerritory}) and the manager owns every world read.
 *
 * <p><b>Layers.</b> Layer K is {@code (dilate4(claims, K) \ dilate4(claims, K-1)) ∩ region}: dilate the player's own
 * claims outward K chunks, subtract the previous ring, intersect with the painted region. There is no per-edge
 * orientation maths, so an L-shaped front is handled by construction. Dilation is 4-neighbour, matching the front
 * definition ({@code FrontlineMath.frontChunks}) and region connectivity.
 *
 * <p><b>The number of layers is not the dilation limit.</b> {@link #MAX_DILATIONS} bounds how far the dilation reaches
 * (up to 5 rings); how many of those rings actually intersect the region is whatever the region's shape says. A 1x5
 * region beside a straight front is ONE layer. Layers are returned compacted (empty rings dropped), so callers must
 * never assume {@code layers.size() == MAX_DILATIONS}.
 */
public final class AdvanceMath {

    /** Max dilations from the claims: up to this many rings, so at most this many layers, usually fewer. */
    public static final int MAX_DILATIONS = 5;
    /** Consecutive bad-parent passes before a plan is cleared (a transient split during an advance is not a loss). */
    public static final int CLEAR_AFTER_BAD_PASSES = 3;

    private static final int[][] N4 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private AdvanceMath() {}

    // ---- dilation and layers ------------------------------------------------------------------------------

    /** The front of {@code claims} as keys: claimed chunks with at least one 4-neighbour that is not claimed. */
    public static Set<Long> frontKeys(Set<Long> claims) {
        Set<Long> out = new HashSet<>();
        for (Chunk c : FrontlineMath.frontChunks(claims)) out.add(FrontlineMath.pack(c.x(), c.z()));
        return out;
    }

    /** {@code base} grown outward by {@code k} 4-neighbour steps ({@code k = 0} is a copy). */
    public static Set<Long> dilate4(Set<Long> base, int k) {
        Set<Long> current = new HashSet<>(base);
        for (int i = 0; i < k; i++) {
            Set<Long> next = new HashSet<>(current);
            for (long key : current) {
                Chunk c = FrontlineMath.unpack(key);
                for (int[] d : N4) next.add(FrontlineMath.pack(c.x() + d[0], c.z() + d[1]));
            }
            current = next;
        }
        return current;
    }

    /** Ring K of the dilation: {@code dilate4(claims, k) \ dilate4(claims, k-1)}, for {@code k >= 1}. */
    public static Set<Long> ring(Set<Long> claims, int k) {
        Set<Long> outer = dilate4(claims, k);
        outer.removeAll(dilate4(claims, k - 1));
        return outer;
    }

    /**
     * Manhattan distance from {@code chunk} to the nearest claimed chunk: 0 when it is claimed, K when it lies in ring K,
     * or -1 when no claim is within {@code maxK}. This is exactly the ring index, without building the rings.
     */
    public static int distanceToClaims(Set<Long> claims, long chunk, int maxK) {
        Chunk c = FrontlineMath.unpack(chunk);
        for (int r = 0; r <= maxK; r++) {
            for (int dx = -r; dx <= r; dx++) {
                int dz = r - Math.abs(dx);
                if (claims.contains(FrontlineMath.pack(c.x() + dx, c.z() + dz))) return r;
                if (dz != 0 && claims.contains(FrontlineMath.pack(c.x() + dx, c.z() - dz))) return r;
            }
        }
        return -1;
    }

    /**
     * The region split into layers, nearest the claims first. {@code layers} holds only NON-EMPTY rings, in order (a
     * region with chunks at distance 1 and 3 but none at 2 has two layers); {@code layerOf} maps a chunk to its index
     * in that list; {@code unreachable} is every region chunk farther than {@code maxK} from the claims; and
     * {@code dropped} is every region chunk that got no layer because it is already claimed (distance 0).
     *
     * <p>{@code dropped} is empty whenever the caller has already refused self-claimed chunks (the bake does), but the
     * function is pure and carries no such precondition: every region chunk lands in exactly one of a layer,
     * {@code unreachable} or {@code dropped}, and the caller decides whether a non-empty {@code dropped} is worth a log.
     */
    public record Layers(List<Set<Long>> layers, Map<Long, Integer> layerOf, Set<Long> unreachable, Set<Long> dropped) {}

    public static Layers layers(Set<Long> claims, Set<Long> region, int maxK) {
        TreeMap<Integer, Set<Long>> byDistance = new TreeMap<>();
        Set<Long> unreachable = new HashSet<>();
        Set<Long> dropped = new HashSet<>();
        for (long chunk : region) {
            int d = distanceToClaims(claims, chunk, maxK);
            if (d == 0) dropped.add(chunk);
            else if (d < 0) unreachable.add(chunk);
            else byDistance.computeIfAbsent(d, k -> new HashSet<>()).add(chunk);
        }
        List<Set<Long>> layers = new ArrayList<>(byDistance.values());
        Map<Long, Integer> layerOf = new HashMap<>();
        for (int i = 0; i < layers.size(); i++) {
            for (long chunk : layers.get(i)) layerOf.put(chunk, i);
        }
        return new Layers(layers, layerOf, unreachable, dropped);
    }

    // ---- connectivity and parenting ---------------------------------------------------------------------------

    /** 4-adjacency connected components of a chunk set. */
    public static List<Set<Long>> components4(Set<Long> set) {
        List<Set<Long>> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (long start : set) {
            if (!seen.add(start)) continue;
            Set<Long> component = new HashSet<>();
            ArrayDeque<Long> queue = new ArrayDeque<>();
            queue.add(start);
            component.add(start);
            while (!queue.isEmpty()) {
                Chunk c = FrontlineMath.unpack(queue.poll());
                for (int[] d : N4) {
                    long next = FrontlineMath.pack(c.x() + d[0], c.z() + d[1]);
                    if (set.contains(next) && seen.add(next)) {
                        component.add(next);
                        queue.add(next);
                    }
                }
            }
            out.add(component);
        }
        return out;
    }

    /** One non-empty 4-connected blob (the bake rule for a painted region). */
    public static boolean isSingleBlob(Set<Long> set) {
        return !set.isEmpty() && components4(set).size() == 1;
    }

    /** The front chunks 4-adjacent to any chunk of {@code remainder} (the unclaimed rest of a plan's region). */
    public static Set<Long> contactChunks(Set<Long> front, Set<Long> remainder) {
        Set<Long> out = new HashSet<>();
        for (long key : remainder) {
            Chunk c = FrontlineMath.unpack(key);
            for (int[] d : N4) {
                long n = FrontlineMath.pack(c.x() + d[0], c.z() + d[1]);
                if (front.contains(n)) out.add(n);
            }
        }
        return out;
    }

    public enum Parent { HEALTHY, GONE, FRAGMENTED }

    /**
     * Whether the plan still has a single front to advance from. {@code GONE}: no front chunk touches the remainder.
     * {@code FRAGMENTED}: the touching chunks lie in more than one 4-connected component OF THE FRONT.
     *
     * <p>Connectivity is judged on the whole front, deliberately not among the contact chunks themselves: a region in
     * the inner corner of an L touches the front at two chunks that are only diagonal neighbours of each other, yet
     * both sit on the same arm-to-arm front component through the corner chunk.
     */
    public static Parent parentState(Set<Long> front, Set<Long> remainder) {
        Set<Long> contact = contactChunks(front, remainder);
        if (contact.isEmpty()) return Parent.GONE;
        Map<Long, Integer> componentOf = new HashMap<>();
        List<Set<Long>> components = components4(front);
        for (int i = 0; i < components.size(); i++) {
            for (long key : components.get(i)) componentOf.put(key, i);
        }
        Integer first = null;
        for (long key : contact) {
            Integer c = componentOf.get(key);
            if (c == null) return Parent.GONE;
            if (first == null) first = c;
            else if (!first.equals(c)) return Parent.FRAGMENTED;
        }
        return Parent.HEALTHY;
    }

    /** Streak of consecutive bad-parent passes: any healthy pass resets it. */
    public static int nextBadStreak(int streak, boolean bad) {
        return bad ? streak + 1 : 0;
    }

    public static boolean shouldClear(int streak) {
        return streak >= CLEAR_AFTER_BAD_PASSES;
    }

    // ---- the per-pass step: path first, claim after ------------------------------------------------------------

    /** {@code dispatchedLayer} before the first dispatch: not any layer index, so the first pass after Start dispatches. */
    public static final int NOT_DISPATCHED = -1;

    /** What one pass of a running plan does about the current layer. */
    public enum Step {
        /** Send the plan's units to this layer's contact edge (scoped Frontline re-run), then wait. */
        DISPATCH,
        /** Dispatched, but the minimum soak has not elapsed. */
        WAIT_SOAK,
        /** Nobody is posted on the contact edge (a post failed): dispatch again rather than claim ahead of the units. */
        REDISPATCH,
        /** Someone posted on the contact edge has not arrived yet. */
        ARRIVING,
        /** Everyone on the edge is there: run the hostile / loaded / limit gates and claim. */
        PROCEED
    }

    /**
     * Path before claim. The order is the rule: dispatch first; then the soak is a MINIMUM; then the units must be on
     * the edge (at least one, or a plan would claim ahead of an empty edge) and ALL of them arrived.
     *
     * @param onEdge   live snapshot units whose post is on the contact edge
     * @param arrived  how many of those have arrived
     */
    public static Step nextStep(int dispatchedLayer, int currentLayer, boolean soaked, int onEdge, int arrived) {
        if (dispatchedLayer != currentLayer) return Step.DISPATCH;
        if (!soaked) return Step.WAIT_SOAK;
        if (onEdge <= 0) return Step.REDISPATCH;
        if (arrived < onEdge) return Step.ARRIVING;
        return Step.PROCEED;
    }

    // ---- arrows and centre --------------------------------------------------------------------------------------

    /** An advance arrow: where it starts (block coordinates) and its axis-aligned direction, front toward region. */
    public record Arrow(int blockX, int blockZ, int dx, int dz) {}

    /**
     * One arrow per distinct edge normal. Every front/region 4-adjacent pair is an edge whose normal (front to region)
     * is axis-aligned, so the arrow is perpendicular to that edge by construction; edges sharing a normal are one
     * group whose arrow starts at the centroid of its front chunks. A straight front gives one arrow, an L-shaped
     * contact gives two. Sorted by (dx, dz) so output is deterministic.
     */
    public static List<Arrow> arrows(Set<Long> front, Set<Long> region) {
        TreeMap<Long, Set<Long>> groups = new TreeMap<>();
        for (long key : region) {
            Chunk c = FrontlineMath.unpack(key);
            for (int[] d : N4) {
                long n = FrontlineMath.pack(c.x() + d[0], c.z() + d[1]);
                if (!front.contains(n)) continue;
                long normal = FrontlineMath.pack(-d[0], -d[1]); // front -> region
                groups.computeIfAbsent(normal, k -> new HashSet<>()).add(n);
            }
        }
        List<Arrow> out = new ArrayList<>();
        for (Map.Entry<Long, Set<Long>> e : groups.entrySet()) {
            Chunk normal = FrontlineMath.unpack(e.getKey());
            double[] at = centroid(e.getValue());
            out.add(new Arrow((int) Math.round(at[0]), (int) Math.round(at[1]), normal.x(), normal.z()));
        }
        out.sort(Comparator.comparingInt(Arrow::dx).thenComparingInt(Arrow::dz));
        return out;
    }

    /** Mean of the chunk centres, in block coordinates ({0, 0} for an empty set). */
    public static double[] centroid(Set<Long> chunks) {
        if (chunks.isEmpty()) return new double[] {0, 0};
        double x = 0, z = 0;
        for (long key : chunks) {
            Chunk c = FrontlineMath.unpack(key);
            x += c.centreX();
            z += c.centreZ();
        }
        return new double[] {x / chunks.size(), z / chunks.size()};
    }

    // ---- claiming a layer ---------------------------------------------------------------------------------------

    /** A layer chunk as claim time finds it: free, already ours, or held by someone else (unclaim, then claim). */
    public enum ChunkState { FREE, OURS, FOREIGN }

    /**
     * What claiming a layer would take. Both FREE and FOREIGN chunks end up as a new claim of ours and so consume
     * headroom; OURS chunks do not. {@link #fits} is the all-or-nothing rule: if the whole layer does not fit, none of
     * it is touched, and in particular no enemy chunk is unclaimed first.
     */
    public record ClaimPlan(int free, int foreign, int ours) {
        public int toClaim() {
            return free + foreign;
        }

        public boolean fits(int headroom) {
            return toClaim() <= headroom;
        }
    }

    public static ClaimPlan planClaim(Map<Long, ChunkState> states) {
        int free = 0, foreign = 0, ours = 0;
        for (ChunkState s : states.values()) {
            switch (s) {
                case FREE -> free++;
                case FOREIGN -> foreign++;
                case OURS -> ours++;
            }
        }
        return new ClaimPlan(free, foreign, ours);
    }

    // ---- bake conflict rule -------------------------------------------------------------------------------------

    /**
     * Who holds a region chunk, as the manager classified it: {@code ENEMY} is a claim whose owner is in a diplomatic
     * ENEMY relation (the only foreign claim a plan may take); {@code OTHER} is any foreign claim that is not.
     */
    public enum Owner { UNCLAIMED, SELF, ALLY, ENEMY, OTHER }

    /**
     * The bake verdict. Refused if any chunk is SELF, ALLY or OTHER; UNCLAIMED and ENEMY are allowed ({@code enemy}
     * counts the chunks that would be conquered by unclaim then claim). Samples are the first few offending chunks per
     * class in (x, z) order, so the refusal message can name them.
     */
    public record Verdict(boolean ok, int self, int ally, int other, int enemy,
                          List<Long> selfSamples, List<Long> allySamples, List<Long> otherSamples) {}

    private static final int MAX_SAMPLES = 3;

    public static Verdict classify(Map<Long, Owner> owners) {
        int self = 0, ally = 0, other = 0, enemy = 0;
        List<Long> selfKeys = new ArrayList<>(), allyKeys = new ArrayList<>(), otherKeys = new ArrayList<>();
        for (Map.Entry<Long, Owner> e : owners.entrySet()) {
            switch (e.getValue()) {
                case SELF -> { self++; selfKeys.add(e.getKey()); }
                case ALLY -> { ally++; allyKeys.add(e.getKey()); }
                case OTHER -> { other++; otherKeys.add(e.getKey()); }
                case ENEMY -> enemy++;
                case UNCLAIMED -> { }
            }
        }
        return new Verdict(self + ally + other == 0, self, ally, other, enemy,
                firstFew(selfKeys), firstFew(allyKeys), firstFew(otherKeys));
    }

    private static List<Long> firstFew(List<Long> keys) {
        keys.sort(Comparator.comparingInt((Long k) -> FrontlineMath.unpack(k).x()).thenComparingInt(k -> FrontlineMath.unpack(k).z()));
        return new ArrayList<>(keys.subList(0, Math.min(MAX_SAMPLES, keys.size())));
    }
}
