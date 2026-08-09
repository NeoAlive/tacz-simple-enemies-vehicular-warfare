package com.neoalive.tacz_sewv.block;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

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
        long emp = empPos.asLong();
        if (!this.emplacements.contains(emp)) return 0;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            long cell = empPos.relative(dir).asLong();
            if (!this.cells.contains(cell)) continue;
            // Flood to find min packed = network id basis
            LongOpenHashSet visited = new LongOpenHashSet();
            ArrayDeque<Long> queue = new ArrayDeque<>();
            queue.add(cell);
            visited.add(cell);
            long minPacked = cell;
            while (!queue.isEmpty()) {
                long cur = queue.removeFirst();
                if (cur < minPacked) minPacked = cur;
                BlockPos p = BlockPos.of(cur);
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    long next = p.relative(d).asLong();
                    if (this.cells.contains(next) && visited.add(next)) {
                        queue.add(next);
                    }
                }
            }
            return Long.hashCode(minPacked);
        }
        return 0;
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

    static boolean isTrackedCell(BlockState state) {
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
}
