package com.neoalive.tacz_sewv.mixin;

import java.util.UUID;

import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.server.level.ServerLevel;
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

import com.neoalive.tacz_sewv.bridge.IAiFireTracker;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.navigation.VehiclePathObstacles;
import com.neoalive.tacz_sewv.util.SmokeVision;

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

    // Cache for the line-of-fire verdict, valid for a small rolling window rather than just the
    // current tick: SBW's AI fire loop consults canShoot at least twice per seat per fire
    // attempt, and for a fast automatic weapon (see tacz_sewv$effectiveCooldown) the AI cooldown
    // gate above stops blocking the call after 1 tick, which would otherwise re-run the full
    // verdict (occupancy check, allied-vehicle check, 2 block clips, a smoke entity query) every
    // single tick for as long as the weapon keeps firing. Terrain/smoke occlusion does not change
    // meaningfully within a few ticks, so LOS_CACHE_WINDOW_TICKS trades a small amount of staleness
    // (a target that fully breaks LOS mid-burst can draw up to that many extra ticks of fire) for
    // a large cut in raycast frequency on high-RPM weapons. Keyed on the shooter too — different
    // seats track different targets.
    @Unique
    private static final int LOS_CACHE_WINDOW_TICKS = 3;
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
                && now - this.tacz_sewv$lastAiShotTick
                        < tacz_sewv$effectiveCooldown(self, living)) {
            cir.setReturnValue(false);
            return;
        }

        LivingEntity target = unit.getTarget();
        if (target == null) return;

        boolean lineCacheExpired = this.tacz_sewv$lineCacheTick == Long.MIN_VALUE
                || now - this.tacz_sewv$lineCacheTick >= LOS_CACHE_WINDOW_TICKS;
        if (lineCacheExpired || living.getId() != this.tacz_sewv$lineCacheShooterId) {
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

        // Empty hulls and wrecks: the allied check below only names crewed friends,
        // so a parked wreck between muzzle and target was a free shot. Same occupancy
        // map infantry use; this hull and the target's ride are excluded.
        if (self.level() instanceof ServerLevel server
                && VehiclePathObstacles.occludes(server, from, to,
                        self.getId(), VehiclePathObstacles.rideId(target))) {
            return true;
        }
        // Hold fire if a friendly hull is in the way — prevents the crew shelling
        // an ally that has driven between it and its target (living is always an
        // AbstractUnit here; the caller gated on it). Extra 1-block margin beyond
        // the occupancy voxels, for near-grazes and shell blast.
        if (living instanceof AbstractUnit unit
                && VehicleTargeting.alliedVehicleInLineOfFire(unit, self, from, to)) {
            return true;
        }
        // Danger-close: enemy next to the owning player / a friendly PMC (TOWs and
        // other splashy vehicle weapons). Radius 0 disables.
        if (living instanceof AbstractUnit unit
                && VehicleTargeting.friendlyNearPoint(
                        unit, to, SewvConfig.FRIENDLY_FIRE_VEHICLE_RADIUS.get())) {
            return true;
        }
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

        // Mortars are exempt: ManMortarGoal already paces them with
        // mortarFireCooldownTicks and only shoots once the barrel has settled, so
        // throttling here as well would silently tie a mortar's rate of fire to a tank
        // setting. The line-of-fire gate on canShoot never applied to them anyway —
        // they're indirect fire, and this overload doesn't consult canShoot.
        if ((Object) this instanceof MortarEntity) return;

        if (living instanceof PmcUnitEntity pmc && pmc.getOrder() == OrderType.CEASE_FIRE) {
            ci.cancel();
            return;
        }

        VehicleEntity self = (VehicleEntity) (Object) this;
        long now = self.level().getGameTime();
        if (this.tacz_sewv$lastAiShotTick != Long.MIN_VALUE
                && now - this.tacz_sewv$lastAiShotTick
                        < tacz_sewv$effectiveCooldown(self, living)) {
            ci.cancel();
            return;
        }
        this.tacz_sewv$lastAiShotTick = now;
    }

    /**
     * The configured wait, but never slower than the weapon's own cyclic rate allows.
     *
     * <p>{@code aiFireCooldownTicks} is a pacing knob written for a main gun: a tank shell, a TOW
     * missile, one loud thing at a time. Applied flat it also rate-limits an <b>automatic</b>
     * weapon, and there it is not pacing, it is a different gun. The A-10's GAU-8 declares 1200
     * RPM — a round a tick — and five ticks between shots turns it into a 240 RPM cannon, so a
     * firing pass of a second and a half delivers six rounds where it should deliver thirty.
     * Volume of fire is the entire reason a 4-block-blast cannon can hit anything from a moving
     * aircraft, and throttling it read in-game as an accuracy problem rather than a rate one.
     *
     * <p>Taking the minimum is safe for the weapons the knob was written for: a 12 RPM tank gun
     * has a natural interval of 100 ticks, so the configured 5 still binds — and SBW's own RPM
     * gate independently enforces the 100 regardless. Only a weapon that genuinely cycles faster
     * than the config is let off it.
     */
    @Unique
    private static int tacz_sewv$effectiveCooldown(VehicleEntity self, LivingEntity living) {
        int configured = SewvConfig.AI_FIRE_COOLDOWN_TICKS.get();
        try {
            int rpm = self.vehicleWeaponRpm(living);
            if (rpm <= 0) return configured;
            return Math.min(configured, Math.max(1, (int) Math.ceil(1200.0 / rpm)));
        } catch (Exception e) {
            return configured;
        }
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
