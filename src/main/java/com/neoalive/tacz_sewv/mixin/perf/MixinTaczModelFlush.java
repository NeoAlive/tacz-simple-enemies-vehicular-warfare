package com.neoalive.tacz_sewv.mixin.perf;

import com.tacz.guns.client.model.BedrockAttachmentModel;
import com.tacz.guns.client.model.bedrock.BedrockModel;
import com.tacz.guns.compat.oculus.OculusCompat;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.neoalive.tacz_sewv.client.perf.ThirdPersonGunFlush;

/**
 * TaCZ ends every model draw with {@code if (!OculusCompat.endBatch(bs)) bs.endBatch(type)}.
 * Answering true while {@link ThirdPersonGunFlush#skip()} skips both flushes; the vertices stay in
 * the same buffer, in the same order, and draw with the rest of the entity pass.
 */
@Mixin(value = {BedrockModel.class, BedrockAttachmentModel.class}, remap = false)
public abstract class MixinTaczModelFlush {

    @Redirect(
            method = {"render*", "renderTempPart*"},
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/tacz/guns/compat/oculus/OculusCompat;endBatch(Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;)Z",
                    remap = false),
            remap = false,
            require = 0)
    private boolean tacz_sewv$keepBatched(MultiBufferSource.BufferSource bufferSource) {
        if (ThirdPersonGunFlush.skip()) return true;
        return OculusCompat.endBatch(bufferSource);
    }
}
