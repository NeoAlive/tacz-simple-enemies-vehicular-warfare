package com.neoalive.tacz_sewv.client.radial;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Pure Far Cry–style radial menu state: accumulated mouse deltas → hot wedge, menu stack for
 * category drill-down. Zero Minecraft / Forge dependencies so it can be self-checked headless.
 */
public final class RadialInputState {

    /** Radius of the neutral deadzone in accumulated-delta units. */
    public static final double DEADZONE_RADIUS = 24.0;

    private final Deque<List<WedgeEntry>> stack = new ArrayDeque<>();
    private double accumX;
    private double accumY;
    private int hotIndex = -1;

    public RadialInputState(List<WedgeEntry> root) {
        Objects.requireNonNull(root, "root");
        if (root.isEmpty()) {
            throw new IllegalArgumentException("root wedges must not be empty");
        }
        this.stack.push(List.copyOf(root));
        resetPointer();
    }

    /** Feed one frame of raw mouse motion (window/pixel deltas, not absolute cursor). */
    public void feedMouseDelta(double dx, double dy) {
        this.accumX += dx;
        this.accumY += dy;
        recomputeHot();
    }

    /**
     * Left-click on the hot wedge: enter a category submenu, fire a leaf pipeline, or no-op in the
     * deadzone.
     */
    public CommitResult commitHot() {
        if (this.hotIndex < 0) {
            return CommitResult.Deadzone.INSTANCE;
        }
        List<WedgeEntry> wedges = currentWedges();
        if (this.hotIndex >= wedges.size()) {
            return CommitResult.Deadzone.INSTANCE;
        }
        WedgeEntry entry = wedges.get(this.hotIndex);
        if (entry instanceof WedgeEntry.CategoryEntry cat) {
            if (cat.children().isEmpty()) {
                return CommitResult.Deadzone.INSTANCE;
            }
            this.stack.push(List.copyOf(cat.children()));
            resetPointer();
            return CommitResult.EnteredSubmenu.INSTANCE;
        }
        if (entry instanceof WedgeEntry.PipelineEntry leaf) {
            return new CommitResult.FiredLeaf(leaf.pipelineId());
        }
        return CommitResult.Deadzone.INSTANCE;
    }

    /**
     * Right-click: pop one menu level. Returns {@code true} when the stack was already at root
     * (caller should close the wheel with no action).
     */
    public boolean popMenu() {
        if (this.stack.size() <= 1) {
            return true;
        }
        this.stack.pop();
        resetPointer();
        return false;
    }

    public List<WedgeEntry> currentWedges() {
        List<WedgeEntry> top = this.stack.peek();
        return top != null ? top : List.of();
    }

    /** {@code -1} while inside the deadzone / no selection. */
    public int hotIndex() {
        return this.hotIndex;
    }

    public int depth() {
        return this.stack.size();
    }

    public double accumX() {
        return this.accumX;
    }

    public double accumY() {
        return this.accumY;
    }

    private void resetPointer() {
        this.accumX = 0.0;
        this.accumY = 0.0;
        this.hotIndex = -1;
    }

    private void recomputeHot() {
        List<WedgeEntry> wedges = currentWedges();
        int n = wedges.size();
        if (n == 0) {
            this.hotIndex = -1;
            return;
        }
        double len = Math.hypot(this.accumX, this.accumY);
        if (len < DEADZONE_RADIUS) {
            this.hotIndex = -1;
            return;
        }
        // Screen Y grows downward; invert so "up" is the first half of the first wedge.
        double angle = Math.atan2(this.accumY, this.accumX);
        // Rotate so 0 rad is straight up (-Y), then wrap to [0, 2π).
        double fromUp = angle + Math.PI / 2.0;
        if (fromUp < 0.0) {
            fromUp += Math.PI * 2.0;
        }
        if (fromUp >= Math.PI * 2.0) {
            fromUp -= Math.PI * 2.0;
        }
        double slice = (Math.PI * 2.0) / n;
        this.hotIndex = (int) Math.floor(fromUp / slice) % n;
    }

    /** Sealed commit outcome — no nulls / magic ints. */
    public sealed interface CommitResult {
        record EnteredSubmenu() implements CommitResult {
            public static final EnteredSubmenu INSTANCE = new EnteredSubmenu();
        }

        record FiredLeaf(String pipelineId) implements CommitResult {
            public FiredLeaf {
                Objects.requireNonNull(pipelineId, "pipelineId");
            }
        }

        record Deadzone() implements CommitResult {
            public static final Deadzone INSTANCE = new Deadzone();
        }
    }
}
