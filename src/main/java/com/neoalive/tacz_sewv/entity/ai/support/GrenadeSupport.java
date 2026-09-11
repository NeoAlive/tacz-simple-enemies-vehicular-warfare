package com.neoalive.tacz_sewv.entity.ai.support;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.projectile.HandGrenadeEntity;
import com.atsuishio.superbwarfare.entity.projectile.M18SmokeGrenadeEntity;
import com.atsuishio.superbwarfare.entity.projectile.RgoGrenadeEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.init.ModItems;
import com.atsuishio.superbwarfare.item.HandGrenade;
import com.atsuishio.superbwarfare.item.RgoGrenade;
import com.atsuishio.superbwarfare.item.projectile.M18SmokeGrenadeItem;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;
import com.neoalive.tacz_sewv.util.WorldVehiclePools;
import com.neoalive.tacz_sewv.util.WorldVehiclePools.Category;

/**
 * On-foot grenade throws for RU/US/PMC. SBW's item {@code releaseUsing} is Player-gated, so AI
 * spawns the projectile entities directly.
 *
 * <p><b>Pick rule:</b> mounted target → M18 then RGO; infantry/monster → hand grenade, then RGO,
 * then M18. RU/US always may spend any id in their faction GRENADE pool; PMC must have the item
 * in inventory.
 */
public final class GrenadeSupport {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String TAG_COOLDOWN_UNTIL = "sewv:grenade_cd";
    public static final String TAG_STASH_PRESENT = "sewv:grenade_stash";
    public static final String TAG_STASH_MAIN = "sewv:grenade_stash_main";
    public static final String TAG_STASH_OFF = "sewv:grenade_stash_off";
    /** Cached PMC inventory probe (game time + bitset of known kinds). */
    private static final String TAG_INV_SCAN_AT = "sewv:grenade_inv_at";
    private static final String TAG_INV_SCAN_MASK = "sewv:grenade_inv_mask";

    private static final int INV_SCAN_TTL = 20;

    public static final String ID_HAND = "superbwarfare:hand_grenade";
    public static final String ID_RGO = "superbwarfare:rgo_grenade";
    public static final String ID_M18 = "superbwarfare:m18_smoke_grenade";

    private static final String[] MOUNTED_PREF = { ID_M18, ID_RGO };
    private static final String[] INFANTRY_PREF = { ID_HAND, ID_RGO, ID_M18 };

    private static final Set<String> WARNED_UNKNOWN = new HashSet<>();

    /** Mid-hold player throw: ~half cook, power ~1.2. */
    private static final float THROW_POWER = 1.2f;
    private static final int HAND_FUSE = 50;
    private static final int RGO_FUSE = 40;
    private static final int M18_FUSE = 40;

    private GrenadeSupport() {}

    public enum Cadence {
        // HE needs to land near the target; smoke only screens LOS, so it may be lobbed
        // from farther with no minimum standoff.
        DEFAULT(8.0, 24.0, 0.0, 40.0, 100),
        RARE(0.0, 16.0, 0.0, 32.0, 240),
        AGGRESSIVE(0.0, 30.0, 0.0, 48.0, 50);

        public final double heMinRange;
        public final double heMaxRange;
        public final double smokeMinRange;
        public final double smokeMaxRange;
        /** Game ticks between throws. */
        public final int cooldownTicks;

        Cadence(double heMinRange, double heMaxRange, double smokeMinRange, double smokeMaxRange,
                int cooldownTicks) {
            this.heMinRange = heMinRange;
            this.heMaxRange = heMaxRange;
            this.smokeMinRange = smokeMinRange;
            this.smokeMaxRange = smokeMaxRange;
            this.cooldownTicks = cooldownTicks;
        }

        public static Cadence fromConfig() {
            String raw = SewvConfig.GRENADE_THROW_CADENCE.get();
            if (raw == null) return DEFAULT;
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "rare" -> RARE;
                case "aggressive" -> AGGRESSIVE;
                default -> DEFAULT;
            };
        }
    }

    public static boolean isGrenadeItem(Item item) {
        return item instanceof HandGrenade
                || item instanceof RgoGrenade
                || item instanceof M18SmokeGrenadeItem;
    }

    public static boolean isGrenadeItem(ItemStack stack) {
        return !stack.isEmpty() && isGrenadeItem(stack.getItem());
    }

    @Nullable
    public static TankFaction factionOf(AbstractUnit unit) {
        if (unit instanceof RUunitEntity) return TankFaction.RU;
        if (unit instanceof USunitEntity) return TankFaction.US;
        if (unit instanceof PmcUnitEntity) return TankFaction.PMC;
        return null;
    }

    public static boolean onCooldown(AbstractUnit unit) {
        long until = unit.getPersistentData().getLong(TAG_COOLDOWN_UNTIL);
        return unit.level().getGameTime() < until;
    }

    public static void armCooldown(AbstractUnit unit) {
        Cadence c = Cadence.fromConfig();
        unit.getPersistentData().putLong(TAG_COOLDOWN_UNTIL,
                unit.level().getGameTime() + c.cooldownTicks);
    }

    /**
     * Range gate for a specific grenade id. M18 smoke uses a longer band with no close-in
     * requirement — it screens LOS, it does not need to land on the hull. HE keeps the
     * tighter cadence envelope.
     */
    public static boolean inThrowRange(AbstractUnit unit, LivingEntity target, String id) {
        Cadence c = Cadence.fromConfig();
        double dSq = unit.distanceToSqr(target);
        boolean smoke = ID_M18.equals(id);
        double min = smoke ? c.smokeMinRange : c.heMinRange;
        double max = smoke ? c.smokeMaxRange : c.heMaxRange;
        if (dSq < min * min) return false;
        return dSq <= max * max;
    }

    public static boolean targetMounted(LivingEntity target) {
        return target.getVehicle() instanceof VehicleEntity;
    }

    /**
     * Preferred grenade id for this target that the unit can currently spend, or null.
     */
    @Nullable
    public static String pick(AbstractUnit unit, LivingEntity target) {
        TankFaction faction = factionOf(unit);
        if (faction == null) return null;

        Set<String> available = availableIds(unit, faction);
        if (available.isEmpty()) return null;

        String[] order = targetMounted(target) ? MOUNTED_PREF : INFANTRY_PREF;
        for (String id : order) {
            if (available.contains(id)) return id;
        }
        return null;
    }

    /** Pool ∩ spendable, only known throwable mappings. */
    public static Set<String> availableIds(AbstractUnit unit, TankFaction faction) {
        List<String> pool = WorldVehiclePools.get(unit.level()).list(faction, Category.GRENADE);
        Set<String> out = new HashSet<>();
        boolean pmc = unit instanceof PmcUnitEntity;
        int invMask = pmc ? pmcInventoryMask(unit) : -1;

        for (String raw : pool) {
            String id = normalizeKnown(raw);
            if (id == null) continue;
            if (pmc && !maskHas(invMask, id)) continue;
            out.add(id);
        }
        return out;
    }

    @Nullable
    private static String normalizeKnown(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String id = raw.trim().toLowerCase(Locale.ROOT);
        if (ID_HAND.equals(id) || ID_RGO.equals(id) || ID_M18.equals(id)) return id;
        if (WARNED_UNKNOWN.add(id)) {
            LOGGER.warn("[sewv] Grenade pool entry '{}' is not a supported throwable; ignored.", raw);
        }
        return null;
    }

    private static int pmcInventoryMask(AbstractUnit unit) {
        var data = unit.getPersistentData();
        long now = unit.level().getGameTime();
        if (data.contains(TAG_INV_SCAN_AT) && now - data.getLong(TAG_INV_SCAN_AT) < INV_SCAN_TTL) {
            return data.getInt(TAG_INV_SCAN_MASK);
        }
        int mask = scanInventoryMask(unit);
        data.putLong(TAG_INV_SCAN_AT, now);
        data.putInt(TAG_INV_SCAN_MASK, mask);
        return mask;
    }

    private static void invalidateInventoryCache(AbstractUnit unit) {
        unit.getPersistentData().remove(TAG_INV_SCAN_AT);
        unit.getPersistentData().remove(TAG_INV_SCAN_MASK);
    }

    private static int scanInventoryMask(AbstractUnit unit) {
        IItemHandler inv = unit.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null);
        if (inv == null) return 0;
        int mask = 0;
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack.isEmpty()) continue;
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (key == null) continue;
            String id = key.toString();
            if (ID_HAND.equals(id)) mask |= 1;
            else if (ID_RGO.equals(id)) mask |= 2;
            else if (ID_M18.equals(id)) mask |= 4;
        }
        return mask;
    }

    private static boolean maskHas(int mask, String id) {
        if (mask < 0) return true;
        return switch (id) {
            case ID_HAND -> (mask & 1) != 0;
            case ID_RGO -> (mask & 2) != 0;
            case ID_M18 -> (mask & 4) != 0;
            default -> false;
        };
    }

    @Nullable
    public static Item itemFor(String id) {
        if (ID_HAND.equals(id)) return ModItems.HAND_GRENADE.get();
        if (ID_RGO.equals(id)) return ModItems.RGO_GRENADE.get();
        if (ID_M18.equals(id)) return ModItems.M18_SMOKE_GRENADE.get();
        return null;
    }

    /**
     * Pull one grenade for the throw hand. PMC extracts from inventory; RU/US conjure a copy.
     * Returns empty if unavailable.
     */
    public static ItemStack takeForThrow(AbstractUnit unit, String id) {
        Item item = itemFor(id);
        if (item == null) return ItemStack.EMPTY;

        if (!(unit instanceof PmcUnitEntity)) {
            return new ItemStack(item, 1);
        }

        IItemHandler inv = unit.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null);
        if (inv == null) return ItemStack.EMPTY;
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack.isEmpty() || stack.getItem() != item) continue;
            ItemStack extracted = inv.extractItem(i, 1, false);
            invalidateInventoryCache(unit);
            return extracted;
        }
        invalidateInventoryCache(unit);
        return ItemStack.EMPTY;
    }

    /** Spawn and launch. Caller owns aiming and holster restore. */
    public static boolean throwGrenade(AbstractUnit unit, String id) {
        Level level = unit.level();
        if (level.isClientSide()) return false;

        if (ID_HAND.equals(id)) {
            HandGrenadeEntity grenade = new HandGrenadeEntity(unit, level);
            grenade.setLife(HAND_FUSE);
            grenade.shootFromRotation(unit, unit.getXRot(), unit.getYRot(), 0.0f, THROW_POWER, 0.0f);
            level.addFreshEntity(grenade);
        } else if (ID_RGO.equals(id)) {
            RgoGrenadeEntity grenade = new RgoGrenadeEntity(unit, level);
            grenade.setLife(RGO_FUSE);
            grenade.shootFromRotation(unit, unit.getXRot(), unit.getYRot(), 0.0f, THROW_POWER, 0.0f);
            level.addFreshEntity(grenade);
        } else if (ID_M18.equals(id)) {
            M18SmokeGrenadeEntity grenade = new M18SmokeGrenadeEntity(unit, level, M18_FUSE);
            grenade.setColor(1.0f, 1.0f, 1.0f);
            grenade.shootFromRotation(unit, unit.getXRot(), unit.getYRot(), 0.0f, THROW_POWER, 0.0f);
            level.addFreshEntity(grenade);
        } else {
            return false;
        }

        level.playSound(null, unit.getX(), unit.getY(), unit.getZ(),
                com.atsuishio.superbwarfare.init.ModSounds.GRENADE_THROW.get(),
                SoundSource.NEUTRAL, 1.0f, 1.0f);
        return true;
    }
}
