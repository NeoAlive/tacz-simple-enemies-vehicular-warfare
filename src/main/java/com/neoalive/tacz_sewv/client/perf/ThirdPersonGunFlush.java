package com.neoalive.tacz_sewv.client.perf;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.compat.oculus.OculusCompat;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.config.PerfFlags;

/**
 * Whether the TaCZ gun being rendered may skip its per-model buffer flushes. TaCZ flushes the whole
 * Oculus entity batch after the gun and after every attachment, with or without a shader pack. That
 * flush only matters for scoped guns (stencil masking) and shader packs, so those keep it.
 * Render thread only; nested gun renders keep their own answer.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ThirdPersonGunFlush {

    private static final int MAX_DEPTH = 8;
    private static final boolean[] STACK = new boolean[MAX_DEPTH];
    private static int depth;

    private ThirdPersonGunFlush() {}

    public static void enter(PoseStack poseStack, ItemStack gun, ItemDisplayContext context) {
        boolean skip = RenderSystem.isOnRenderThread() && decide(poseStack, gun, context);
        if (depth < MAX_DEPTH) {
            STACK[depth] = skip;
        }
        depth++;
    }

    public static void exit() {
        if (depth > 0) depth--;
    }

    public static boolean skip() {
        return depth > 0 && depth <= MAX_DEPTH && STACK[depth - 1] && RenderSystem.isOnRenderThread();
    }

    /** A gun render that threw never reached exit; never carry its answer into the next frame. */
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            depth = 0;
        }
    }

    /** Third person, world or held, not first person or GUI. */
    public static boolean isWorldContext(ItemDisplayContext context) {
        return context != ItemDisplayContext.GUI && context != ItemDisplayContext.NONE && !context.firstPerson();
    }

    private static boolean decide(PoseStack poseStack, ItemStack gun, ItemDisplayContext context) {
        if (!PerfFlags.taczThirdPersonFlushSkip() || !isWorldContext(context)) return false;
        if (OculusCompat.isUsingRenderPack()) return false;
        return !drawsScope(poseStack, gun, context);
    }

    /** Mirrors TaCZ: the SCOPE slot, falling back to the built-in scope. A far scope skipped by LOD does not count. */
    private static boolean drawsScope(PoseStack poseStack, ItemStack gun, ItemDisplayContext context) {
        IGun iGun = IGun.getIGunOrNull(gun);
        if (iGun == null) return false;
        ItemStack scope = iGun.getAttachment(gun, AttachmentType.SCOPE);
        if (scope.isEmpty()) {
            scope = iGun.getBuiltinAttachment(gun, AttachmentType.SCOPE);
        }
        if (scope == null || scope.isEmpty()) return false;
        return !AttachmentLod.skips(poseStack, context);
    }
}
