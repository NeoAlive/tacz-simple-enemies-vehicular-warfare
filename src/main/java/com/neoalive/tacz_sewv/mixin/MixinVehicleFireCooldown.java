package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.bridge.IAiFireTracker;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.util.SmokeVision;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

@Mixin(targets = "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity")
public abstract class MixinVehicleFireCooldown implements IAiFireTracker {

    @Unique
    private long tacz_sewv$lastAiShotTick = Long.MIN_VALUE;

    // Published to the drive goal via IAiFireTracker so a crew can tell "parked and
    // shooting" from "parked and achieving nothing". Not @Unique: an interface
    // implementation has to keep its declared name.
    @Override
    public long tacz_sewv$getLastAiShotTick() {
        return this.tacz_sewv$lastAiShotTick;
    }

    // Per-tick cache for the line-of-fire verdict: SBW's AI fire loop consults
    // canShoot twice per seat per fire attempt, and the verdict (2 block clips +
    // a smoke entity query) can't change within the same tick. Keyed on the
    // shooter too — different seats track different targets.
    @Unique
    private long tacz_sewv$lineCacheTick = Long.MIN_VALUE;
    @Unique
    private int tacz_sewv$lineCacheShooterId;
    @Unique
    private boolean tacz_sewv$lineCacheBlocked;

    // The AI-crew gate lives on canShoot: SBW's AI fire loop (VehicleEntity.baseTick
    // and each tank's muzzle-flash override) checks it before every shot, for every
    // weapon, so denying it here suppresses the shot AND its flash upstream of the
    // subclass overrides. Players aim manually and aren't AbstractUnit, so they're
    // untouched. Three things are gated, cheapest first:
    //
    // 1. CEASE_FIRE order — the crew still repositions, it just doesn't shoot.
    // 2. AI fire cooldown — minimum ticks between AI shots, on top of weapon RPM.
    //    (The timestamp is stamped by the vehicleShoot injections below.)
    // 3. Line of fire — terrain (SBW never asks what's between muzzle and target,
    //    so crews would hose the wall a target hides behind; this also covers
    //    targets that never went through the scan goal's acquisition LOS check)
    //    and smoke (SmokeDecoyEntity has no collision, so a block raycast alone
    //    would land shots straight through the cloud). Turret auto-aim is
    //    independent, so the barrel keeps tracking; fire resumes when the line
    //    clears.
    @Inject(method = "canShoot", at = @At("HEAD"), cancellable = true, remap = false)
    private void tacz_sewv$gateAiFire(
            LivingEntity living, CallbackInfoReturnable<Boolean> cir) {

        if (!(living instanceof AbstractUnit unit)) return;

        if (living instanceof PmcUnitEntity pmc && pmc.getOrder() == OrderType.CEASE_FIRE) {
            cir.setReturnValue(false);
            return;
        }

        VehicleEntity self = (VehicleEntity) (Object) this;
        long now = self.level().getGameTime();
        if (this.tacz_sewv$lastAiShotTick != Long.MIN_VALUE
                && now - this.tacz_sewv$lastAiShotTick < SewvConfig.AI_FIRE_COOLDOWN_TICKS.get()) {
            cir.setReturnValue(false);
            return;
        }

        LivingEntity target = unit.getTarget();
        if (target == null) return;

        if (now != this.tacz_sewv$lineCacheTick || living.getId() != this.tacz_sewv$lineCacheShooterId) {
            this.tacz_sewv$lineCacheTick = now;
            this.tacz_sewv$lineCacheShooterId = living.getId();
            this.tacz_sewv$lineCacheBlocked = tacz_sewv$lineOfFireBlocked(self, living, target);
        }
        if (this.tacz_sewv$lineCacheBlocked) {
            cir.setReturnValue(false);
        }
    }

    @Unique
    private static boolean tacz_sewv$lineOfFireBlocked(
            VehicleEntity self, LivingEntity living, LivingEntity target) {
        Vec3 from = self.getShootPos(living, 1f);            // muzzle
        Vec3 to = target.getBoundingBox().getCenter();       // target center

        if (SewvConfig.VEHICLE_TARGET_REQUIRE_LOS.get()
                && tacz_sewv$terrainBlocksLine(self, from, target, to)) {
            return true;
        }
        return SmokeVision.lineBlockedBySmoke(
                self.level(), self, from, to, SewvConfig.SMOKE_BLOCK_RADIUS.get());
    }

    // vehicleShoot has TWO independent overloads and the AI paths split between
    // them: baseTick's AI-crew loop fires the (LivingEntity, UUID, Vec3) one,
    // while mortars/artillery/auto-aim turrets fire the (LivingEntity, String)
    // one. Both are hooked — as a backstop gate for any caller that skipped
    // canShoot, and to stamp the cooldown the canShoot gate above enforces.
    @Inject(
            method = "vehicleShoot(Lnet/minecraft/world/entity/LivingEntity;Ljava/lang/String;)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void tacz_sewv$throttleNamedFire(
            LivingEntity living, String weaponName, CallbackInfo ci) {
        tacz_sewv$gateAndStampAiShot(living, ci);
    }

    @Inject(
            method = "vehicleShoot(Lnet/minecraft/world/entity/LivingEntity;Ljava/util/UUID;Lnet/minecraft/world/phys/Vec3;)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void tacz_sewv$throttleAimedFire(
            LivingEntity living, UUID targetId, Vec3 shootVec, CallbackInfo ci) {
        tacz_sewv$gateAndStampAiShot(living, ci);
    }

    @Unique
    private void tacz_sewv$gateAndStampAiShot(LivingEntity living, CallbackInfo ci) {
        if (!(living instanceof AbstractUnit)) return;

        if (living instanceof PmcUnitEntity pmc && pmc.getOrder() == OrderType.CEASE_FIRE) {
            ci.cancel();
            return;
        }

        VehicleEntity self = (VehicleEntity) (Object) this;
        long now = self.level().getGameTime();
        if (this.tacz_sewv$lastAiShotTick != Long.MIN_VALUE
                && now - this.tacz_sewv$lastAiShotTick < SewvConfig.AI_FIRE_COOLDOWN_TICKS.get()) {
            ci.cancel();
            return;
        }
        this.tacz_sewv$lastAiShotTick = now;
    }

    // Blocked only when BOTH the target's center and its eyes are behind blocks:
    // a target peeking over low cover (sandbags, fences) has an exposed head a
    // direct-fire shot can genuinely reach, so the second raycast keeps those
    // engagements alive instead of silencing the guns against half cover.
    @Unique
    private static boolean tacz_sewv$terrainBlocksLine(
            VehicleEntity self, Vec3 from, LivingEntity target, Vec3 center) {
        if (self.level().clip(new ClipContext(from, center,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, self))
                .getType() == HitResult.Type.MISS) {
            return false;
        }
        return self.level().clip(new ClipContext(from, target.getEyePosition(),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, self))
                .getType() != HitResult.Type.MISS;
    }
}
