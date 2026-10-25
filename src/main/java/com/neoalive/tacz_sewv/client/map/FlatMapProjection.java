package com.neoalive.tacz_sewv.client.map;

/**
 * A north-up map centred on a camera: the World Map's projection. {@code pxPerBlock} is the map's
 * window-pixel zoom divided by the GUI scale factor — the same {@code scale / screenScale} the map
 * divides by when it sets up its own transform. A real class rather than an anonymous one built in
 * {@code MixinGuiMap}, which would be merged into Xaero's screen along with javac's accessors.
 */
public record FlatMapProjection(double cameraX, double cameraZ, double pxPerBlock, int width, int height)
        implements MapProjection {

    @Override
    public int[] toScreen(double worldX, double worldZ) {
        double px = (worldX - this.cameraX) * this.pxPerBlock;
        double pz = (worldZ - this.cameraZ) * this.pxPerBlock;
        return new int[]{(int) Math.round(this.width / 2.0 + px), (int) Math.round(this.height / 2.0 + pz)};
    }

    @Override
    public double lengthPx(double blocks) {
        return blocks * this.pxPerBlock;
    }

    @Override
    public boolean intersects(int x0, int y0, int x1, int y1) {
        return x1 >= 0 && y1 >= 0 && x0 <= this.width && y0 <= this.height;
    }
}
