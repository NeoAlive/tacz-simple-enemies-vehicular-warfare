package com.neoalive.tacz_sewv.mixin;

import com.electronwill.nightconfig.core.CommentedConfig;
import net.minecraftforge.common.ForgeConfigSpec;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.config.ConfigKeyMigration;
import com.neoalive.tacz_sewv.config.SewvConfig;

/**
 * Renamed-key carry-over for this mod's server config — see {@link ConfigKeyMigration}. Runs at the
 * HEAD of {@code setConfig}, i.e. before Forge's correction pass deletes keys the spec no longer
 * knows. Every other mod's spec passes straight through. Forge's own classes are not obfuscated,
 * hence {@code remap = false}.
 */
@Mixin(value = ForgeConfigSpec.class, remap = false)
public abstract class MixinForgeConfigSpec {

    @Inject(method = "setConfig", at = @At("HEAD"))
    private void tacz_sewv$migrateRenamedKeys(CommentedConfig config, CallbackInfo ci) {
        if ((Object) this == SewvConfig.SPEC && config != null) {
            ConfigKeyMigration.apply(config);
        }
    }
}
