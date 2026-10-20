package com.neoalive.tacz_sewv.mixin;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.ConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.neoalive.tacz_sewv.config.ConfigKeyMigration;
import com.neoalive.tacz_sewv.config.SewvConfig;

/**
 * Renamed-key carry-over for this mod's server config — see {@link ConfigKeyMigration}. Every other
 * mod's spec passes straight through.
 *
 * <p>NeoForge port: Forge corrected inside {@code setConfig}; NeoForge has no such method. FML's
 * {@code ConfigTracker} instead asks {@code isCorrect} and, when a stale key makes that false, calls
 * {@code correct(CommentedConfig)} — which (like {@code acceptConfig}'s own fallback) funnels into the
 * 3-arg {@code correct} hooked here. Running at its HEAD keeps the old contract: values move to their
 * new paths before the correction pass deletes the keys the spec no longer knows.
 */
@Mixin(value = ModConfigSpec.class, remap = false)
public abstract class MixinForgeConfigSpec {

    @Inject(method = "correct(Lcom/electronwill/nightconfig/core/CommentedConfig;"
            + "Lcom/electronwill/nightconfig/core/ConfigSpec$CorrectionListener;"
            + "Lcom/electronwill/nightconfig/core/ConfigSpec$CorrectionListener;)I", at = @At("HEAD"))
    private void tacz_sewv$migrateRenamedKeys(CommentedConfig config, ConfigSpec.CorrectionListener listener,
                                              ConfigSpec.CorrectionListener commentListener,
                                              CallbackInfoReturnable<Integer> cir) {
        if ((Object) this == SewvConfig.SPEC && config != null) {
            ConfigKeyMigration.apply(config);
        }
    }
}
