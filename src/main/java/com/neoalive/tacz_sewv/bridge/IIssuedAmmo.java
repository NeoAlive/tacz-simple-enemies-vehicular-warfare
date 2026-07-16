package com.neoalive.tacz_sewv.bridge;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

/**
 * An unlimited supply of one ammunition item, issued to a crew at spawn.
 *
 * <p>This exists because <b>RU/US units have no inventory to put ammo in</b>. SEM's
 * {@code UnitInventoryHandler} is built from a {@code PmcUnitEntity} and exposed only by
 * {@code PmcUnitEntity.getCapability}; {@code RUunitEntity}/{@code USunitEntity} extend
 * {@code AbstractUnit} as siblings, not subclasses, and have neither an inventory nor an
 * {@code ITEM_HANDLER} capability. Every stock-the-crew's-pockets approach therefore fails
 * silently on them — and it fails <em>quietly</em>, because SBW's own lookups are all
 * null-tolerant: {@code AmmoConsumer.count(data, null handler)} simply returns 0, so a fully
 * crewed RU mortar reports "no shells" and stands there.
 *
 * <p>So the crew doesn't carry rounds; it is <em>issued</em> a type and has as many as it
 * needs. Which is also the honest model for the thing being simulated — an attacking battery
 * arrives with its ammunition, and the player's answer to it is to go and kill the crew, not
 * to outlast a counter.
 *
 * <p>Persistent, and by the same rule as {@link IHelicopterPilot}: an item id carries no
 * entity network id, so it survives a reload meaning exactly what it meant. A battery that
 * was shooting white phosphorus before a restart is still shooting white phosphorus after.
 *
 * <p>A crew with none set (every PMC unit a player ordered onto a tube with the board key)
 * falls back to reading its actual inventory, which is what makes hand-loading a mortar with
 * the shells you chose still work.
 */
public interface IIssuedAmmo {

    String TAG_ISSUED_AMMO = "tacz_sewv_issued_ammo";

    default void sewv$setIssuedAmmo(@Nullable Item item) {
        CompoundTag tag = ((Entity) this).getPersistentData();
        if (item == null) {
            tag.remove(TAG_ISSUED_AMMO);
            return;
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
        if (id != null) tag.putString(TAG_ISSUED_AMMO, id.toString());
    }

    /** The item this crew has an unlimited supply of, or null if it carries its own. */
    @Nullable
    default Item sewv$getIssuedAmmo() {
        CompoundTag tag = ((Entity) this).getPersistentData();
        if (!tag.contains(TAG_ISSUED_AMMO)) return null;

        ResourceLocation id = ResourceLocation.tryParse(tag.getString(TAG_ISSUED_AMMO));
        // containsKey first: the item registry is defaulted, so a bare getValue() on an id
        // from a since-removed addon would hand back minecraft:air rather than null, and the
        // crew would silently believe it had been issued nothing-shaped ammunition.
        if (id == null || !ForgeRegistries.ITEMS.containsKey(id)) return null;
        return ForgeRegistries.ITEMS.getValue(id);
    }
}
