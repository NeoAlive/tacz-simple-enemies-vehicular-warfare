package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.item.gun.GunItem;
import com.neoalive.tacz_sewv.config.SewvConfig;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import java.util.EnumSet;

/**
 * Works a SuperbWarfare launcher for a unit on foot — the firing half of what
 * {@link SmallArmsSupport} hands out.
 *
 * <p>SuperbWarfare <em>does</em> ship a mob gun AI ({@code GunShootGoal}), and it cannot be
 * used: it takes a {@code MobGunData}, whose constructor is private and whose only factory
 * ({@code MobGunData.from}) returns null unless the mob's <b>entity type</b> has an entry in the
 * {@code sbw/mob_guns} datapack. Shipping such an entry would arm every unit of that type at
 * spawn, which is the opposite of "one or two men out of a dismounting squad". So this goal
 * exists — but it is a deliberate trim of that one, and the sequence below (tick the gun, start
 * a reload, start a bolt, then shoot) is SuperbWarfare's, not an invention.
 *
 * <p>Nothing in the fire path is player-only: {@code GunData.shoot/canShoot/tick/reloadAmmo} all
 * take a plain {@code Entity}, and the {@code ServerPlayer} branches inside them are first-person
 * sounds and animation packets. Handing the target's UUID straight to {@code shoot} also skips
 * the Javelin's lock-on entirely — that state machine is client-side, and a UUID arriving here
 * is exactly what its packet would have delivered, giving an entity-guided missile.
 *
 * <p><b>Claims LOOK only.</b> It aims and it fires; it never navigates. SEM's own
 * {@code MoveToAttackRangeGoal} owns MOVE and walks the unit to within 90 blocks, and taking
 * MOVE away from it here would leave an AT gunner rooted wherever it landed.
 */
public class AtWeaponGoal extends Goal {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /**
     * Set once SuperbWarfare's fire path has thrown — see {@link #tacz_sewv$reportBrokenFirePath}.
     * A broken fire path is a property of the installed mod set, so this latches for the session
     * instead of being retried per shot.
     */
    private static volatile boolean FIRE_PATH_BROKEN = false;

    private final AbstractUnit unit;
    /** Goal ticks the target has been tracked for. Reset whenever the target is lost. */
    private int aimTicks;

    public AtWeaponGoal(AbstractUnit unit) {
        this.unit = unit;
        this.setFlags(EnumSet.of(Flag.LOOK));
    }

    /**
     * Gated on the weapon in hand, not on faction or on how it got there.
     *
     * <p>{@link SmallArmsSupport} only ever arms RU/US dismounts, but this side has no reason to
     * care: a PMC a player hands an RPG to fires it too, for free.
     */
    @Override
    public boolean canUse() {
        if (FIRE_PATH_BROKEN) return false; // an incompatible mod broke SBW's fire path — stay down
        if (this.unit.level().isClientSide()) return false;
        if (this.unit.isPassenger()) return false; // a mounted crew works the hull's weapons

        LivingEntity target = this.unit.getTarget();
        if (target == null || !target.isAlive()) return false;

        GunData gun = gun();
        // Out of rockets AND out of magazine: the goal stops rather than idling on a dead tube,
        // which lets SEM's own goals have the unit back.
        return gun != null && (gun.countBackupAmmo(this.unit) > 0 || gun.hasEnoughAmmoToShoot(this.unit));
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        this.aimTicks = 0;
    }

    @Override
    public void stop() {
        this.aimTicks = 0;
    }

    @Override
    public void tick() {
        LivingEntity target = this.unit.getTarget();
        if (target == null) return;

        GunData gun = gun();
        if (gun == null) return;

        this.unit.getLookControl().setLookAt(target, 30.0F, 30.0F);
        faceTarget(target);

        // Mobs get no inventoryTick, so nothing else in the game advances this gun's reload,
        // bolt and heat timers. Without this call the launcher fires once and never reloads.
        gun.tick(this.unit, true);
        if (gun.shouldStartReloading(this.unit)) gun.startReload();
        if (gun.shouldStartBolt()) gun.startBolt();

        // Line of sight is what the aim delay is actually measuring; a target behind a wall
        // resets it rather than counting down toward a shot into the wall.
        if (!this.unit.getSensing().hasLineOfSight(target)) {
            this.aimTicks = 0;
            return;
        }
        if (this.aimTicks < SewvConfig.AT_AIM_TICKS.get()) {
            this.aimTicks++;
            return;
        }

        // A FIRE gate, not an approach order. SEM stops closing at 90 blocks whatever it is
        // holding, so an unguided rocket would otherwise be lobbed from far past its useful
        // range and the gunner's small issued supply would be gone before it mattered. The
        // Javelin is guided and its configured range is set well beyond this for that reason.
        double range = SewvConfig.AT_ENGAGE_RANGE.get();
        if (this.unit.distanceToSqr(target) > range * range) return;

        // No rate limiter of our own. Both launchers hold a single round, so canShoot() is false
        // for the whole reload cycle after every shot (RPG 100 ticks, Javelin 78) and the reload
        // IS the rate of fire. Adding an RPM timer on top, the way GunShootGoal must for
        // magazine-fed weapons, would only ever be the slacker of the two constraints.
        if (!gun.canShoot(this.unit)) return;

        // zoom = true is REQUIRED, not cosmetic: JavelinItem.shoot returns immediately without
        // it, so a Javelin gunner would track its target forever and never launch.
        try {
            gun.shoot(this.unit, 0.0, true, target.getUUID());
        } catch (Throwable t) {
            tacz_sewv$reportBrokenFirePath(t);
        }
    }

    /**
     * Stand every AT gunner down after the SuperbWarfare fire path throws.
     *
     * <p>Third-party mods inject into that path and can be flatly incompatible with the installed
     * SBW: Gunfire Overhaul 0.1.6-a, for instance, mixes into {@code GunItem.playFireSounds} and
     * reads {@code AmmoConsumer.type} directly, which is <b>private</b> in SBW 0.8.9 (there is a
     * public {@code getType()}), so every shot dies with {@code IllegalAccessError}. None of that is
     * ours to fix — but this goal ticks on every AT gunner every tick, and an AI goal must never be
     * able to take the server down, which is the same rule the rest of this package follows.
     *
     * <p>{@code Throwable}, not {@code Exception}: {@code IllegalAccessError} is a
     * {@code LinkageError} and would sail straight through a narrower catch.
     *
     * <p>Latched globally rather than retried, because a broken fire path is a property of the
     * INSTALL, not of this shot — retrying would rethrow on every reload cycle and bury the log.
     * The cost is honest and worth stating: AT gunners keep their launchers but stop firing, so the
     * feature is inert until the offending mod is updated or removed.
     */
    private static void tacz_sewv$reportBrokenFirePath(Throwable t) {
        if (FIRE_PATH_BROKEN) return;
        FIRE_PATH_BROKEN = true;
        LOGGER.error("SuperbWarfare's gun-fire path threw, so AI anti-tank gunners are standing down"
                + " for this session. This is an incompatibility between SuperbWarfare and another"
                + " mod injecting into it (Gunfire Overhaul is a known case: it reads the private"
                + " AmmoConsumer.type and fails with IllegalAccessError on SBW 0.8.9), NOT a fault in"
                + " the weapon or the crew. The same crash occurs if a PLAYER fires a SuperbWarfare"
                + " gun. Update or remove that mod to restore AT gunners.", t);
    }

    /**
     * Square the unit's <b>body</b> onto the target, not just its head.
     *
     * <p>This is the whole aiming mechanism, and it is not optional. Every SuperbWarfare launch
     * direction comes from {@code shooter.getLookAngle()}, which is built from {@code getYRot()}
     * and {@code getXRot()} — and on a Mob, {@code getYRot()} is the <b>body</b> yaw.
     * {@code LookControl.setLookAt} drives {@code yHeadRot} and the pitch; it never touches body
     * yaw, which otherwise only follows where the unit is walking. So a stationary gunner staring
     * straight at a tank still launches along whatever bearing it happened to stop on.
     *
     * <p>The RPG merely misses when that happens. <b>The Javelin never recovers</b>: its guidance
     * only steers while the angle between its heading and the bearing to the target is under 80°
     * (and then at just 6° per tick), so a launch outside that cone trips {@code lostTarget} on
     * the first guided tick and the missile flies off in a straight line forever — a homing
     * missile that behaves as though it has no seeker at all. That is the bug this fixes, and it
     * is why "it fires but nothing hits" looked like a targeting problem rather than an aim one.
     *
     * <p>Set every tick the goal runs rather than only at the instant of firing, so the gunner
     * visibly turns to face its target during the aim delay instead of snapping round on the shot.
     * Safe to write here because a unit that is actually walking is being driven by SEM's
     * navigation, which rewrites body yaw itself on the ticks it owns.
     */
    private void faceTarget(LivingEntity target) {
        double dx = target.getX() - this.unit.getX();
        double dz = target.getZ() - this.unit.getZ();
        double dy = target.getEyeY() - this.unit.getEyeY();
        double flat = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
        float pitch = (float) (-(Mth.atan2(dy, flat) * Mth.RAD_TO_DEG));

        this.unit.setYRot(yaw);
        this.unit.yBodyRot = yaw;
        this.unit.yHeadRot = yaw;
        this.unit.setXRot(pitch);
    }

    /** The launcher in the unit's main hand, or null if it isn't holding one. */
    private GunData gun() {
        try {
            var stack = this.unit.getMainHandItem();
            return stack.getItem() instanceof GunItem ? GunData.from(stack) : null;
        } catch (Exception e) {
            return null; // unreadable gun data must never crash the AI tick
        }
    }
}
