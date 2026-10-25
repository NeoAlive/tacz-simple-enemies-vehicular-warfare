package com.neoalive.tacz_sewv.client.map;

/**
 * The minimap's projection: centred on the player at (0, 0), rotated by the map's own
 * {@code ps}/{@code pc}, in the framebuffer pixels Xaero draws its elements in. Built from the
 * handler's live transform each frame (see {@code IMinimapProjectionView}), so a line drawn with it
 * meets the icons Xaero placed exactly.
 *
 * @param originX  world X at the map centre (Xaero's {@code renderPos})
 * @param originZ  world Z at the map centre
 * @param halfView half-width of the visible square, in framebuffer pixels
 */
public record RotatedMapProjection(double ps, double pc, double zoom, double originX, double originZ,
                                   float halfView) implements MapProjection {

    @Override
    public int[] toScreen(double worldX, double worldZ) {
        double dx = worldX - this.originX;
        double dz = worldZ - this.originZ;
        return new int[]{
                (int) Math.round(MinimapMath.projectX(this.ps, this.pc, this.zoom, dx, dz)),
                (int) Math.round(MinimapMath.projectY(this.ps, this.pc, this.zoom, dx, dz))};
    }

    @Override
    public double lengthPx(double blocks) {
        return blocks * this.zoom;
    }

    @Override
    public boolean intersects(int x0, int y0, int x1, int y1) {
        return x1 >= -this.halfView && y1 >= -this.halfView && x0 <= this.halfView && y0 <= this.halfView;
    }
}
