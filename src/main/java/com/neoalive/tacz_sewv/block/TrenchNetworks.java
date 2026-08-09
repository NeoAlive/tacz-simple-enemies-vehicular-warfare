package com.neoalive.tacz_sewv.block;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Per-dimension index of trench cells (lower halves / foxholes) and emplacement pads.
 * Mutated from player topology edits only — same discipline as connection refresh.
 */
public final class TrenchNetworks extends SavedData {

    private static final String DATA_NAME = "tacz_sewv_trench_networks";

    private final LongOpenHashSet cells = new LongOpenHashSet();
    private final LongOpenHashSet emplacements = new LongOpenHashSet();

    public TrenchNetworks() {}

    public static TrenchNetworks get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TrenchNetworks::load, TrenchNetworks::new, DATA_NAME);
    }

    public static TrenchNetworks load(CompoundTag nbt) {
        TrenchNetworks data = new TrenchNetworks();
        readLongSet(nbt.getList("cells", Tag.TAG_LONG), data.cells);
        readLongSet(nbt.getList("emplacements", Tag.TAG_LONG), data.emplacements);
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag nbt) {
        nbt.put("cells", writeLongSet(this.cells));
        nbt.put("emplacements", writeLongSet(this.emplacements));
        return nbt;
    }

    /** Re-read membership at {@code origin} and its orthogonal neighbours from the world. */
    public void refreshAround(ServerLevel level, BlockPos origin) {
        updateMembership(level, origin);
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            updateMembership(level, origin.relative(dir));
        }
        // Double-block mates: upper half is never indexed, but breaking upper leaves lower —
        // still re-check vertical neighbour so a lone upper ghost never sticks.
        updateMembership(level, origin.above());
        updateMembership(level, origin.below());
        setDirty();
    }

    public void setEmplacement(BlockPos pos, boolean present) {
        if (present) {
            this.emplacements.add(pos.asLong());
        } else {
            this.emplacements.remove(pos.asLong());
        }
        setDirty();
    }

    public LongSet cells() {
        return this.cells;
    }

    public LongSet emplacements() {
        return this.emplacements;
    }

    /**
     * Connected components of trench cells, plus standalone emplacements (cellCount 0).
     * Ids are stable for a given component (min packed cell / emplacement pos).
     */
    public List<Network> networks() {
        LongOpenHashSet visited = new LongOpenHashSet();
        List<Network> out = new ArrayList<>();
        LongOpenHashSet claimedEmplacements = new LongOpenHashSet();

        for (long packed : this.cells) {
            if (visited.contains(packed)) continue;

            LongArrayList component = new LongArrayList();
            ArrayDeque<Long> queue = new ArrayDeque<>();
            queue.add(packed);
            visited.add(packed);
            long minPacked = packed;
            double sumX = 0.0;
            double sumY = 0.0;
            double sumZ = 0.0;

            while (!queue.isEmpty()) {
                long cur = queue.removeFirst();
                component.add(cur);
                if (cur < minPacked) minPacked = cur;
                BlockPos p = BlockPos.of(cur);
                sumX += p.getX() + 0.5;
                sumY += p.getY() + 0.5;
                sumZ += p.getZ() + 0.5;
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    long next = p.relative(dir).asLong();
                    if (this.cells.contains(next) && visited.add(next)) {
                        queue.add(next);
                    }
                }
            }

            boolean hasEmplacement = false;
            for (int i = 0; i < component.size(); i++) {
                BlockPos p = BlockPos.of(component.getLong(i));
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    long emp = p.relative(dir).asLong();
                    if (this.emplacements.contains(emp)) {
                        hasEmplacement = true;
                        claimedEmplacements.add(emp);
                    }
                }
            }

            int n = component.size();
            out.add(new Network(
                    Long.hashCode(minPacked),
                    sumX / n,
                    sumY / n,
                    sumZ / n,
                    n,
                    hasEmplacement));
        }

        for (long emp : this.emplacements) {
            if (claimedEmplacements.contains(emp)) continue;
            BlockPos p = BlockPos.of(emp);
            out.add(new Network(
                    Long.hashCode(emp),
                    p.getX() + 0.5,
                    p.getY() + 0.5,
                    p.getZ() + 0.5,
                    0,
                    true));
        }
        return out;
    }

    /** Network id for an emplacement, or {@code 0} when standalone / unknown. */
    public int networkIdForEmplacement(BlockPos empPos) {
        NetworkDetail detail = networkContaining(empPos);
        return detail != null ? detail.id() : 0;
    }

    /**
     * Normalize a raycast/hit pos to the indexed cell (lower half / foxhole / emplacement).
     */
    public static BlockPos indexPos(BlockPos pos, BlockState state) {
        if ((state.getBlock() instanceof TrenchBlock || state.getBlock() instanceof TrenchXCrossBlock)
                && state.hasProperty(TrenchBlock.HALF)
                && state.getValue(TrenchBlock.HALF) == DoubleBlockHalf.UPPER) {
            return pos.below();
        }
        return pos;
    }

    /**
     * Resolve the connected component containing {@code pos} (cell or linked/standalone emplacement).
     * On-demand BFS — O(component), not a full world scan.
     */
    @Nullable
    public NetworkDetail networkContaining(BlockPos pos) {
        long packed = pos.asLong();
        if (this.cells.contains(packed)) {
            return floodDetail(packed);
        }
        if (this.emplacements.contains(packed)) {
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                long cell = pos.relative(dir).asLong();
                if (this.cells.contains(cell)) {
                    return floodDetail(cell);
                }
            }
            LongArrayList emptyCells = new LongArrayList();
            LongOpenHashSet empOnly = new LongOpenHashSet();
            empOnly.add(packed);
            return new NetworkDetail(Long.hashCode(packed), packed, emptyCells, empOnly);
        }
        return null;
    }

    /**
     * Map clicks often lack exact trench Y — find a cell/emplacement within {@code radius} of XZ
     * and resolve its network.
     */
    @Nullable
    public NetworkDetail findNearbyNetwork(BlockPos approx, int radius) {
        NetworkDetail direct = networkContaining(approx);
        if (direct != null) return direct;
        int r2 = radius * radius;
        long best = Long.MIN_VALUE;
        double bestDist = Double.MAX_VALUE;
        for (long packed : this.cells) {
            BlockPos p = BlockPos.of(packed);
            double dx = p.getX() - approx.getX();
            double dz = p.getZ() - approx.getZ();
            double d2 = dx * dx + dz * dz;
            if (d2 <= r2 && d2 < bestDist) {
                bestDist = d2;
                best = packed;
            }
        }
        for (long packed : this.emplacements) {
            BlockPos p = BlockPos.of(packed);
            double dx = p.getX() - approx.getX();
            double dz = p.getZ() - approx.getZ();
            double d2 = dx * dx + dz * dz;
            if (d2 <= r2 && d2 < bestDist) {
                bestDist = d2;
                best = packed;
            }
        }
        return best == Long.MIN_VALUE ? null : networkContaining(BlockPos.of(best));
    }

    /** Component whose min packed cell (or emp) hashes to {@code seed}. Null if gone. */
    @Nullable
    public NetworkDetail networkBySeed(long seed) {
        if (this.cells.contains(seed)) {
            return floodDetail(seed);
        }
        if (this.emplacements.contains(seed) && !hasAdjacentCell(BlockPos.of(seed))) {
            LongArrayList emptyCells = new LongArrayList();
            LongOpenHashSet empOnly = new LongOpenHashSet();
            empOnly.add(seed);
            return new NetworkDetail(Long.hashCode(seed), seed, emptyCells, empOnly);
        }
        // Seed may be minPacked of a multi-cell net — walk all cells (rare; assignment keeps seed).
        LongOpenHashSet visited = new LongOpenHashSet();
        for (long packed : this.cells) {
            if (!visited.add(packed)) continue;
            NetworkDetail detail = floodDetail(packed);
            visited.addAll(detail.cells());
            if (detail.seed() == seed) return detail;
        }
        return null;
    }

    private boolean hasAdjacentCell(BlockPos empPos) {
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if (this.cells.contains(empPos.relative(dir).asLong())) return true;
        }
        return false;
    }

    private NetworkDetail floodDetail(long startCell) {
        LongArrayList component = new LongArrayList();
        ArrayDeque<Long> queue = new ArrayDeque<>();
        LongOpenHashSet visited = new LongOpenHashSet();
        queue.add(startCell);
        visited.add(startCell);
        long minPacked = startCell;
        while (!queue.isEmpty()) {
            long cur = queue.removeFirst();
            component.add(cur);
            if (cur < minPacked) minPacked = cur;
            BlockPos p = BlockPos.of(cur);
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                long next = p.relative(dir).asLong();
                if (this.cells.contains(next) && visited.add(next)) {
                    queue.add(next);
                }
            }
        }
        LongOpenHashSet linkedEmp = new LongOpenHashSet();
        for (int i = 0; i < component.size(); i++) {
            BlockPos p = BlockPos.of(component.getLong(i));
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                long emp = p.relative(dir).asLong();
                if (this.emplacements.contains(emp)) {
                    linkedEmp.add(emp);
                }
            }
        }
        return new NetworkDetail(Long.hashCode(minPacked), minPacked, component, linkedEmp);
    }

    private void updateMembership(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (isTrackedCell(state)) {
            this.cells.add(pos.asLong());
        } else {
            this.cells.remove(pos.asLong());
        }
        if (state.getBlock() instanceof EmplacementBlock) {
            this.emplacements.add(pos.asLong());
        } else {
            this.emplacements.remove(pos.asLong());
        }
    }

    public static boolean isTrackedCell(BlockState state) {
        if (state.getBlock() instanceof FoxholeBlock) return true;
        if (state.getBlock() instanceof TrenchBlock || state.getBlock() instanceof TrenchXCrossBlock) {
            return state.hasProperty(TrenchBlock.HALF)
                    && state.getValue(TrenchBlock.HALF) == DoubleBlockHalf.LOWER;
        }
        return false;
    }

    private static void readLongSet(ListTag list, LongOpenHashSet out) {
        for (int i = 0; i < list.size(); i++) {
            out.add(((LongTag) list.get(i)).getAsLong());
        }
    }

    private static ListTag writeLongSet(LongOpenHashSet set) {
        ListTag list = new ListTag();
        for (long v : set) {
            list.add(LongTag.valueOf(v));
        }
        return list;
    }

    public record Network(int id, double x, double y, double z, int cellCount, boolean hasEmplacement) {}

    /** Full component for assignment / reroll (cells + linked emplacements). */
    public record NetworkDetail(int id, long seed, LongArrayList cells, LongOpenHashSet emplacements) {
        public LongSet cellSet() {
            return new LongOpenHashSet(this.cells);
        }
    }
}
