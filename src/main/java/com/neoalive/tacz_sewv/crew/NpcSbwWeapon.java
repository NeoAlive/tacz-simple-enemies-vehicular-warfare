package com.neoalive.tacz_sewv.crew;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;

import com.neoalive.tacz_sewv.entity.ai.support.SmallArmsSupport;
import com.neoalive.tacz_sewv.loadout.LoadoutManager;
import com.neoalive.tacz_sewv.loadout.LoadoutMerge;
import com.neoalive.tacz_sewv.loadout.LoadoutRow;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;

/**
 * The SBW half of the loadout manager: re-arms a freshly spawned RU/US unit from its faction's SBW
 * rows ({@link LoadoutMerge#sbwPool}). SEM's own equipper can only build TACZ guns — an SBW id there
 * leaves the unit empty-handed — so those rows never reach SEM and are applied here instead, after
 * SEM has equipped its TACZ roll. Rolling against the pool's {@code share} is what keeps every row's
 * overall odds equal to the editor's % column.
 *
 * <p>Same shape as {@link NpcArmor}: runs off {@code EntityJoinLevelEvent}, once per unit, behind a
 * persistent flag. A unit loaded from disk without the flag predates this feature and is marked
 * rather than re-armed, so neither an old world nor a saved unit ever re-rolls.
 *
 * <p>Exact SEM {@code ruunit}/{@code usunit} only — this mod's own unit subclasses (engineers,
 * medics) have loadouts of their own. RU/US only because issuing is: see {@link SmallArmsSupport}.
 */
public final class NpcSbwWeapon {

    private static final String ROLLED = "sewv:sbw_rolled";

    private NpcSbwWeapon() {}

    public static void issue(AbstractUnit unit, boolean loadedFromDisk) {
        TankFaction faction = unit.getClass() == RUunitEntity.class ? TankFaction.RU
                : unit.getClass() == USunitEntity.class ? TankFaction.US : null;
        if (faction == null) return;

        CompoundTag data = unit.getPersistentData();
        if (data.getBoolean(ROLLED)) return;
        data.putBoolean(ROLLED, true);
        if (loadedFromDisk) return;

        LoadoutMerge.SbwPool pool = LoadoutManager.sbwPool(faction);
        if (pool.rows().isEmpty() || unit.getRandom().nextDouble() >= pool.share()) return;

        LoadoutRow row = pick(pool, unit.getRandom().nextInt(totalWeight(pool)));
        ResourceLocation id = ResourceLocation.tryParse(row.gunId);
        Item item = id == null ? null : ForgeRegistries.ITEMS.getValue(id);
        // Reserve starts at zero: AtWeaponGoal tops RU/US up to two magazines whenever it runs.
        ItemStack stack = SmallArmsSupport.buildIssued(item, row.ammo, row.fireMode, 0);
        if (stack != null) unit.setItemInHand(InteractionHand.MAIN_HAND, stack);
    }

    private static int totalWeight(LoadoutMerge.SbwPool pool) {
        int total = 0;
        for (LoadoutRow r : pool.rows()) total += Math.max(1, r.weight);
        return total;
    }

    private static LoadoutRow pick(LoadoutMerge.SbwPool pool, int roll) {
        for (LoadoutRow r : pool.rows()) {
            roll -= Math.max(1, r.weight);
            if (roll < 0) return r;
        }
        return pool.rows().get(pool.rows().size() - 1);
    }
}
