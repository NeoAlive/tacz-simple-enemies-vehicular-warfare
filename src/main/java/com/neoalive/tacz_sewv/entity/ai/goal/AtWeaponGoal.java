package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.EnumSet;

import com.atsuishio.superbwarfare.data.gun.FireMode;
import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.gun.GunProp;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.support.SmallArmsSupport;
import com.neoalive.tacz_sewv.entity.ai.support.SmallArmsSupport.SbwClass;

/**
 * Works any SuperbWarfare hand weapon for a unit on foot — launchers from {@link SmallArmsSupport},
 * loadout-issued rifles/MGs/snipers, or whatever a player handed a PMC.
 *
 * <p>SuperbWarfare <em>does</em> ship a mob gun AI ({@code GunShootGoal}), and it cannot be
 * used: it takes a {@code MobGunData}, whose constructor is private and whose only factory
 * returns null unless the mob's <b>entity type</b> has an entry in the {@code sbw/mob_guns}
 * datapack — which would arm every unit of that type at spawn. So this goal is a deliberate trim
 * of that one: the sequence below (tick the gun, start a reload, start a bolt, then shoot on an
 * RPM clock) is SuperbWarfare's, not an invention.
 *
 * <p>Nothing in the fire path is player-only: {@code GunData.shoot/canShoot/tick/reloadAmmo} all
 * take a plain {@code Entity}. Handing the target's UUID straight to {@code shoot} also skips the
 * Javelin's client-side lock-on, giving an entity-guided missile (the Igla likewise).
 *
 * <p><b>Claims LOOK only.</b> It aims and it fires; it never navigates. SEM's own
 * {@code MoveToAttackRangeGoal} owns MOVE, and taking it here would root the gunner where it stood.
 */
public class AtWeaponGoal extends Goal {

    private static final int AIM_TICKS = 15;
    /** SBW's {@code GunSpawnData.SemiFireInterval} default (500 ms), in ticks. */
    private static final double SEMI_EXTRA_TICKS = 10.0;
    /** Reserve an RU/US unit is kept topped up to for a gun with no magazine (minigun, QL-1031). */
    private static final int BACKPACK_RESERVE = 256;

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** Set once SuperbWarfare's fire path has thrown — see {@link #tacz_sewv$reportBrokenFirePath}. */
    private static volatile boolean FIRE_PATH_BROKEN = false;

    private final AbstractUnit unit;
    /** Goal ticks the target has been tracked for. Reset whenever the target is lost. */
    private int aimTicks;
    /** Ticks banked toward the next shot; NaN = not firing, so the next shot goes out at once. */
    private double shotClock = Double.NaN;

    public AtWeaponGoal(AbstractUnit unit) {
        this.unit = unit;
        this.setFlags(EnumSet.of(Flag.LOOK));
    }

    /** Gated on the weapon in hand, not on faction or on how it got there. */
    @Override
    public boolean canUse() {
        if (FIRE_PATH_BROKEN) return false; // an incompatible mod broke SBW's fire path — stay down
        if (this.unit.level().isClientSide()) return false;
        if (this.unit.isPassenger()) return false; // a mounted crew works the hull's weapons

        LivingEntity target = this.unit.getTarget();
        if (target == null || !target.isAlive()) return false;
        // Defense in depth beside the setTarget veto: a target that reached the unit by a route
        // this mod does not own must still never be fired at with the wrong launcher.
        if (SmallArmsSupport.refusesTarget(this.unit, target)) return false;

        GunData gun = gun();
        if (gun == null) return false;
        if (isIssuedSupply()) return true; // topped up in tick(), never runs dry
        // Out of backup AND out of magazine: stop rather than idle on an empty gun.
        return gun.countBackupAmmo(this.unit) > 0 || gun.hasEnoughAmmoToShoot(this.unit);
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    /** Every tick: an RPM clock at half rate would halve every automatic weapon's rate of fire. */
    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        this.aimTicks = 0;
        this.shotClock = Double.NaN;
    }

    @Override
    public void stop() {
        this.aimTicks = 0;
        this.shotClock = Double.NaN;
    }

    @Override
    public void tick() {
        LivingEntity target = this.unit.getTarget();
        if (target == null) return;

        GunData gun = gun();
        if (gun == null) return;
        SbwClass cls = SmallArmsSupport.classOf(this.unit.getMainHandItem().getItem(), gun);

        this.unit.getLookControl().setLookAt(target, 30.0F, 30.0F);
        faceTarget(target);

        if (isIssuedSupply()) topUp(gun);

        // Mobs get no inventoryTick, so nothing else in the game advances this gun's reload,
        // bolt and heat timers. Without this call the gun fires once and never reloads.
        gun.tick(this.unit, true);
        if (gun.shouldStartReloading(this.unit)) gun.startReload();
        if (gun.shouldStartBolt()) gun.startBolt();

        // Line of sight is what the aim delay is actually measuring; a target behind a wall
        // resets it rather than counting down toward a shot into the wall.
        if (!this.unit.getSensing().hasLineOfSight(target)) {
            this.aimTicks = 0;
            this.shotClock = Double.NaN;
            return;
        }
        if (this.aimTicks < AIM_TICKS) {
            this.aimTicks++;
            return;
        }

        // A FIRE gate, not an approach order: SEM does the closing, this only holds fire past
        // the weapon's useful range.
        double range = rangeFor(cls);
        if (this.unit.distanceToSqr(target) > range * range || !gun.canShoot(this.unit)) {
            this.shotClock = Double.NaN;
            return;
        }

        // SBW's GunShootGoal clock, in ticks rather than ms: 1200/RPM ticks per round, plus a
        // semi-auto pause. A single-round launcher is never limited by this — canShoot is false
        // for its whole reload — so it only ever paces magazine-fed weapons.
        double cooldown = 1200.0 / Math.max(1, gun.get(GunProp.RPM));
        FireMode mode = gun.selectedFireModeInfo().mode;
        if (mode == FireMode.SEMI || (mode == FireMode.BURST && gun.burstAmount.get() == 0)) {
            cooldown += SEMI_EXTRA_TICKS;
        }
        if (Double.isNaN(this.shotClock)) this.shotClock = cooldown; // first round goes at once
        this.shotClock += 1.0;

        // zoom = true is REQUIRED, not cosmetic: JavelinItem/IglaItem.shoot return immediately
        // without it. Spread only for guns a rifleman aims by hand; guided launchers ignore it.
        double spread = (cls == SbwClass.SMALL_ARMS || cls == SbwClass.SNIPER) ? SewvConfig.SBW_AI_SPREAD.get() : 0.0;
        int shots = 0;
        while (this.shotClock >= cooldown && shots < 4 && gun.canShoot(this.unit)) {
            try {
                gun.shoot(this.unit, spread, true, target.getUUID());
            } catch (Throwable t) {
                tacz_sewv$reportBrokenFirePath(t);
                return;
            }
            this.shotClock -= cooldown;
            shots++;
        }
        if (this.shotClock > cooldown) this.shotClock = cooldown; // never bank a burst
    }

    private static double rangeFor(SbwClass cls) {
        return switch (cls) {
            case AT -> SewvConfig.AT_ENGAGE_RANGE.get();
            case AA -> SewvConfig.SBW_AA_RANGE.get();
            case SNIPER -> SewvConfig.SBW_SNIPER_RANGE.get();
            case SMALL_ARMS -> SewvConfig.SBW_SMALL_ARMS_RANGE.get();
        };
    }

    /**
     * RU/US supply is ISSUED and unlimited — the same doctrine as {@code IIssuedAmmo} for
     * mortars and TOWs. They have no inventory to carry a finite supply in; a PMC does, so a PMC
     * is left to its real pockets.
     */
    private boolean isIssuedSupply() {
        return this.unit instanceof RUunitEntity || this.unit instanceof USunitEntity;
    }

    private static void topUp(GunData gun) {
        int mag = gun.get(GunProp.MAGAZINE);
        int floor = mag > 0 ? mag * 2 : BACKPACK_RESERVE;
        if (gun.virtualAmmo.get() < floor) {
            gun.virtualAmmo.set(floor);
            gun.save();
        }
    }

    /**
     * Stand every SBW gunner down after the SuperbWarfare fire path throws.
     *
     * <p>Third-party mods inject into that path and can be flatly incompatible with the installed
     * SBW (Gunfire Overhaul 0.1.6-a reads the private {@code AmmoConsumer.type} and dies with
     * {@code IllegalAccessError} on SBW 0.8.9). An AI goal must never be able to take the server
     * down, so this latches for the session instead of rethrowing every shot. {@code Throwable},
     * not {@code Exception}: a {@code LinkageError} would sail through a narrower catch.
     */
    private static void tacz_sewv$reportBrokenFirePath(Throwable t) {
        if (FIRE_PATH_BROKEN) return;
        FIRE_PATH_BROKEN = true;
        LOGGER.error("SuperbWarfare's gun-fire path threw, so AI units holding SuperbWarfare weapons"
                + " stop firing them for this session. This is an incompatibility between SuperbWarfare"
                + " and another mod injecting into it (Gunfire Overhaul is a known case: it reads the"
                + " private AmmoConsumer.type and fails with IllegalAccessError on SBW 0.8.9), NOT a"
                + " fault in the weapon or the unit. The same crash occurs if a PLAYER fires a"
                + " SuperbWarfare gun. Update or remove that mod to restore it.", t);
    }

    /**
     * Square the unit's <b>body</b> onto the target, not just its head.
     *
     * <p>Every SuperbWarfare launch direction comes from {@code shooter.getLookAngle()}, and on a
     * Mob {@code getYRot()} is the <b>body</b> yaw, which {@code LookControl} never touches. A
     * stationary gunner staring at a tank would otherwise fire along whatever bearing it last
     * walked on — and a Javelin launched more than 80° off the target bearing loses guidance on
     * its first tick and flies straight forever.
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

    /** The usable SBW gun in the unit's main hand, or null. */
    private GunData gun() {
        return SmallArmsSupport.usableGun(this.unit.getMainHandItem());
    }
}
