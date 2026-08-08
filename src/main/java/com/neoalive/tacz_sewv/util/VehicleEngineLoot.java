package com.neoalive.tacz_sewv.util;

import java.util.ArrayList;
import java.util.List;

import com.atsuishio.superbwarfare.data.gun.AmmoConsumer;
import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.gun.GunProp;
import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType;
import com.atsuishio.superbwarfare.data.vehicle.subdata.SeatInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.init.ModItems;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.config.SewvConfig;

/**
 * Fills an NPC hull's container when it unlocks (no RU/US crew left). Rolls EngineType datapack
 * loot tables into inventory slots; optionally concatenates finite ammo (never drops to the ground).
 * After fill, slots are scrambled so contents look scavenged rather than neatly packed.
 */
public final class VehicleEngineLoot {

    public static final String TAG_PENDING = "sewv:loot_pending";
    public static final String TAG_APPLIED = "sewv:loot_applied";

    private VehicleEngineLoot() {
    }

    /** RU/US crewed spawns — unlock will fill inventory once the enemy crew is gone. */
    public static void markPending(VehicleEntity hull) {
        if (hull.level().isClientSide) return;
        if (hull.getPersistentData().getBoolean(TAG_APPLIED)) return;
        hull.getPersistentData().putBoolean(TAG_PENDING, true);
    }

    public static boolean isLockedByEnemyCrew(VehicleEntity hull) {
        for (Entity passenger : hull.getPassengers()) {
            if (passenger instanceof RUunitEntity || passenger instanceof USunitEntity) {
                return true;
            }
        }
        return false;
    }

    /**
     * If pending and unlocked, roll EngineType loot into empty slots and optionally ammo-concat.
     * Idempotent via {@link #TAG_APPLIED}.
     */
    public static void tryApplyOnUnlock(VehicleEntity hull) {
        if (!(hull.level() instanceof ServerLevel level)) return;
        if (hull.getPersistentData().getBoolean(TAG_APPLIED)) return;
        if (!hull.getPersistentData().getBoolean(TAG_PENDING)) return;
        if (isLockedByEnemyCrew(hull)) return;

        if (!hull.hasContainer() || hull.getContainerSize() <= 0) {
            finish(hull);
            return;
        }

        String mode = SewvConfig.VEHICLE_DEATH_DROPS.get();
        if (!"disable".equals(mode)) {
            insertEngineLoot(hull, level);
            if (SewvConfig.VEHICLE_AMMO_LOOT.get()) {
                concatAmmo(hull, level.getRandom());
            }
            scrambleInventory(hull, level.getRandom());
        }
        finish(hull);
    }

    private static void finish(VehicleEntity hull) {
        hull.getPersistentData().remove(TAG_PENDING);
        hull.getPersistentData().putBoolean(TAG_APPLIED, true);
    }

    /** Fisher–Yates shuffle so loot/ammo are not packed into the first slots. */
    private static void scrambleInventory(VehicleEntity hull, RandomSource random) {
        List<ItemStack> items = hull.getItems();
        int n = items.size();
        if (n < 2) return;
        for (int i = n - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            ItemStack a = items.get(i);
            items.set(i, items.get(j));
            items.set(j, a);
        }
    }

    private static void insertEngineLoot(VehicleEntity hull, ServerLevel level) {
        EngineType engine;
        try {
            engine = hull.computed().getEngineType();
        } catch (Exception e) {
            return;
        }
        if (engine == null) return;

        ResourceLocation tableId = new ResourceLocation(TaczSewv.MODID,
                "vehicles/engine/" + engine.name().toLowerCase());
        LootTable table = level.getServer().getLootData().getLootTable(tableId);
        if (table == LootTable.EMPTY) return;

        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, hull.position())
                .create(LootContextParamSets.CHEST);

        for (ItemStack stack : table.getRandomItems(params)) {
            if (stack.isEmpty() || isCreativeAmmoBox(stack)) continue;
            insertStack(hull, stack);
        }
    }

    private static void concatAmmo(VehicleEntity hull, RandomSource random) {
        List<ItemStack> items = hull.getItems();
        boolean hadCreative = false;
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (isCreativeAmmoBox(stack)) {
                items.set(i, ItemStack.EMPTY);
                hadCreative = true;
            }
        }

        if (!hadCreative) {
            // Finite combat ammo stays; only add a light bonus stack per ammo type into free slots.
            List<Item> ammo = resolveAmmoItems(hull);
            for (Item item : ammo) {
                int count = bonusCount(item, random);
                if (count > 0) insertStack(hull, new ItemStack(item, count));
            }
            return;
        }

        if (!SewvConfig.CREATIVE_AMMO_FALLBACK.get()) return;

        List<Item> ammo = resolveAmmoItems(hull);
        if (ammo.isEmpty()) return;
        for (Item item : ammo) {
            int count = synthesizeCount(item, random);
            if (count > 0) insertStack(hull, new ItemStack(item, count));
        }
    }

    /** ~10–25% of a stack / magazine — sporadic player loot when replacing a creative box. */
    private static int synthesizeCount(Item item, RandomSource random) {
        int max = Math.max(1, item.getMaxStackSize());
        int lo = Math.max(1, max / 10);
        int hi = Math.max(lo, max / 4);
        return Mth.nextInt(random, lo, hi);
    }

    /** Smaller bonus when finite ammo was already present. */
    private static int bonusCount(Item item, RandomSource random) {
        int max = Math.max(1, item.getMaxStackSize());
        int hi = Math.max(1, max / 8);
        return Mth.nextInt(random, 1, hi);
    }

    private static void insertStack(VehicleEntity hull, ItemStack stack) {
        if (stack.isEmpty()) return;
        List<ItemStack> items = hull.getItems();
        // Merge into existing matching stacks first.
        for (int i = 0; i < items.size() && !stack.isEmpty(); i++) {
            ItemStack slot = items.get(i);
            if (slot.isEmpty() || !ItemStack.isSameItemSameTags(slot, stack)) continue;
            int space = slot.getMaxStackSize() - slot.getCount();
            if (space <= 0) continue;
            int move = Math.min(space, stack.getCount());
            slot.grow(move);
            stack.shrink(move);
        }
        for (int i = 0; i < items.size() && !stack.isEmpty(); i++) {
            if (!items.get(i).isEmpty()) continue;
            int put = Math.min(stack.getCount(), stack.getMaxStackSize());
            items.set(i, stack.split(put));
        }
    }

    private static boolean isCreativeAmmoBox(ItemStack stack) {
        return stack.is(ModItems.CREATIVE_AMMO_BOX.get());
    }

    private static List<Item> resolveAmmoItems(VehicleEntity tank) {
        List<Item> ammo = new ArrayList<>();
        int seats = Math.max(1, tank.getMaxPassengers());
        for (int seat = 0; seat < seats; seat++) {
            SeatInfo info = tank.getSeat(seat);
            int weapons = info == null ? 0 : info.weapons().size();
            for (int w = 0; w < weapons; w++) {
                try {
                    GunData gun = tank.getGunData(seat, w);
                    if (gun == null) continue;
                    List<AmmoConsumer> consumers = gun.get(GunProp.AMMO_CONSUMER);
                    if (consumers == null) continue;
                    for (AmmoConsumer c : consumers) {
                        if (c == null) continue;
                        ItemStack stack = c.stack();
                        if (!stack.isEmpty() && !ammo.contains(stack.getItem())) {
                            ammo.add(stack.getItem());
                        }
                    }
                } catch (Exception ignored) {
                    // exotic weapon data — skip
                }
            }
        }
        return ammo;
    }
}
