package com.neoalive.tacz_sewv.bridge;

/**
 * Read access to the transform Xaero's in-minimap element handler places elements with, mixed onto
 * {@code MinimapElementMapRendererHandler} by {@code MixinMinimapMapRendererHandler}. The handler
 * keeps these private and only hands each element its own translated position, which is enough for
 * an icon but not for a line between two world points — the overlays need the transform itself.
 *
 * <p>Element offset {@code (dx, dz)} in blocks lands at
 * {@code X = ps·dx·zoom − pc·dz·zoom}, {@code Y = pc·dx·zoom + ps·dz·zoom}, centred on the player;
 * {@code halfView} is the half-width of the visible square in the same pixels.
 */
public interface IMinimapProjectionView {

    double sewv$ps();

    double sewv$pc();

    double sewv$zoom();

    float sewv$halfView();
}
