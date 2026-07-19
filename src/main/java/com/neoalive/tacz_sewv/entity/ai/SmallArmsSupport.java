package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.item.gun.GunItem;
import com.neoalive.tacz_sewv.config.SewvConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;

/**
 * Hands a SuperbWarfare launcher to a dismounting IFV crewman, so the squad an IFV puts on the
 * ground has something that can actually hurt what put it there.
 *
 * <p>A dismount only ever happens because the hull met {@code TargetCategory.VEHICLE} — see
 * {@link DriveVehicleGoal#dismountSquad} — and a TACZ rifle cannot scratch armour. Without this
 * the feature drops riflemen in front of a tank.
 *
 * <p>Firing what this issues is {@link AtWeaponGoal}'s job. The two halves are deliberately
 * split the way {@code MortarSupport}/{@code ManMortarGoal} are: issuing is a one-shot act at a
 * known moment, firing is a per-tick concern with nothing to say about where the weapon came
 * from.
 *
 * <h2>RU/US only</h2>
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
     * Marks a unit as already having drawn its launcher. Needed because
     * {@link DriveVehicleGoal#dismountSquad} runs on <b>every</b> combat tick with no done-flag
     * (that is what its passenger-count early-out replaces), so without this a crewman who
     * re-boarded and dismounted again would be re-issued — and the counter that caps a squad at
     * two gunners would be counting the same man twice.
     *
     * <p>Persistent rather than transient, for the same reason {@code NpcArmor}'s flag is: a
     * gunner is still a gunner after a save/load, and re-issuing on every chunk load would hand
     * out a fresh full supply of rockets each time.
     */
    private static final String ISSUED = "sewv:at_issued";

    private SmallArmsSupport() {}

    /**
     * Issue an anti-tank launcher to this unit, and report whether one actually went out.
     *
     * <p>The return value is what lets the caller cap a squad at two gunners without knowing any
     * of the rules here: a PMC, an already-armed unit, a faction whose weapon id is blank, and a
     * misconfigured id all answer {@code false}, so the count tracks weapons handed out rather
     * than attempts made.
     */
    public static boolean issueAtWeapon(AbstractUnit unit) {
        Item item = weaponFor(unit);
        // Not a GunItem means either a blank/typo'd config id or an item that isn't a
        // SuperbWarfare gun at all. Either way AtWeaponGoal could not fire it, and putting it in
        // the unit's hand would only cost it the rifle it already had.
        if (!(item instanceof GunItem)) return false;

        CompoundTag data = unit.getPersistentData();
        if (data.getBoolean(ISSUED)) return false;

        try {
            ItemStack stack = new ItemStack(item);
            GunData gun = GunData.from(stack);
            // The supply is ISSUED, not carried: virtualAmmo is SuperbWarfare's own item-free
            // ammo channel, and it is the only one that works here — countBackupAmmo resolves
            // real ammo through the ITEM_HANDLER capability, which an RU/US unit does not have,
            // so every inventory lookup silently answers 0. Same trick TowSupport.reload uses.
            gun.virtualAmmo.set(SewvConfig.AT_BACKUP_AMMO.get());
            gun.reloadAmmo(unit); // spawn with a round on the rail rather than a reload cycle
            gun.save();
            // gun.stack is the very stack passed to from() (its cache is keyed on identity), so
            // this carries the ammo NBT the two calls above just wrote.
            unit.setItemInHand(InteractionHand.MAIN_HAND, gun.stack);
        } catch (Exception e) {
            return false; // a broken gun datapack must not take the dismount down with it
        }

        data.putBoolean(ISSUED, true);
        return true;
    }

    /**
     * The launcher this unit's side issues, or null for anyone who gets none.
     *
     * <p>Resolved from config by id rather than referencing {@code ModItems.RPG} directly, which
     * costs nothing and lets a pack point a faction at an addon's launcher — the same reasoning
     * {@code NpcArmor} follows for armour.
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
