package com.neoalive.tacz_sewv.mixin;

import net.nekoyuni.SimpleEnemyMod.config.common.TacticalAiConfig;
import net.nekoyuni.SimpleEnemyMod.entity.ai.goals.SeekCoverGoal;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.neoalive.tacz_sewv.config.EasyMode;

/**
 * Easy Mode: a timid hostile rifleman re-checks for cover twice as often (SEM still floors the
 * wait at 20 ticks). {@code require = 0}, like every SEM-facing Easy injector — the config call
 * has already moved once between SEM builds.
 */
@Mixin(SeekCoverGoal.class)
public abstract class MixinSemCoverEasy {

    @Shadow(remap = false)
    @Final
    private AbstractUnit unit;

    @Redirect(method = "*",
            at = @At(value = "INVOKE",
                    target = "Lnet/nekoyuni/SimpleEnemyMod/config/common/TacticalAiConfig;coverSearchCooldownTicks()I"),
            remap = false, require = 0)
    private int tacz_sewv$easyCoverCooldown() {
        int base = TacticalAiConfig.coverSearchCooldownTicks();
        return EasyMode.timid(this.unit) ? Math.max(1, base / 2) : base;
    }
}
