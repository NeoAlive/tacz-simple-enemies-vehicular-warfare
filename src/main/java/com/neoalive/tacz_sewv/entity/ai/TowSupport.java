package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.data.gun.AmmoConsumer;
import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.gun.GunProp;
import com.atsuishio.superbwarfare.entity.vehicle.TowEntity;
import com.atsuishio.superbwarfare.init.ModSounds;
import com.neoalive.tacz_sewv.bridge.IIssuedAmmo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

/**
 * TOW launcher logic shared by {@link ManTowGoal} and
 * {@link com.neoalive.tacz_sewv.mixin.MixinTowTurretAim}.
 *
 * <p>Unlike a mortar, a TOW is an ordinary crewed vehicle: it has a seat, so a unit rides
 * it and the normal board flow reaches it with no special order. What it does NOT have is
 * anything that loads it — see {@link #reload} — or an aim SBW's AI can use — see
 * {@link #aimVector}.
 */
public final class TowSupport {

    /**
     * The TOW's only seat, which is also its gunner and its turret controller. The seat
     * index is all that's needed to reach the launcher's one weapon ("Missile"): the
     * single-int {@code getGunData}/{@code modifyGunData} overloads resolve a SEAT to the
     * weapon it currently has selected, which is what SBW's own fire path and
     * {@code TowEntity.interact} both use.
     */
    public static final int GUNNER_SEAT = 0;

    private TowSupport() {}

    /** Whether this unit is sitting in a TOW, and so is a launcher crew rather than infantry. */
    public static boolean isCrewing(Entity unit) {
        return unit.getVehicle() instanceof TowEntity;
    }

    /**
     * Puts a missile on the rail from the crew's own inventory, and reports whether one
     * went on.
     *
     * <p>Nothing in SBW does this for a mob. A TOW is loaded exclusively by a player
     * right-clicking it with a missile in hand ({@code TowEntity.interact}); the per-seat
     * loop in {@code VehicleEntity.tick} only ever warns a <em>Player</em> that it is out
     * of ammo, and never loads anything. An AI-crewed launcher therefore fires once — the
     * round it was deployed with — and is then dead weight forever. This is the whole
     * reason the goal exists.
     *
     * <p>The gates are SBW's own:
     * <ul>
     *   <li>{@code hasEnoughAmmoToShoot} — the magazine holds 1, so this is simply "already
     *       loaded". It is what {@code interact} reads to decide the same thing.
     *   <li>{@code getReloadCooldown()} — the launcher cycles for {@code ceil(20 / (RPM/60))}
     *       ticks after a shot (7.5 s at the TOW's RPM 8). Set by {@code vehicleShoot}, ticked
     *       down by {@code TowEntity.tick}, and NOT checked by {@code canShoot} — it gates the
     *       reload, not the shot.
     *   <li>a round to load — an issued supply ({@link IIssuedAmmo}, unlimited) or, failing
     *       that, {@code countBackupAmmo} against the crew's actual inventory. Deliberately
     *       not a hardcoded {@code MEDIUM_ANTI_GROUND_MISSILE} test: both routes ask the
     *       weapon's own AmmoConsumer whether the item fits, so they follow the datapack if a
     *       pack repoints the TOW's AmmoType.
     * </ul>
     *
     * <p>The load goes through {@code GunData.reloadAmmo} either way — {@code reloadAmmo(Entity)}
     * is byte-for-byte the call {@code TowEntity.interact} makes for a player. An issued supply
     * feeds it through {@code virtualAmmo}, SBW's own "ammo not backed by an item" channel:
     * {@code countBackupAmmo} adds it in and {@code consumeBackupAmmo} spends it first, so the
     * round is conjured and consumed entirely inside SBW's normal path and the crew's (possibly
     * nonexistent) inventory is never consulted. That indirection is the point — an RU/US unit
     * has no inventory at all, and every lookup against it silently answers 0.
     *
     * <p>{@code LOADED} is display state: {@code TowModel} reads it to put the missile on
     * the rail, and {@code getRetrieveItems} hands the round back when the launcher is
     * picked up. Nothing in the fire path reads it — which is exactly why it has to be
     * reconciled here. {@code vehicleShoot} empties the magazine but never clears LOADED;
     * SBW gets away with that because {@code interact} fixes it up the next time a player
     * touches the launcher, and for an AI crew that moment never comes. Left alone, the
     * rail would show a missile that flew 7 seconds ago.
     */
    public static boolean reload(TowEntity tow, AbstractUnit unit) {
        try {
            GunData gun = tow.getGunData(GUNNER_SEAT);
            if (gun == null) return false;

            boolean loaded = gun.hasEnoughAmmoToShoot(unit);
            if (tow.getLoaded() != loaded) tow.setLoaded(loaded);

            if (loaded) return false;
            if (tow.getReloadCooldown() != 0) return false;

            boolean issued = hasIssuedMissile(gun, unit);
            if (!issued && gun.countBackupAmmo(unit) <= 0) return false;

            tow.modifyGunData(GUNNER_SEAT, data -> {
                // Top up virtualAmmo to the magazine size rather than something large: reloadAmmo
                // takes min(needed, available) and consumeBackupAmmo spends virtualAmmo first, so
                // exactly this much is conjured and exactly this much is spent, leaving it back at
                // zero. Anything bigger would persist a stockpile into the gun's NBT.
                if (issued) data.virtualAmmo.set(data.get(GunProp.MAGAZINE));
                data.reloadAmmo(unit);
            });
            tow.setLoaded(true);

            if (tow.level() instanceof ServerLevel serverLevel) {
                serverLevel.playSound(null, tow.blockPosition(), ModSounds.TYPE_63_RELOAD.get(),
                        SoundSource.NEUTRAL, 1.0F, tow.getRandom().nextFloat() * 0.1F + 0.9F);
            }
            return true;
        } catch (Exception e) {
            return false; // unreadable gun data must never crash the AI tick
        }
    }

    /**
     * Whether this crew was issued an unlimited supply of something this launcher will take.
     *
     * <p>Asks the weapon's own AmmoConsumer rather than comparing against a hardcoded missile
     * item, so a crew issued mortar shells can't load a TOW with them, and a datapack that
     * repoints the TOW's AmmoType keeps working.
     */
    private static boolean hasIssuedMissile(GunData gun, AbstractUnit unit) {
        if (!(unit instanceof IIssuedAmmo crew)) return false;
        Item issued = crew.sewv$getIssuedAmmo();
        if (issued == null) return false;

        AmmoConsumer consumer = gun.selectedAmmoConsumer();
        return consumer != null && consumer.isAmmoItem(new ItemStack(issued));
    }

    /**
     * Where the barrel has to point to put a missile on {@code target}: straight at it.
     *
     * <p>This exists to replace the solution SBW's AI turret auto-aim would compute, which
     * is wrong for this launcher twice over. {@code turretAutoAimFromUuid} feeds
     * {@code RangeTool.calculateFiringSolution} the weapon's {@code GunProp.GRAVITY} — and
     * {@code tow.json} never sets Gravity, so the Missile inherits the schema default of
     * <b>0.05</b>. The solver duly lofts the barrel to arc a shell over the intervening
     * distance. Measured against the real solver, at the TOW's muzzle velocity of 3 and
     * against a target at the launcher's own height, the demanded elevation is:
     *
     * <pre>  10 blocks: +1.6°   30: +4.8°   50: +8.1°   75: +12.3°   96: +16.1°</pre>
     *
     * <p>But a {@code WireGuideMissileEntity} does not arc. {@code MissileProjectile.getGravity()}
     * returns <b>0</b>: it self-propels, and every tick it steers back onto the line from
     * the launcher out along {@code getBarrelVector} — it rides the beam. So it flies down
     * whatever line the barrel is on, and a lofted barrel sends it clean over the target.
     *
     * <p>Hence twice over. SBW's native AI fire gate is a hard-coded <b>4°</b> between the
     * barrel and the straight muzzle-target line, so past about 25 blocks the loft alone
     * exceeds it and the launcher simply <em>never fires</em>; inside that, and anywhere
     * {@code aiFireAssistConeDeg} (12° default) waves a shot through, the missile rides the
     * lofted beam and misses high. Untreated, an AI TOW is a 25-block weapon that misses.
     * Players never see any of this because manual aim never runs the solver: they point at
     * the target and the missile rides to it.
     *
     * <p>So we point at the target, exactly as a player does. No lead either — the barrel
     * re-aims every tick and the missile keeps riding it, so the beam tracks a moving target
     * on its own; leading it would only steer the missile to a point the target has left.
     */
    public static Vec3 aimVector(TowEntity tow, Entity controller, Entity target) {
        return target.getBoundingBox().getCenter().subtract(tow.getShootPos(controller, 1.0F));
    }
}
