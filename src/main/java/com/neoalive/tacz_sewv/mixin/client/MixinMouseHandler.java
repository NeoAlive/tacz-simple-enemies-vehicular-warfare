package com.neoalive.tacz_sewv.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.client.radial.QuickCommandWheelScreen;

/**
 * Far Cry wheel needs raw mouse deltas while a {@link Screen} stays open. Vanilla
 * {@link MouseHandler#grabMouse()} closes the Screen, and {@code mouseMoved} only gets absolute
 * GUI coords — so when our wheel is up we steal {@code onMove} deltas and suppress camera turn.
 */
@Mixin(MouseHandler.class)
public abstract class MixinMouseHandler {

    @Shadow @Final private Minecraft minecraft;
    @Shadow private double xpos;
    @Shadow private double ypos;
    @Shadow private boolean ignoreFirstMove;

    @Inject(method = "onMove", at = @At("HEAD"), cancellable = true)
    private void tacz_sewv$divertWheelDeltas(long window, double xpos, double ypos, CallbackInfo ci) {
        Screen screen = this.minecraft.screen;
        if (!(screen instanceof QuickCommandWheelScreen wheel)) return;
        if (window != this.minecraft.getWindow().getWindow()) return;

        if (this.ignoreFirstMove) {
            this.xpos = xpos;
            this.ypos = ypos;
            this.ignoreFirstMove = false;
            ci.cancel();
            return;
        }

        double dx = xpos - this.xpos;
        double dy = ypos - this.ypos;
        this.xpos = xpos;
        this.ypos = ypos;
        wheel.feedRawDelta(dx, dy);
        ci.cancel();
    }
}
