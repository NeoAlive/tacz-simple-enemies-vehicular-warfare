package com.neoalive.tacz_sewv.entity.ai.command;

import java.util.List;

/**
 * Coarse allocentric influence grid for one battle group.
 *
 * <p>Performance contracts (binding):
 * <ul>
 *   <li>Rebuild only on the command cadence, only for battle-gated groups.</li>
 *   <li>If the AABB would exceed {@code maxCells}, <b>increase cell size</b> until under the
 *       cap — never grow the array past the cap.</li>
 *   <li>Reuse {@code friendly[]}/{@code enemy[]} across rebuilds; grow capacity only when the
 *       current grid needs more cells than last time.</li>
 * </ul>
 *
 * <p>Pure over plain {@link UnitPos} inputs — no world types — so {@code InfluenceMapSelfCheck}
 * can assert centroids, axis, flanks, pockets, and cell-cap downscale headless.
 */
public final class InfluenceMap {

    /** Deposit radius in cells — coarse; resolution is for gradients, not accuracy. */
    private static final int DEPOSIT_CELLS = 3;

    private float[] friendly = new float[0];
    private float[] enemy = new float[0];
    private int width;
    private int height;
    private double originX;
    private double originZ;
    private double cellSize = 12.0;

    public int width() {
        return this.width;
    }

    public int height() {
        return this.height;
    }

    public double cellSize() {
        return this.cellSize;
    }

    public double originX() {
        return this.originX;
    }

    public double originZ() {
        return this.originZ;
    }

    public int cellCount() {
        return this.width * this.height;
    }

    float friendlyAt(int i, int j) {
        return this.friendly[idx(i, j)];
    }

    float enemyAt(int i, int j) {
        return this.enemy[idx(i, j)];
    }

    /**
     * Rebuild the grid from plain unit samples. Arrays are cleared and rewritten in place;
     * capacity grows only when {@code width*height} exceeds the previous buffer.
     *
     * @param units      friendly + opposing samples (faction ordinal matches {@link UnitPos})
     * @param ourFaction faction ordinal of the battle group
     * @param baseCell   configured cell size (increased if the AABB would exceed {@code maxCells})
     * @param maxCells   hard cap on {@code width * height}
     * @param margin     padding added to the units' AABB before gridding
     */
    public void rebuild(List<UnitPos> units, int ourFaction, double baseCell, int maxCells, double margin) {
        if (units.isEmpty() || maxCells < 1 || baseCell <= 0.0) {
            this.width = 0;
            this.height = 0;
            return;
        }

        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (UnitPos u : units) {
            if (u.x < minX) minX = u.x;
            if (u.x > maxX) maxX = u.x;
            if (u.z < minZ) minZ = u.z;
            if (u.z > maxZ) maxZ = u.z;
        }
        minX -= margin;
        maxX += margin;
        minZ -= margin;
        maxZ += margin;

        double spanX = Math.max(maxX - minX, baseCell);
        double spanZ = Math.max(maxZ - minZ, baseCell);
        double cell = chooseCellSize(spanX, spanZ, baseCell, maxCells);

        int w = Math.max(1, (int) Math.ceil(spanX / cell));
        int h = Math.max(1, (int) Math.ceil(spanZ / cell));
        // Final guard — floating error on ceil after downscale.
        while ((long) w * h > maxCells) {
            cell *= 1.05;
            w = Math.max(1, (int) Math.ceil(spanX / cell));
            h = Math.max(1, (int) Math.ceil(spanZ / cell));
        }

        this.cellSize = cell;
        this.width = w;
        this.height = h;
        this.originX = minX;
        this.originZ = minZ;

        int n = w * h;
        ensureCapacity(n);
        for (int k = 0; k < n; k++) {
            this.friendly[k] = 0.0F;
            this.enemy[k] = 0.0F;
        }

        double falloff = cell * DEPOSIT_CELLS;
        double falloffSq = falloff * falloff;
        for (UnitPos u : units) {
            boolean ours = u.faction == ourFaction;
            int ci = clamp((int) Math.floor((u.x - this.originX) / cell), 0, w - 1);
            int cj = clamp((int) Math.floor((u.z - this.originZ) / cell), 0, h - 1);
            int i0 = Math.max(0, ci - DEPOSIT_CELLS);
            int i1 = Math.min(w - 1, ci + DEPOSIT_CELLS);
            int j0 = Math.max(0, cj - DEPOSIT_CELLS);
            int j1 = Math.min(h - 1, cj + DEPOSIT_CELLS);
            for (int j = j0; j <= j1; j++) {
                for (int i = i0; i <= i1; i++) {
                    double cx = this.originX + (i + 0.5) * cell;
                    double cz = this.originZ + (j + 0.5) * cell;
                    double dx = u.x - cx;
                    double dz = u.z - cz;
                    double d2 = dx * dx + dz * dz;
                    if (d2 > falloffSq) continue;
                    float wgt = (float) (1.0 - Math.sqrt(d2) / falloff);
                    if (wgt <= 0.0F) continue;
                    int k = idx(i, j);
                    if (ours) this.friendly[k] += wgt;
                    else this.enemy[k] += wgt;
                }
            }
        }
    }

    /**
     * Fill {@code out} from this map + the same unit list. Centroids are arithmetic means of
     * unit positions (friendly / opposing) so Stage 2 centrality stays continuous when the
     * source switches here.
     */
    public void derive(BattleField out, List<UnitPos> units, int ourFaction) {
        out.clear();
        if (units.isEmpty() || this.width <= 0 || this.height <= 0) return;

        double fSumX = 0.0, fSumZ = 0.0;
        double eSumX = 0.0, eSumZ = 0.0;
        int fN = 0, eN = 0;
        for (UnitPos u : units) {
            if (u.faction == ourFaction) {
                fSumX += u.x;
                fSumZ += u.z;
                fN++;
            } else {
                eSumX += u.x;
                eSumZ += u.z;
                eN++;
            }
        }
        out.friendlyCount = fN;
        out.enemyCount = eN;
        out.forceBalance = fN / (double) Math.max(eN, 1);
        if (fN == 0) return; // no friendly picture — leave unpopulated

        out.friendlyCentroidX = fSumX / fN;
        out.friendlyCentroidZ = fSumZ / fN;
        if (eN > 0) {
            out.enemyCentroidX = eSumX / eN;
            out.enemyCentroidZ = eSumZ / eN;
        } else {
            out.enemyCentroidX = out.friendlyCentroidX;
            out.enemyCentroidZ = out.friendlyCentroidZ;
        }

        // Enemy → us axis.
        double ax = out.friendlyCentroidX - out.enemyCentroidX;
        double az = out.friendlyCentroidZ - out.enemyCentroidZ;
        double alen = Math.sqrt(ax * ax + az * az);
        if (alen > 1.0e-6) {
            out.axisX = ax / alen;
            out.axisZ = az / alen;
        }

        findPockets(out);
        scoreFlanks(out);
        out.populated = true;
    }

    /**
     * Rebuild + derive in one shot. Preferred entry for the coordinator and self-check.
     */
    public void rebuildAndDerive(BattleField out, List<UnitPos> units, int ourFaction,
                                 double baseCell, int maxCells, double margin) {
        rebuild(units, ourFaction, baseCell, maxCells, margin);
        derive(out, units, ourFaction);
    }

    /**
     * Choose the smallest cell size ≥ {@code base} such that {@code ceil(span/cell)^2} fits
     * under {@code maxCells}. Increases resolution coarseness rather than growing the array.
     */
    static double chooseCellSize(double spanX, double spanZ, double base, int maxCells) {
        double cell = base;
        int w = Math.max(1, (int) Math.ceil(spanX / cell));
        int h = Math.max(1, (int) Math.ceil(spanZ / cell));
        if ((long) w * h <= maxCells) return cell;

        // Jump to the theoretical minimum for a rectangular AABB, then nudge for ceil.
        double needed = Math.sqrt((spanX * spanZ) / (double) maxCells);
        cell = Math.max(base, needed);
        for (int guard = 0; guard < 32; guard++) {
            w = Math.max(1, (int) Math.ceil(spanX / cell));
            h = Math.max(1, (int) Math.ceil(spanZ / cell));
            if ((long) w * h <= maxCells) return cell;
            cell *= 1.05;
        }
        return cell;
    }

    private void findPockets(BattleField out) {
        // Collect local maxima of enemy influence, strongest first; greedily keep those far
        // enough apart that two separated clusters stay two pockets.
        int w = this.width;
        int h = this.height;
        int cap = w * h;
        // Scratch: peak strength + flat index. Cap scan — we only need a few.
        int[] peakIdx = new int[Math.min(cap, 64)];
        float[] peakStr = new float[peakIdx.length];
        int peakN = 0;

        float threshold = 0.15F;
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                float v = this.enemy[idx(i, j)];
                if (v < threshold) continue;
                if (!isLocalMax(i, j, v)) continue;
                if (peakN < peakIdx.length) {
                    peakIdx[peakN] = idx(i, j);
                    peakStr[peakN] = v;
                    peakN++;
                } else {
                    // Replace weakest if stronger.
                    int weak = 0;
                    for (int p = 1; p < peakN; p++) {
                        if (peakStr[p] < peakStr[weak]) weak = p;
                    }
                    if (v > peakStr[weak]) {
                        peakIdx[weak] = idx(i, j);
                        peakStr[weak] = v;
                    }
                }
            }
        }

        // Sort peaks by strength descending (tiny n — insertion).
        for (int a = 1; a < peakN; a++) {
            float s = peakStr[a];
            int id = peakIdx[a];
            int b = a - 1;
            while (b >= 0 && peakStr[b] < s) {
                peakStr[b + 1] = peakStr[b];
                peakIdx[b + 1] = peakIdx[b];
                b--;
            }
            peakStr[b + 1] = s;
            peakIdx[b + 1] = id;
        }

        double mergeDist = this.cellSize * 2.5;
        double mergeSq = mergeDist * mergeDist;
        out.pocketCount = 0;
        for (int p = 0; p < peakN && out.pocketCount < BattleField.MAX_POCKETS; p++) {
            int flat = peakIdx[p];
            int pi = flat % w;
            int pj = flat / w;
            double px = this.originX + (pi + 0.5) * this.cellSize;
            double pz = this.originZ + (pj + 0.5) * this.cellSize;
            boolean near = false;
            for (int q = 0; q < out.pocketCount; q++) {
                double dx = px - out.pocketX[q];
                double dz = pz - out.pocketZ[q];
                if (dx * dx + dz * dz < mergeSq) {
                    near = true;
                    break;
                }
            }
            if (near) continue;
            out.pocketX[out.pocketCount] = px;
            out.pocketZ[out.pocketCount] = pz;
            out.pocketCount++;
        }

        // No peaks above threshold but enemies exist — one pocket at the enemy centroid.
        if (out.pocketCount == 0 && out.enemyCount > 0) {
            out.pocketX[0] = out.enemyCentroidX;
            out.pocketZ[0] = out.enemyCentroidZ;
            out.pocketCount = 1;
        }
    }

    private boolean isLocalMax(int i, int j, float v) {
        for (int dj = -1; dj <= 1; dj++) {
            for (int di = -1; di <= 1; di++) {
                if (di == 0 && dj == 0) continue;
                int ni = i + di;
                int nj = j + dj;
                if (ni < 0 || nj < 0 || ni >= this.width || nj >= this.height) continue;
                if (this.enemy[idx(ni, nj)] > v) return false;
            }
        }
        return true;
    }

    /**
     * Open flank = no secondary enemy pocket on that side of the primary pocket, relative to
     * the enemy→us axis (left = 90° CCW). A single blob → both flanks open; a wing/pocket on
     * one side closes only that side.
     */
    private void scoreFlanks(BattleField out) {
        out.openFlankLeft = false;
        out.openFlankRight = false;
        if (out.enemyCount == 0 || out.pocketCount == 0) return;
        double ax = out.axisX;
        double az = out.axisZ;
        if (ax * ax + az * az < 1.0e-12) return;

        double leftX = -az;
        double leftZ = ax;
        out.openFlankLeft = true;
        out.openFlankRight = true;
        if (out.pocketCount == 1) return;

        double px = out.pocketX[0];
        double pz = out.pocketZ[0];
        double closeDist = this.cellSize * 2.0;
        for (int q = 1; q < out.pocketCount; q++) {
            double side = (out.pocketX[q] - px) * leftX + (out.pocketZ[q] - pz) * leftZ;
            if (side > closeDist) out.openFlankLeft = false;
            else if (side < -closeDist) out.openFlankRight = false;
        }
    }

    private void ensureCapacity(int n) {
        if (this.friendly.length < n) {
            this.friendly = new float[n];
            this.enemy = new float[n];
        }
    }

    private int idx(int i, int j) {
        return j * this.width + i;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
