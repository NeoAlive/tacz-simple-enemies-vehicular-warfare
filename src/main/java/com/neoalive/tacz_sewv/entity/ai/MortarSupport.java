package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleVecUtils;
import com.atsuishio.superbwarfare.init.ModItems;
import com.atsuishio.superbwarfare.item.projectile.MortarShellItem;
import com.atsuishio.superbwarfare.tools.TrajectoryCalculator;
import com.neoalive.tacz_sewv.bridge.IIssuedAmmo;
import com.neoalive.tacz_sewv.bridge.IMortarCrew;
import com.neoalive.tacz_sewv.config.SewvConfig;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Mortar logic shared by {@link ManMortarGoal} and
 * {@link com.neoalive.tacz_sewv.network.PacketManMortar}.
 *
 * <p>Designating a target for a crew is not here — that is shared with the TOW and lives
 * in {@link FireMissionSupport}.
 */
public final class MortarSupport {

    public static final String WEAPON = "Main";

    /** A mortar's container is one slot: the tube. */
    public static final int TUBE_SLOT = 0;

    private MortarSupport() {}

    /**
     * Whether another unit already holds this mortar. A MortarEntity has no owner
     * field, so occupancy is read back off the units: the claim exists only as a unit
     * pointing at a mortar, which makes it self-healing — a crew that dies or unloads
     * stops pointing and releases it.
     *
     * <p>Scans, so it belongs in order handling (once per keypress), never in canUse.
     */
    public static boolean isMortarClaimed(MortarEntity mortar, @Nullable AbstractUnit except) {
        return crewOf(mortar, except) != null;
    }

    /**
     * The unit working this mortar, or null if nobody is. Same scan as
     * {@link #isMortarClaimed(MortarEntity, AbstractUnit)} — which is written in terms of this —
     * for callers that need the crew itself and not just whether there is one: a mortar has no
     * seats, so its crew is the only place to read a faction, an owner or an entity to order.
     */
    @Nullable
    public static AbstractUnit crewOf(MortarEntity mortar, @Nullable AbstractUnit except) {
        double radius = SewvConfig.BOARD_SCAN_RADIUS.get();
        // AbstractUnit, not PmcUnitEntity: RU/US crews man mortars too, and a tube one of
        // them is working must not read as free just because the asker is a PMC.
        //
        // instanceof rather than a cast: this scans whatever AbstractUnits are nearby, and
        // an addon's own unit type would not carry our bridge interface.
        for (AbstractUnit unit : mortar.level().getEntitiesOfClass(
                AbstractUnit.class, mortar.getBoundingBox().inflate(radius))) {
            if (unit == except || !unit.isAlive()) continue;
            if (unit instanceof IMortarCrew crew && crew.sewv$getMortarTargetId() == mortar.getId()) return unit;
        }
        return null;
    }

    public static boolean hasMortarClaim(AbstractUnit unit) {
        return ((IMortarCrew) unit).sewv$getMortarTargetId() != IMortarCrew.NO_MORTAR;
    }

    public static void claim(AbstractUnit unit, MortarEntity mortar) {
        ((IMortarCrew) unit).sewv$setMortarTargetId(mortar.getId());
    }

    public static void releaseClaim(Entity unit) {
        if (unit instanceof IMortarCrew crew) crew.sewv$setMortarTargetId(IMortarCrew.NO_MORTAR);
    }

    /**
     * The turret angles that put a shell on {@code aimPos}, as {@code {yaw, pitch}} in
     * the mortar's own rotation space, or null if it can't reach.
     *
     * <p>Deliberately does not go through {@code MortarEntity.setTarget}: that returns
     * void and silently leaves the barrel alone when it can't solve, re-rolls its own
     * scatter on every call, and reaches the arc it wants through two layers of
     * inverted naming. Reading the solver directly and writing the angles ourselves is
     * the same path SBW's own player controls use, and it can be checked.
     */
    @Nullable
    public static float[] solveAim(MortarEntity mortar, Vec3 aimPos) {
        // Solutions come back sorted by descending flight time, so the steep lob is
        // first — that's the mortar arc, and the shallow one is under the turret's
        // elevation floor at all but extreme range. Taking the first that fits avoids
        // having to name either.
        List<Vec3> solutions = TrajectoryCalculator.INSTANCE.calculateShootVectors(
                mortar.getEyePosition(), aimPos.add(0.0, -1.0, 0.0),
                mortar.getProjectileVelocity(WEAPON), mortar.getProjectileGravity(WEAPON));

        for (Vec3 launch : solutions) {
            // getXRotFromVector is atan2(y, horizontal): positive is up, the opposite of
            // vanilla xRot. Negating puts it in the same space as TurretPitchRange.
            float pitch = (float) -VehicleVecUtils.getXRotFromVector(launch);
            if (pitch < -mortar.getTurretMaxPitch() || pitch > -mortar.getTurretMinPitch()) continue;
            return new float[]{bearingTo(mortar, aimPos), pitch};
        }
        return null; // inside minimum range, beyond maximum, or no arc clears the stops
    }

    /**
     * Lays the barrel. travel() slews the mortar onto these every tick, and the shell
     * launches along the resulting getLookAngle().
     */
    public static void aimAt(MortarEntity mortar, float yaw, float pitch) {
        mortar.getEntityData().set(MortarEntity.TARGET_YAW, yaw);
        mortar.getEntityData().set(MortarEntity.TARGET_PITCH, pitch);
    }

    /** A random point within the dispersion radius of {@code target}, on its own level. */
    public static Vec3 scatter(Vec3 target, RandomSource random) {
        int radius = SewvConfig.MORTAR_DISPERSION_RADIUS.get();
        if (radius <= 0) return target;
        double bearing = random.nextDouble() * Math.PI * 2.0;
        double distance = random.nextDouble() * radius;
        return new Vec3(
                target.x + Math.cos(bearing) * distance,
                target.y,
                target.z + Math.sin(bearing) * distance);
    }

    /** Whether the barrel has finished slewing onto what it was last asked to point at. */
    public static boolean aimSettled(MortarEntity mortar, float toleranceDeg) {
        float yawError = Mth.degreesDifferenceAbs(
                mortar.getYRot(), mortar.getEntityData().get(MortarEntity.TARGET_YAW));
        float pitchError = Mth.degreesDifferenceAbs(
                mortar.getXRot(), mortar.getEntityData().get(MortarEntity.TARGET_PITCH));
        return yawError <= toleranceDeg && pitchError <= toleranceDeg;
    }

    /**
     * Pulls one shell for a shot, or an empty stack if the crew is out. With
     * mortarRequiresAmmo off this conjures one instead of touching the inventory.
     */
    public static ItemStack takeShell(AbstractUnit unit) {
        if (!SewvConfig.MORTAR_REQUIRES_AMMO.get()) {
            return new ItemStack(ModItems.MORTAR_SHELL.get());
        }

        // An issued supply is unlimited and needs no inventory at all — which is the only
        // way an RU/US crew can shoot anything, since those unit types have none. Checked
        // before the scan because a crew with one has nothing to scan.
        //
        // The instanceof is not a formality: it keeps a crew issued some other mod's ammo
        // from conjuring it into a tube that would refuse it, and it accepts MORTAR_SHELL_WP
        // (a plain MortarShellItem, like the plain shell) and POTION_MORTAR_SHELL
        // (PotionMortarShellItem extends it) with no extra cases.
        if (unit instanceof IIssuedAmmo crew) {
            Item issued = crew.sewv$getIssuedAmmo();
            if (issued instanceof MortarShellItem) return new ItemStack(issued);
        }

        IItemHandler inventory = unit.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null);
        if (inventory == null) return ItemStack.EMPTY;

        // Every slot, equipment included: slots 0-5 are the unit's hands and armour, but
        // only shells match, so a gun in the main hand is never at risk and shells
        // stashed in a hand still get used.
        //
        // Test the item, NOT mortar.canPlaceItem: that is the container-insertion gate,
        // and it compares the WHOLE incoming stack's count against the mortar's max
        // stack size of 1 — so it rejects any stack of more than one shell, which is
        // every realistic case. SBW's own player load path doesn't use it either; it
        // checks the item and then inserts a single-count copy, as we do.
        // PotionMortarShellItem extends MortarShellItem, so this covers every variant —
        // the same test vehicleShoot and baseTick use on the tube.
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            if (inventory.getStackInSlot(slot).getItem() instanceof MortarShellItem) {
                return inventory.extractItem(slot, 1, false);
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * MortarEntity.look()'s bearing formula — the yaw space travel() and getLookAngle()
     * both work in.
     */
    private static float bearingTo(MortarEntity mortar, Vec3 aimPos) {
        Vec3 eye = mortar.getEyePosition();
        return Mth.wrapDegrees(
                (float) (Mth.atan2(aimPos.z - eye.z, aimPos.x - eye.x) * (180.0 / Math.PI)) - 90.0F);
    }
}
