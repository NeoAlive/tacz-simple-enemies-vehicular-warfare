package com.neoalive.tacz_sewv.entity.ai.support;

import com.atsuishio.superbwarfare.entity.vehicle.Type63Entity;
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleVecUtils;
import com.atsuishio.superbwarfare.init.ModItems;
import com.atsuishio.superbwarfare.item.projectile.MediumRocketItem;
import com.atsuishio.superbwarfare.tools.TrajectoryCalculator;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.jetbrains.annotations.Nullable;

import com.neoalive.tacz_sewv.bridge.IIssuedAmmo;
import com.neoalive.tacz_sewv.bridge.IMortarCrew;
import com.neoalive.tacz_sewv.config.SewvConfig;

/**
 * Type-63 MLRS logic — same stand-beside claim shape as {@link MortarSupport}, different
 * aim/load/fire API ({@code TARGET_*}, twelve tubes, {@link Type63Entity#shoot}).
 */
public final class Type63Support {

    public static final String WEAPON = "Main";
    public static final int TUBE_COUNT = 12;

    private Type63Support() {}

    public static boolean isClaimed(Type63Entity launcher, @Nullable AbstractUnit except) {
        return crewOf(launcher, except) != null;
    }

    @Nullable
    public static AbstractUnit crewOf(Type63Entity launcher, @Nullable AbstractUnit except) {
        double radius = SewvConfig.BOARD_SCAN_RADIUS.get();
        for (AbstractUnit unit : launcher.level().getEntitiesOfClass(
                AbstractUnit.class, launcher.getBoundingBox().inflate(radius))) {
            if (unit == except || !unit.isAlive()) continue;
            if (unit instanceof IMortarCrew crew && crew.sewv$getMortarTargetId() == launcher.getId()) {
                return unit;
            }
        }
        return null;
    }

    /** True while this unit holds any seatless emplacement claim (mortar or Type-63). */
    public static boolean hasClaim(AbstractUnit unit) {
        return ((IMortarCrew) unit).sewv$getMortarTargetId() != IMortarCrew.NO_MORTAR;
    }

    public static void claim(AbstractUnit unit, Type63Entity launcher) {
        ((IMortarCrew) unit).sewv$setMortarTargetId(launcher.getId());
    }

    public static void releaseClaim(Entity unit) {
        MortarSupport.releaseClaim(unit);
    }

    /**
     * Hull bearing + turret pitch for {@code aimPos}, or null if out of envelope.
     * Rotates the hull to the target bearing and centres turret yaw (±15° arc).
     */
    @Nullable
    public static float[] solveAim(Type63Entity launcher, Vec3 aimPos) {
        Vec3 start = launcher.getShootPos(1.0F);
        Vec3 target = aimPos.add(0.0, -1.0, 0.0);
        double velocity = launcher.getProjectileVelocity(WEAPON);
        double gravity = launcher.getProjectileGravity(WEAPON);
        Vec3 launch = TrajectoryCalculator.calculateLaunchVector(start, target, velocity, gravity, false);
        if (launch == null) {
            launch = TrajectoryCalculator.calculateLaunchVector(start, target, velocity, gravity, true);
        }
        if (launch == null) return null;

        float pitch = (float) VehicleVecUtils.getXRotFromVector(launch);
        // Matches Type63InfoOverlay — not the mortar-style turretMin/Max accessors.
        if (pitch < -5.0F || pitch > 60.0F) {
            return null;
        }

        float bearing = bearingTo(launcher, aimPos);
        // Cancel any pending hoe body slew so setYRot is not fought on the next baseTick.
        launcher.getEntityData().set(Type63Entity.BODY_YAW, 0.0F);
        launcher.setYRot(bearing);
        return new float[]{0.0F, pitch};
    }

    /**
     * Writes demand angles and snaps the turret there. {@code travel()} only closes
     * 10% of the gap per tick — fine for a player, too slow for AI on a moving target.
     * {@link #aimSettled} reads {@code turretXRot/turretYRot}, the same fields
     * {@code travel()} slews; do not compare {@code SHOOT_*} (barrel-vector space).
     */
    public static void aimAt(Type63Entity launcher, float turretYaw, float pitch) {
        launcher.getEntityData().set(Type63Entity.TARGET_YAW, turretYaw);
        launcher.getEntityData().set(Type63Entity.TARGET_PITCH, pitch);
        launcher.setTurretYRot(turretYaw);
        launcher.setTurretXRot(pitch);
    }

    public static void stabilizeClaimed(Type63Entity launcher) {
        Vec3 motion = launcher.getDeltaMovement();
        double horiz = motion.horizontalDistanceSqr();
        if (horiz > 1.0e-6 || (!launcher.onGround() && Math.abs(motion.y) > 0.05)) {
            launcher.setDeltaMovement(0.0, Math.min(0.0, motion.y), 0.0);
            launcher.hurtMarked = true;
        }
        if (!launcher.onGround()) {
            launcher.resetFallDistance();
        }
    }

    public static Vec3 scatter(Vec3 target, RandomSource random) {
        return MortarSupport.scatter(target, random);
    }

    public static boolean aimSettled(Type63Entity launcher, float toleranceDeg) {
        float pitchError = Mth.degreesDifferenceAbs(
                launcher.getTurretXRot(),
                launcher.getEntityData().get(Type63Entity.TARGET_PITCH));
        float yawError = Mth.degreesDifferenceAbs(
                launcher.getTurretYRot(),
                launcher.getEntityData().get(Type63Entity.TARGET_YAW));
        return pitchError <= toleranceDeg && yawError <= toleranceDeg;
    }

    public static boolean onFireCooldown(Type63Entity launcher) {
        return launcher.getCooldown() > 0;
    }

    /** Index of the first loaded tube, or {@code -1}. */
    public static int nextLoadedTube(Type63Entity launcher) {
        for (int i = 0; i < TUBE_COUNT && i < launcher.getContainerSize(); i++) {
            if (launcher.getItems().get(i).getItem() instanceof MediumRocketItem) {
                return i;
            }
        }
        return -1;
    }

    /** Load every empty tube from the crew's pockets / issued supply. Returns tubes filled. */
    public static int loadAllEmptyTubes(Type63Entity launcher, AbstractUnit unit) {
        int loaded = 0;
        for (int i = 0; i < TUBE_COUNT && i < launcher.getContainerSize(); i++) {
            if (!launcher.getItems().get(i).isEmpty()) continue;
            ItemStack rocket = takeRocket(unit);
            if (rocket.isEmpty()) break;
            launcher.getItems().set(i, rocket.copyWithCount(1));
            loaded++;
        }
        if (loaded > 0) {
            launcher.setChanged();
        }
        return loaded;
    }

    public static ItemStack takeRocket(AbstractUnit unit) {
        if (!SewvConfig.MORTAR_REQUIRES_AMMO.get()) {
            return new ItemStack(ModItems.MEDIUM_ROCKET_HE.get());
        }

        if (unit instanceof IIssuedAmmo crew) {
            Item issued = crew.sewv$getIssuedAmmo();
            if (issued instanceof MediumRocketItem) {
                return new ItemStack(issued);
            }
        }

        IItemHandler inventory = unit.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null);
        if (inventory == null) return ItemStack.EMPTY;

        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            if (inventory.getStackInSlot(slot).getItem() instanceof MediumRocketItem) {
                return inventory.extractItem(slot, 1, false);
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * Fire one tube if the launcher cooldown allows. Clears the tube after a successful shot,
     * matching the player interact path.
     */
    public static boolean fireTube(Type63Entity launcher, @Nullable Player shooter, int tube) {
        if (tube < 0 || tube >= launcher.getContainerSize()) return false;
        if (!(launcher.getItems().get(tube).getItem() instanceof MediumRocketItem)) return false;
        if (launcher.getCooldown() > 0) return false;

        launcher.shoot(shooter, tube);
        launcher.getItems().set(tube, ItemStack.EMPTY);
        launcher.setChanged();
        return true;
    }

    private static float bearingTo(Type63Entity launcher, Vec3 aimPos) {
        Vec3 eye = launcher.getEyePosition();
        return Mth.wrapDegrees(
                (float) (Mth.atan2(aimPos.z - eye.z, aimPos.x - eye.x) * (180.0 / Math.PI)) - 90.0F);
    }
}
