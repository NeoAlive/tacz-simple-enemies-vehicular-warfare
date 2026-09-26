package com.neoalive.tacz_sewv.loadout;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

import com.tacz.guns.api.item.attachment.AttachmentType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Two strengths on purpose. {@link #gunUsable} is the only thing the merge path asks, and it is
 * lenient: TACZ's index is not guaranteed loaded when SEM's listener runs (same lesson as
 * {@code BulletFacts}), so "index empty" must read as "cannot tell", never "invalid". {@link
 * #sanitize} is the strict save-time pass, where the index is certainly up.
 */
public final class LoadoutValidator {

    /** Armor loadout key → the slot an item must declare to be valid there (also drives the editor's autofill). */
    public static final Map<String, EquipmentSlot> ARMOR_SLOT = Map.of(
            "helmet_id", EquipmentSlot.HEAD,
            "chest_plate_id", EquipmentSlot.CHEST,
            "leggings_id", EquipmentSlot.LEGS,
            "boots_id", EquipmentSlot.FEET);

    private LoadoutValidator() {}

    /** Merge-time gate: drop a row only when TACZ demonstrably does not know the gun. */
    public static boolean gunUsable(String gunId) {
        if (WeaponCatalogSource.gunMeta(gunId).isPresent()) return true;
        return WeaponCatalogSource.gunIds().isEmpty();
    }

    /**
     * Strict pass for a row coming from the editor. Returns the cleaned copy, or null when the row
     * cannot be salvaged; every change made is appended to {@code problems} for the action bar.
     */
    @Nullable
    public static LoadoutRow sanitize(LoadoutRow in, boolean extended, List<String> problems) {
        LoadoutRow r = in.copy();
        r.name = cleanName(r.name, r.gunId);
        if (ResourceLocation.tryParse(r.gunId) == null) {
            problems.add(r.name + ": bad gun id");
            return null;
        }
        var meta = WeaponCatalogSource.gunMeta(r.gunId);
        if (meta.isEmpty() && !WeaponCatalogSource.gunIds().isEmpty()) {
            problems.add(r.name + ": unknown gun " + r.gunId);
            return null;
        }
        r.weight = Math.max(1, Math.min(LoadoutRow.MAX_WEIGHT, r.weight));
        r.fireMode = r.fireMode == null ? "SEMI" : r.fireMode.toUpperCase(Locale.ROOT);
        if (!extended && r.fireMode.equals("BURST")) {
            problems.add(r.name + ": BURST needs SEM Extended, set to SEMI");
            r.fireMode = "SEMI";
        }
        meta.ifPresent(m -> {
            r.ammo = Math.max(1, Math.min(r.ammo, Math.max(1, m.magazine())));
            if (!m.fireModes().isEmpty() && m.fireModes().stream().noneMatch(f -> f.name().equals(r.fireMode))) {
                r.fireMode = m.fireModes().get(0).name();
                problems.add(r.name + ": fire mode not supported by gun, set to " + r.fireMode);
            }
        });
        r.ids.entrySet().removeIf(e -> !keep(r, e.getKey(), e.getValue(), problems));
        return r;
    }

    private static boolean keep(LoadoutRow r, String key, String id, List<String> problems) {
        if (id == null || id.isEmpty()) return false;
        AttachmentType slot = WeaponCatalogSource.SLOT_OF_KEY.get(key);
        if (slot != null) {
            AttachmentType actual = WeaponCatalogSource.attachmentType(id);
            boolean ok = actual == slot && WeaponCatalogSource.allows(r.gunId, id);
            if (!ok) problems.add(r.name + ": " + id + " does not fit " + key);
            return ok;
        }
        EquipmentSlot want = ARMOR_SLOT.get(key);
        ResourceLocation rl = ResourceLocation.tryParse(id);
        Item item = rl == null ? null : ForgeRegistries.ITEMS.getValue(rl);
        boolean ok = want != null && item instanceof ArmorItem armor && armor.getEquipmentSlot() == want;
        if (!ok) problems.add(r.name + ": " + id + " is not valid for " + key);
        return ok;
    }

    private static String cleanName(String name, String gunId) {
        String n = name == null ? "" : name.trim().replaceAll("[^A-Za-z0-9_.\\-]", "_");
        if (n.isEmpty()) {
            int colon = gunId.indexOf(':');
            n = colon >= 0 ? gunId.substring(colon + 1) : gunId;
            if (n.isEmpty()) n = "loadout";
        }
        return n.length() > 48 ? n.substring(0, 48) : n;
    }
}
