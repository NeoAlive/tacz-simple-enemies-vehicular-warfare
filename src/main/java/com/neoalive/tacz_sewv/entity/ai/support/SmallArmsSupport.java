package com.neoalive.tacz_sewv.entity.ai.support;

import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.data.gun.Ammo;
import com.atsuishio.superbwarfare.data.gun.AmmoConsumer;
import com.atsuishio.superbwarfare.data.gun.FireModeInfo;
import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.gun.GunProp;
import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.item.gun.GunItem;
import com.atsuishio.superbwarfare.item.gun.launcher.IglaItem;
import com.atsuishio.superbwarfare.item.gun.special.BocekItem;
import com.atsuishio.superbwarfare.item.gun.special.RepairToolItem;
import com.atsuishio.superbwarfare.item.gun.special.TaserItem;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;

/**
 * SuperbWarfare hand weapons on SEM units: which SBW guns the AI can use at all, what each one
 * is for ({@link SbwClass}), building an issued stack, and the anti-tank launcher an IFV hands a
 * dismounting crewman.
 *
 * <p>Firing is {@link AtWeaponGoal}'s job. The two halves are deliberately split the way
 * {@code MortarSupport}/{@code ManMortarGoal} are: issuing is a one-shot act at a known moment,
 * firing is a per-tick concern with nothing to say about where the weapon came from.
 *
 * <h2>Issuing is RU/US only</h2>
 * A PMC's loadout is the player's business — it is the one unit type SEM gives an inventory to,
 * and the player fills it. Beyond that, writing a weapon into a {@code PmcUnitEntity} could not
 * use {@code setItemInHand} at all: SEM's {@code UnitInventoryHandler} mirrors inventory slot 0
 * onto the main hand and {@code dropCustomDeathLoot} iterates the <em>inventory</em>, so a
 * direct hand-write would leave the stale rifle in slot 0 and drop the wrong weapon on death.
 * RU/US have no inventory and no {@code ITEM_HANDLER} at all, which makes the hand the only
 * place their loadout lives and {@code setItemInHand} exactly right.
 */
public final class SmallArmsSupport {

    /**
     * Marks a unit as already counted as an AT gunner. Needed because
     * {@link DriveVehicleGoal#dismountSquad} runs on <b>every</b> combat tick with no done-flag,
     * so without this a crewman who re-boarded and dismounted again would be re-issued — and the
     * counter that caps a squad at two gunners would be counting the same man twice.
     *
     * <p>Persistent rather than transient, for the same reason {@code NpcArmor}'s flag is: a
     * gunner is still a gunner after a save/load, and re-issuing on every chunk load would hand
     * out a fresh full supply of rockets each time.
     */
    private static final String ISSUED = "sewv:at_issued";

    /** What an SBW hand weapon is for — decides its target filter, range and spread. */
    public enum SbwClass { SMALL_ARMS, SNIPER, AT, AA }

    /**
     * SBW "guns" with no class of their own to exclude by that a mob still cannot use: the star
     * shooter is a joke weapon, the other two are internal placeholders. The Bocek (fires only on
     * a Player's draw-and-release), taser (needs FE nothing recharges) and repair tool are
     * excluded by class in {@link #isUsableSbwGun}.
     */
    private static final Set<String> EXCLUDED_IDS = Set.of(
            "superbwarfare:super_star_shooter", "superbwarfare:vehicle_gun", "superbwarfare:empty_gun");

    private SmallArmsSupport() {}

    /** An SBW gun this mod's AI can issue and fire — the one predicate every SBW-gun test goes through. */
    public static boolean isUsableSbwGun(@Nullable Item item) {
        if (!(item instanceof GunItem)) return false;
        // RepairToolItem extends GunGeoItem extends GunItem and carries readable gun data, so
        // without this an engineer would "shoot" a repair beam at infantry.
        if (item instanceof RepairToolItem || item instanceof BocekItem || item instanceof TaserItem) return false;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
        return id != null && !EXCLUDED_IDS.contains(id.toString());
    }

    /** {@link #isUsableSbwGun} with readable gun data, or null. Never throws. */
    @Nullable
    public static GunData usableGun(ItemStack stack) {
        if (stack.isEmpty() || !isUsableSbwGun(stack.getItem())) return null;
        try {
            return GunData.from(stack);
        } catch (Throwable ignored) {
            return null; // unreadable gun data must never crash the AI tick
        }
    }

    /**
     * Class from data SBW already carries rather than an id list, so addon guns classify too:
     * SBW's own {@code item.gun.launcher} package — and any gun fed by a plain ammo ITEM instead of
     * a player ammo class (rockets, missiles, 40mm) — is anti-armour, the Igla is anti-air, and a
     * sniper/heavy player-ammo class is a sniper.
     */
    public static SbwClass classOf(Item item, @Nullable GunData gun) {
        if (item instanceof IglaItem) return SbwClass.AA;
        if (item.getClass().getName().startsWith("com.atsuishio.superbwarfare.item.gun.launcher.")) {
            return SbwClass.AT;
        }
        Ammo ammoClass = null;
        boolean itemAmmo = false;
        if (gun != null) {
            try {
                List<AmmoConsumer> consumers = gun.get(GunProp.AMMO_CONSUMER);
                if (consumers != null && !consumers.isEmpty()) {
                    AmmoConsumer c = gun.selectedAmmoConsumer(consumers);
                    ammoClass = c.getPlayerAmmoType();
                    String ammo = c.getAmmo();
                    itemAmmo = ammoClass == null && ammo != null && ammo.contains(":");
                }
            } catch (Throwable ignored) {
                // unreadable consumer: fall through to small arms
            }
        }
        if (ammoClass == Ammo.SNIPER || ammoClass == Ammo.HEAVY) return SbwClass.SNIPER;
        if (itemAmmo) return SbwClass.AT;
        return SbwClass.SMALL_ARMS;
    }

    /**
     * A fresh stack of {@code item}: {@code fireMode} selected when it names one of the gun's
     * modes, {@code loaded} rounds in the magazine (clamped to it, at least one), and an ISSUED
     * reserve of {@code reserve} in {@code virtualAmmo}. Null for anything not a usable SBW gun.
     *
     * <p>{@code virtualAmmo} is SBW's own item-free ammo channel and the only one that works for
     * RU/US: {@code countBackupAmmo} otherwise resolves real ammo through the ITEM_HANDLER
     * capability, which those units do not have. {@code reloadAmmo} fills FROM backup, so the
     * rounds to load are staged there first and the reserve is set afterwards.
     */
    @Nullable
    public static ItemStack buildIssued(Item item, int loaded, @Nullable String fireMode, int reserve) {
        if (!isUsableSbwGun(item)) return null;
        try {
            ItemStack stack = new ItemStack(item);
            GunData gun = GunData.from(stack);
            if (fireMode != null && !fireMode.isBlank()) {
                List<FireModeInfo> modes = gun.get(GunProp.AVAILABLE_FIRE_MODES);
                if (modes != null) {
                    for (int i = 0; i < modes.size(); i++) {
                        if (modes.get(i).name.equalsIgnoreCase(fireMode)) {
                            gun.selectedFireMode.set(i);
                            break;
                        }
                    }
                }
            }
            int mag = Math.max(0, gun.get(GunProp.MAGAZINE));
            if (mag > 0) {
                gun.virtualAmmo.set(Math.max(1, Math.min(loaded, mag)));
                gun.reloadAmmo(null);
            }
            gun.virtualAmmo.set(Math.max(0, reserve));
            gun.save();
            // gun.stack is the very stack passed to from() (its cache is keyed on identity), so it
            // carries the NBT just written.
            return gun.stack;
        } catch (Exception e) {
            return null; // a broken gun datapack must not take the caller down with it
        }
    }

    /**
     * Issue an anti-tank launcher to this unit, and report whether it is now an AT gunner.
     *
     * <p>The return value is what lets the caller cap a squad at two gunners without knowing any
     * of the rules here: a PMC, an already-counted unit, a faction whose weapon id is blank, and a
     * misconfigured id all answer {@code false}.
     */
    public static boolean issueAtWeapon(AbstractUnit unit) {
        Item item = weaponFor(unit);
        if (!isUsableSbwGun(item)) return false;

        CompoundTag data = unit.getPersistentData();
        if (data.getBoolean(ISSUED)) return false;

        // A unit whose loadout already put an anti-armour launcher in its hand IS an AT gunner:
        // count it without swapping its weapon for the config one.
        if (!holdsLauncher(unit)) {
            ItemStack stack = buildIssued(item, Integer.MAX_VALUE, null, SewvConfig.AT_BACKUP_AMMO.get());
            if (stack == null) return false;
            unit.setItemInHand(InteractionHand.MAIN_HAND, stack);
        }

        data.putBoolean(ISSUED, true);
        return true;
    }

    /**
     * Whether this unit must refuse {@code target} <b>because of the SBW launcher in its hand</b>
     * — {@code false} for everyone not holding an anti-armour or anti-air one.
     *
     * <p>A launcher has exactly one job and the target ladder should say so. AT may only lock a
     * crewed hull (a {@link VehicleEntity} is never itself the target — the AI targets the crew
     * riding it) whose datapack declares an engine type; {@code EngineType.EMPTY} is the "no data"
     * fallback, so the tube stands down rather than spend a rocket on a rifleman. AA may only lock
     * the crew of an aircraft. Rifles, MGs and snipers refuse nothing.
     *
     * <p>Applied at {@code setTarget} (see {@code MixinAbstractUnit}) for the same reason
     * {@link SupportRole#refusesTarget} is — a veto at acquisition covers SEM's own scans,
     * retaliation and player orders at once.
     */
    public static boolean refusesTarget(AbstractUnit unit, @Nullable LivingEntity target) {
        if (target == null) return false;
        SbwClass cls = launcherClass(unit);
        if (cls == null) return false;

        if (!(target.getVehicle() instanceof VehicleEntity hull)) return true;
        EngineType engine = HullFacts.engineType(hull);
        if (cls == SbwClass.AA) return engine != EngineType.HELICOPTER && engine != EngineType.AIRCRAFT;
        return engine == EngineType.EMPTY;
    }

    /** An SBW anti-armour or anti-air launcher in either hand. */
    public static boolean holdsLauncher(AbstractUnit unit) {
        return launcherClass(unit) != null;
    }

    @Nullable
    private static SbwClass launcherClass(AbstractUnit unit) {
        SbwClass main = launcherClass(unit.getMainHandItem());
        return main != null ? main : launcherClass(unit.getOffhandItem());
    }

    @Nullable
    private static SbwClass launcherClass(ItemStack stack) {
        GunData gun = usableGun(stack);
        if (gun == null) return null;
        SbwClass cls = classOf(stack.getItem(), gun);
        return cls == SbwClass.AT || cls == SbwClass.AA ? cls : null;
    }

    /**
     * The launcher this unit's side issues, or null for anyone who gets none. Resolved from
     * config by id so a pack can point a faction at an addon's launcher.
     */
    private static Item weaponFor(AbstractUnit unit) {
        String id;
        if (unit instanceof RUunitEntity) {
            id = SewvConfig.AT_WEAPON_RU.get();
        } else if (unit instanceof USunitEntity) {
            id = SewvConfig.AT_WEAPON_US.get();
        } else {
            return null; // PMC and anything else: loadout is not ours to touch
        }
        if (id == null || id.isBlank()) return null;

        ResourceLocation key = ResourceLocation.tryParse(id);
        return key == null ? null : ForgeRegistries.ITEMS.getValue(key);
    }
}
