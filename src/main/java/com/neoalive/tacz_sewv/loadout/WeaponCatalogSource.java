package com.neoalive.tacz_sewv.loadout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.data.gun.AmmoConsumer;
import com.atsuishio.superbwarfare.data.gun.FireModeInfo;
import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.gun.GunProp;
import com.tacz.guns.api.DefaultAssets;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.api.item.builder.AttachmentItemBuilder;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import com.tacz.guns.api.item.gun.FireMode;
import com.tacz.guns.resource.index.CommonGunIndex;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import com.neoalive.tacz_sewv.entity.ai.support.SmallArmsSupport;

/**
 * Every TACZ gun and attachment that is actually loaded, from TACZ's own indices rather than any
 * registry — so any gun pack (tacz_default_gun, lrtactical, daffas_arsenal, …) is covered with no
 * id list of ours — plus every SuperbWarfare hand gun the AI can use
 * ({@link SmallArmsSupport#isUsableSbwGun}), from the item registry. Shared by the server (packet snapshot + save-time validation) and the client
 * (autocomplete cache), like {@code VehiclePoolCatalogSource}.
 */
public final class WeaponCatalogSource {

    private WeaponCatalogSource() {}

    /** Loadout JSON key → the TACZ slot it fills. Armor keys are not attachments. */
    public static final java.util.Map<String, AttachmentType> SLOT_OF_KEY = java.util.Map.of(
            "scope_id", AttachmentType.SCOPE,
            "muzzle_id", AttachmentType.MUZZLE,
            "grip_id", AttachmentType.GRIP,
            "stock_id", AttachmentType.STOCK,
            "mag_id", AttachmentType.EXTENDED_MAG,
            "laser_id", AttachmentType.LASER);

    /** What the editor needs to know about one gun; derived on demand, never sent over the wire. */
    /** {@code magazine} is the largest any extended mag allows (the save-time cap); {@code baseMagazine} is stock. */
    /** {@code fireModes} are upper-case names (TACZ enum names, or SBW fire-mode names); {@code sbw} marks an SBW gun. */
    public record GunMeta(String type, String ammoId, int magazine, int baseMagazine, List<String> fireModes, boolean sbw) {}

    public static List<String> gunIds() {
        List<String> out = taczGunIds();
        out.addAll(sbwGunIds());
        out.sort(String::compareTo);
        return out;
    }

    /** TACZ's gun index alone. Empty = the index is not loaded yet, which callers read as "cannot tell". */
    public static List<String> taczGunIds() {
        List<String> out = new ArrayList<>();
        try {
            TimelessAPI.getAllCommonGunIndex().forEach(e -> out.add(e.getKey().toString()));
        } catch (Throwable ignored) {
            // TACZ index not ready yet — the caller retries, as the vehicle catalog does.
        }
        return out;
    }

    /** Usable SBW hand guns in the item registry (addons included). */
    public static List<String> sbwGunIds() {
        List<String> out = new ArrayList<>();
        for (var e : ForgeRegistries.ITEMS.getEntries()) {
            if (SmallArmsSupport.isUsableSbwGun(e.getValue())) out.add(e.getKey().location().toString());
        }
        return out;
    }

    /** The id names a usable SBW gun — the partition {@code LoadoutMerge} keeps out of SEM's files. */
    public static boolean isSbwGun(String gunId) {
        return sbwItem(gunId) != null;
    }

    @Nullable
    private static Item sbwItem(String gunId) {
        ResourceLocation rl = ResourceLocation.tryParse(gunId);
        if (rl == null) return null;
        Item item = ForgeRegistries.ITEMS.getValue(rl);
        return SmallArmsSupport.isUsableSbwGun(item) ? item : null;
    }

    public static List<String> attachmentIds() {
        List<String> out = new ArrayList<>();
        try {
            TimelessAPI.getAllCommonAttachmentIndex().forEach(e -> out.add(e.getKey().toString()));
        } catch (Throwable ignored) {
        }
        out.sort(String::compareTo);
        return out;
    }

    public static Optional<GunMeta> gunMeta(String gunId) {
        ResourceLocation rl = ResourceLocation.tryParse(gunId);
        if (rl == null) return Optional.empty();
        Item sbw = sbwItem(gunId);
        if (sbw != null) return sbwMeta(sbw);
        try {
            return TimelessAPI.getCommonGunIndex(rl).map(WeaponCatalogSource::meta);
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    private static GunMeta meta(CommonGunIndex index) {
        var data = index.getGunData();
        int base = data.getAmmoAmount();
        int mag = base;
        int[] ext = data.getExtendedMagAmmoAmount();
        if (ext != null) for (int n : ext) mag = Math.max(mag, n);
        ResourceLocation ammo = data.getAmmoId();
        return new GunMeta(
                index.getType(), ammo == null ? "" : ammo.toString(), mag, base,
                data.getFireModeSet() == null ? List.of() : data.getFireModeSet().stream().map(FireMode::name).toList(),
                false);
    }

    /** Type is {@code sbw_<class>} (sbw_small_arms, sbw_at, ...), ammo the SBW ammo spec as written. */
    private static Optional<GunMeta> sbwMeta(Item item) {
        try {
            GunData gun = GunData.from(new ItemStack(item));
            int mag = Math.max(0, gun.get(GunProp.MAGAZINE));
            List<String> modes = new ArrayList<>();
            List<FireModeInfo> infos = gun.get(GunProp.AVAILABLE_FIRE_MODES);
            if (infos != null) infos.forEach(f -> modes.add(f.name.toUpperCase(Locale.ROOT)));
            String ammo = "";
            List<AmmoConsumer> consumers = gun.get(GunProp.AMMO_CONSUMER);
            if (consumers != null && !consumers.isEmpty() && consumers.get(0).getAmmo() != null) {
                ammo = consumers.get(0).getAmmo();
            }
            String type = "sbw_" + SmallArmsSupport.classOf(item, gun).name().toLowerCase(Locale.ROOT);
            return Optional.of(new GunMeta(type, ammo, mag, mag, List.copyOf(modes), true));
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    /**
     * A loadout row copied from a real gun stack: gun, fire mode, loaded rounds (or the stock magazine
     * for an empty gun, which would otherwise make a useless entry) and every installed attachment.
     * Empty when the stack is neither a TACZ nor a usable SBW gun. Reads only the stack, so it is safe
     * on either side.
     */
    public static Optional<LoadoutRow> fromStack(ItemStack stack) {
        if (SmallArmsSupport.isUsableSbwGun(stack.getItem())) return sbwFromStack(stack);
        IGun gun = IGun.getIGunOrNull(stack);
        if (gun == null) return Optional.empty();
        ResourceLocation id = gun.getGunId(stack);
        if (id == null || DefaultAssets.EMPTY_GUN_ID.equals(id)) return Optional.empty();
        LoadoutRow r = new LoadoutRow();
        r.gunId = id.toString();
        r.name = id.getPath();
        Optional<GunMeta> meta = gunMeta(r.gunId);
        int loaded = gun.getCurrentAmmoCount(stack);
        r.ammo = loaded > 0 ? loaded : meta.map(GunMeta::baseMagazine).orElse(30);
        FireMode mode = gun.getFireMode(stack);
        if (mode != null && mode != FireMode.UNKNOWN) {
            r.fireMode = mode.name();
        } else {
            r.fireMode = meta.filter(m -> !m.fireModes().isEmpty()).map(m -> m.fireModes().get(0)).orElse("SEMI");
        }
        for (String key : LoadoutRow.ID_KEYS) {
            AttachmentType slot = SLOT_OF_KEY.get(key);
            if (slot == null) continue;
            ResourceLocation att = gun.getAttachmentId(stack, slot);
            if (att != null && !DefaultAssets.isEmptyAttachmentId(att)) r.ids.put(key, att.toString());
        }
        return Optional.of(r);
    }

    private static Optional<LoadoutRow> sbwFromStack(ItemStack stack) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) return Optional.empty();
        LoadoutRow r = new LoadoutRow();
        r.gunId = id.toString();
        r.name = id.getPath();
        Optional<GunMeta> meta = gunMeta(r.gunId);
        r.ammo = Math.max(1, meta.map(GunMeta::magazine).orElse(1));
        r.fireMode = meta.filter(m -> !m.fireModes().isEmpty()).map(m -> m.fireModes().get(0)).orElse("SEMI");
        try {
            GunData gun = GunData.from(stack);
            if (gun.ammo.get() > 0) r.ammo = gun.ammo.get();
            r.fireMode = gun.selectedFireModeInfo().name.toUpperCase(Locale.ROOT);
        } catch (Throwable ignored) {
            // meta defaults above
        }
        return Optional.of(r);
    }

    @Nullable
    public static AttachmentType attachmentType(String attachmentId) {
        ResourceLocation rl = ResourceLocation.tryParse(attachmentId);
        if (rl == null) return null;
        try {
            return TimelessAPI.getCommonAttachmentIndex(rl).map(i -> i.getType()).orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** TACZ's own answer to "may this gun take this attachment" (type list + per-gun allow tags). */
    public static boolean allows(String gunId, String attachmentId) {
        ResourceLocation gun = ResourceLocation.tryParse(gunId);
        ResourceLocation att = ResourceLocation.tryParse(attachmentId);
        if (gun == null || att == null) return false;
        try {
            ItemStack gunStack = GunItemBuilder.create().setId(gun).build();
            IGun iGun = IGun.getIGunOrNull(gunStack);
            if (iGun == null) return false;
            return iGun.allowAttachment(gunStack, AttachmentItemBuilder.create().setId(att).build());
        } catch (Throwable t) {
            return false;
        }
    }
}
