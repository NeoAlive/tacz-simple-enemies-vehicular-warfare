package com.neoalive.tacz_sewv.block;

import java.util.List;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType;
import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
import com.atsuishio.superbwarfare.entity.vehicle.TowEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.item.projectile.MortarShellItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

/**
 * Live weapon-above queries and PMC ammo transfer from an {@link EmplacementBlockEntity}.
 * No entity-id cache — mortars move / despawn without notifying the pad.
 */
public final class EmplacementSupport {

    /** How far above the pad to look for a mount (blocks). */
    private static final double WEAPON_SEARCH_HEIGHT = 3.0;

    private EmplacementSupport() {}

    /**
     * Scan entities above the emplacement's up face. Prefer mortar (seatless) before generic
     * {@link VehicleEntity}, then TOW, then any {@code EngineType.FIXED} mount.
     */
    @Nullable
    public static VehicleEntity findWeaponAbove(Level level, BlockPos emplacementPos) {
        AABB box = new AABB(emplacementPos.above())
                .expandTowards(0.0, WEAPON_SEARCH_HEIGHT - 1.0, 0.0)
                .inflate(0.35);
        List<Entity> found = level.getEntities((Entity) null, box, e -> true);

        MortarEntity mortar = null;
        TowEntity tow = null;
        VehicleEntity fixed = null;
        for (Entity entity : found) {
            if (entity instanceof MortarEntity m) {
                if (mortar == null) mortar = m;
            } else if (entity instanceof TowEntity t) {
                if (tow == null) tow = t;
            } else if (entity instanceof VehicleEntity v && fixed == null) {
                try {
                    if (v.computed().getEngineType() == EngineType.FIXED) {
                        fixed = v;
                    }
                } catch (RuntimeException ignored) {
                    // datapack / compute failure — skip
                }
            }
        }
        if (mortar != null) return mortar;
        if (tow != null) return tow;
        return fixed;
    }

    /** Emplacement pad under / beside this mortar, if any. */
    @Nullable
    public static EmplacementBlockEntity findEmplacementForMortar(MortarEntity mortar) {
        Level level = mortar.level();
        BlockPos origin = mortar.blockPosition();
        for (int dy = 0; dy <= 2; dy++) {
            BlockPos below = origin.below(dy);
            if (level.getBlockEntity(below) instanceof EmplacementBlockEntity be) {
                return be;
            }
        }
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos beside = origin.relative(dir);
            if (level.getBlockEntity(beside) instanceof EmplacementBlockEntity be) {
                return be;
            }
            if (level.getBlockEntity(beside.below()) instanceof EmplacementBlockEntity be) {
                return be;
            }
        }
        return null;
    }

    /**
     * Transfer one mortar shell from a nearby emplacement into the unit's inventory.
     * Returns true only when a shell was inserted so {@code takeShell} can succeed next.
     */
    public static boolean tryRestockShell(AbstractUnit unit, MortarEntity mortar) {
        IItemHandler inventory = unit.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null);
        if (inventory == null) return false;

        EmplacementBlockEntity be = findEmplacementForMortar(mortar);
        if (be == null) return false;

        ItemStack shell = be.extractShell(1);
        if (shell.isEmpty() || !(shell.getItem() instanceof MortarShellItem)) {
            return false;
        }

        int original = shell.getCount();
        ItemStack leftover = insertAny(inventory, shell);
        if (!leftover.isEmpty()) {
            be.insertOrDrop(leftover);
            return leftover.getCount() < original;
        }
        return true;
    }

    private static ItemStack insertAny(IItemHandler inventory, ItemStack stack) {
        ItemStack remaining = stack;
        for (int slot = 0; slot < inventory.getSlots() && !remaining.isEmpty(); slot++) {
            remaining = inventory.insertItem(slot, remaining, false);
        }
        return remaining;
    }
}
