package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.EnumSet;

import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.entity.ShootResult;
import com.tacz.guns.api.item.IGun;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.support.SuppressOrders;
import com.neoalive.tacz_sewv.entity.ai.support.SuppressiveFire;
import com.neoalive.tacz_sewv.util.LoadedClip;

/**
 * An on-foot PMC working a Suppress Area order with its TaCZ gun: stand, aim at the ordered point
 * and fire SA's short bursts into a walking beaten zone until the order's budget runs out.
 *
 * <p>SEM's {@code RangedGunAttackGoal} only ever fires at a target entity, so it cannot do this; the
 * sequence here is a trimmed copy of its burst loop ({@code IGunOperator.shoot}, bolt on
 * {@code NEED_BOLT}, reload on {@code NO_AMMO}, draw on {@code NOT_DRAW}). While the order stands,
 * {@code MixinRangedGunAttackGoal} keeps SEM's goal out of the way, except in self-defence — a
 * target inside {@link SuppressOrders#FOOT_SELF_DEFENCE} ends this goal and SEM fights it, and the
 * order resumes after.
 *
 * <p>Holds MOVE and LOOK: a unit suppressing a point stands still to do it. Facing is written to the
 * body as well as the head, since a shot leaves along the body yaw (the {@code getLookAngle} trap).
 * Rounds that a wall right in front would eat are held, so a unit with no lane fires nothing and
 * the order reports that.
 */
public class SuppressAreaGoal extends Goal {

    private static final int BURST = 3;
    private static final long BURST_PAUSE_TICKS = 16;
    private static final long SHOT_GAP_TICKS = 2;
    /** A clip that stops within this of the point still counts as reaching it — the point is cover. */
    private static final double LANE_SLACK = 3.0;

    private final PmcUnitEntity unit;
    private long nextShot;
    private int shotsInBurst;

    public SuppressAreaGoal(PmcUnitEntity unit) {
        this.unit = unit;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return ready() && SuppressOrders.active(this.unit, this.unit.level().getGameTime()) != null;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    private boolean ready() {
        return !this.unit.level().isClientSide()
                && this.unit.isAlive()
                && this.unit.getVehicle() == null
                && IGun.getIGunOrNull(this.unit.getMainHandItem()) != null
                && !SuppressOrders.selfDefence(this.unit, SuppressOrders.FOOT_SELF_DEFENCE);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        this.unit.getNavigation().stop();
        this.shotsInBurst = 0;
        this.nextShot = this.unit.level().getGameTime();
        IGunOperator.fromLivingEntity(this.unit).aim(true);
    }

    @Override
    public void stop() {
        IGunOperator.fromLivingEntity(this.unit).aim(false);
    }

    @Override
    public void tick() {
        long now = this.unit.level().getGameTime();
        BlockPos point = SuppressOrders.active(this.unit, now);
        if (point == null) return;
        this.unit.getNavigation().stop();

        Vec3 eye = this.unit.getEyePosition();
        Vec3 center = Vec3.atCenterOf(point).add(0.0, 0.5, 0.0);
        Vec3 aim = SuppressiveFire.beatenZone(eye, center, this.unit.getId(), now);
        Vec3 d = aim.subtract(eye);
        double flat = Math.sqrt(d.x * d.x + d.z * d.z);
        float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90.0F;
        float pitch = (float) -(Mth.atan2(d.y, flat) * Mth.RAD_TO_DEG);
        this.unit.setYRot(yaw);
        this.unit.yBodyRot = yaw;
        this.unit.yHeadRot = yaw;
        this.unit.setXRot(pitch);

        if (now < this.nextShot) return;
        if (!laneReaches(eye, aim, center)) return;
        if (VehicleTargeting.alliedInLineOfFire(this.unit, this.unit, eye, aim)) return;
        if (VehicleTargeting.friendlyNearPoint(this.unit, aim, SewvConfig.FRIENDLY_FIRE_VEHICLE_RADIUS.get())) return;

        IGunOperator op = IGunOperator.fromLivingEntity(this.unit);
        ShootResult result = op.shoot(() -> pitch, () -> yaw);
        switch (result) {
            case SUCCESS -> {
                SuppressOrders.noteRound(this.unit);
                if (++this.shotsInBurst >= BURST) {
                    this.shotsInBurst = 0;
                    this.nextShot = now + BURST_PAUSE_TICKS;
                } else {
                    this.nextShot = now + SHOT_GAP_TICKS;
                }
            }
            case NEED_BOLT -> op.bolt();
            case NO_AMMO -> op.reload();
            case NOT_DRAW -> op.draw(this.unit::getMainHandItem);
            default -> { /* cooling down, reloading, drawing: try again next tick */ }
        }
    }

    private boolean laneReaches(Vec3 eye, Vec3 aim, Vec3 center) {
        BlockHitResult hit = LoadedClip.clip(this.unit.level(),
                new ClipContext(eye, aim, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this.unit));
        return hit == null || hit.getType() == HitResult.Type.MISS
                || hit.getLocation().distanceTo(center) <= LANE_SLACK;
    }
}
