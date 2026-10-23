package com.neoalive.tacz_sewv.mixin;

import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;
import net.nekoyuni.SimpleEnemyMod.entity.ai.goals.RangedGunAttackGoal;
import net.nekoyuni.SimpleEnemyMod.entity.ai.goals.utils.AiGunSpreadHelper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.config.EasyMode;
import com.neoalive.tacz_sewv.config.EasyTuning;

/**
 * Easy Mode for SEM's rifle fire: wide spread, short bursts, long pauses and a reaction delay —
 * all only for a hostile unit ({@link EasyMode#softInfantry}). Normal play is untouched: every
 * handler returns SEM's own value when Easy does not apply.
 *
 * <p>SEM moves things between builds, so every injector is {@code require = 0}: on a build that
 * renamed one of these, that lever silently does nothing rather than failing the game. The TaCZ
 * bullet-damage backstop in {@code EasyModePlayerEvents} keeps Easy meaningful either way.
 *
 * <p>The burst fields are copied from SEM's config when the goal is built, so they are redirected
 * where they are read rather than rewritten: that takes effect for units already in the world.
 */
@Mixin(RangedGunAttackGoal.class)
public abstract class MixinSemRifleEasy {

    @Shadow(remap = false)
    @Final
    private Mob mob;

    @Shadow(remap = false)
    private int attackDelay;

    @Shadow(remap = false)
    @Final
    private int MIN_BURST_SHOTS;

    @Shadow(remap = false)
    @Final
    private int MAX_BURST_SHOTS;

    @Shadow(remap = false)
    @Final
    private int MIN_BURST_COOLDOWN_TICKS;

    @Shadow(remap = false)
    @Final
    private int MAX_BURST_COOLDOWN_TICKS;

    /**
     * SEM caps spread at ±1.2° while its base is already 1.4°, so every shot it fires is at the
     * cap. Easy re-rolls the offset from the uncapped distance spread, floored — SEM's own config
     * cap is deliberately not read, since its home moves between SEM versions.
     */
    @Redirect(method = "handleBurstFiringState",
            at = @At(value = "INVOKE",
                    target = "Lnet/nekoyuni/SimpleEnemyMod/entity/ai/goals/utils/AiGunSpreadHelper;CalculateSpread(FFFFLnet/minecraft/util/RandomSource;)F"),
            remap = false, require = 0)
    private float tacz_sewv$easySpread(float angle, float distance, float base, float perBlock, RandomSource random) {
        if (!EasyMode.softInfantry(this.mob)) {
            return AiGunSpreadHelper.CalculateSpread(angle, distance, base, perBlock, random);
        }
        float spread = Math.max(EasyTuning.RIFLE_SPREAD_FLOOR_DEG, base + Math.max(0.0f, distance - 5.0f) * perBlock);
        return angle + (random.nextFloat() - 0.5f) * 2.0f * spread;
    }

    @Redirect(method = "handleBurstFiringState",
            at = @At(value = "FIELD",
                    target = "Lnet/nekoyuni/SimpleEnemyMod/entity/ai/goals/RangedGunAttackGoal;MIN_BURST_SHOTS:I"),
            remap = false, require = 0)
    private int tacz_sewv$easyMinBurst(RangedGunAttackGoal self) {
        return EasyMode.softInfantry(this.mob) ? EasyTuning.RIFLE_BURST_MIN : this.MIN_BURST_SHOTS;
    }

    @Redirect(method = "handleBurstFiringState",
            at = @At(value = "FIELD",
                    target = "Lnet/nekoyuni/SimpleEnemyMod/entity/ai/goals/RangedGunAttackGoal;MAX_BURST_SHOTS:I"),
            remap = false, require = 0)
    private int tacz_sewv$easyMaxBurst(RangedGunAttackGoal self) {
        return EasyMode.softInfantry(this.mob) ? EasyTuning.RIFLE_BURST_MAX : this.MAX_BURST_SHOTS;
    }

    @Redirect(method = "handleBurstFiringState",
            at = @At(value = "FIELD",
                    target = "Lnet/nekoyuni/SimpleEnemyMod/entity/ai/goals/RangedGunAttackGoal;MIN_BURST_COOLDOWN_TICKS:I"),
            remap = false, require = 0)
    private int tacz_sewv$easyMinCooldown(RangedGunAttackGoal self) {
        return EasyMode.softInfantry(this.mob)
                ? EasyTuning.RIFLE_BURST_COOLDOWN_MIN : this.MIN_BURST_COOLDOWN_TICKS;
    }

    @Redirect(method = "handleBurstFiringState",
            at = @At(value = "FIELD",
                    target = "Lnet/nekoyuni/SimpleEnemyMod/entity/ai/goals/RangedGunAttackGoal;MAX_BURST_COOLDOWN_TICKS:I"),
            remap = false, require = 0)
    private int tacz_sewv$easyMaxCooldown(RangedGunAttackGoal self) {
        return EasyMode.softInfantry(this.mob)
                ? EasyTuning.RIFLE_BURST_COOLDOWN_MAX : this.MAX_BURST_COOLDOWN_TICKS;
    }

    /** SEM waits 1-3 ticks before a new engagement's first shot; Easy waits ~2 s. */
    @Inject(method = "start", at = @At("TAIL"), require = 0)
    private void tacz_sewv$easyReaction(CallbackInfo ci) {
        if (!EasyMode.softInfantry(this.mob)) return;
        int span = EasyTuning.RIFLE_REACTION_MAX - EasyTuning.RIFLE_REACTION_MIN + 1;
        this.attackDelay = Math.max(this.attackDelay,
                EasyTuning.RIFLE_REACTION_MIN + this.mob.getRandom().nextInt(span));
    }
}
