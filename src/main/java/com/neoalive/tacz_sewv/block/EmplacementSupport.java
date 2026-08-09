package com.neoalive.tacz_sewv.block;

import java.util.List;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.data.gun.AmmoConsumer;
import com.atsuishio.superbwarfare.data.gun.GunData;
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
 * Live weapon-above queries and PMC ammo from an {@link EmplacementBlockEntity}.
 * Mortars restock the unit inventory; TOWs feed {@code virtualAmmo} (no inventory hop).
 */
public final class EmplacementSupport {

    private static final double WEAPON_SEARCH_HEIGHT = 3.0;

    private EmplacementSupport() {}

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
                }
            }
        }
        if (mortar != null) return mortar;
        if (tow != null) return tow;
        return fixed;
    }

    /** Emplacement pad under / beside this weapon entity. */
    @Nullable
    public static EmplacementBlockEntity findEmplacementNear(Entity weapon) {
        Level level = weapon.level();
        BlockPos origin = weapon.blockPosition();
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

        EmplacementBlockEntity be = findEmplacementNear(mortar);
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

    /**
     * Pull one TOW-compatible missile from the pad and stage it on {@code gun}'s virtualAmmo
     * so {@code reloadAmmo} can load the rail without touching the crew inventory.
     */
    public static boolean tryFeedTowVirtualAmmo(TowEntity tow, GunData gun) {
        EmplacementBlockEntity be = findEmplacementNear(tow);
        if (be == null) return false;

        AmmoConsumer consumer = gun.selectedAmmoConsumer();
        if (consumer == null) return false;

        ItemStack missile = be.extractMatching(1, consumer::isAmmoItem);
        // Caller stages virtualAmmo + reloadAmmo; the pad stack is the payment.
        return !missile.isEmpty();
    }

    private static ItemStack insertAny(IItemHandler inventory, ItemStack stack) {
        ItemStack remaining = stack;
        for (int slot = 0; slot < inventory.getSlots() && !remaining.isEmpty(); slot++) {
            remaining = inventory.insertItem(slot, remaining, false);
        }
        return remaining;
    }
}
