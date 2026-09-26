package com.neoalive.tacz_sewv.client.editor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.attachment.AttachmentType;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;

import com.neoalive.tacz_sewv.loadout.LoadoutValidator;
import com.neoalive.tacz_sewv.loadout.WeaponCatalogSource;
import com.neoalive.tacz_sewv.network.PacketOpenLoadoutEditor;

/**
 * Client cache + search for the loadout editor's autofill. Ids like {@code lrtactical:…} are far
 * harder to type than a vehicle id, so a query matches the id, the translated display name, the gun
 * type and the ammo id together ("kalashnikov", "sniper", "556" all find something).
 *
 * <p>The server's id lists (what the server actually has loaded) are merged with a local TACZ scan
 * — either alone may be incomplete on the first frame after connect, as with
 * {@link VehiclePoolCatalog#mergedWith}.
 */
public final class WeaponCatalog {

    private static volatile List<String> guns = List.of();
    private static volatile List<String> attachments = List.of();
    private static volatile List<String> armor = List.of();
    private static final Map<String, String> HAYSTACK = new ConcurrentHashMap<>();
    private static final Map<String, List<String>> ALLOWED = new ConcurrentHashMap<>();

    private WeaponCatalog() {}

    /** Called when the editor opens: union of the server snapshot and this client's own TACZ index. */
    public static void absorb(PacketOpenLoadoutEditor.Data data) {
        guns = union(data.guns(), WeaponCatalogSource.gunIds());
        attachments = union(data.attachments(), WeaponCatalogSource.attachmentIds());
        armor = List.copyOf(new TreeSet<>(data.armor()));
        HAYSTACK.clear();
        ALLOWED.clear();
    }

    private static List<String> union(List<String> a, List<String> b) {
        TreeSet<String> set = new TreeSet<>(a);
        set.addAll(b);
        return List.copyOf(set);
    }

    public static List<String> guns() {
        return guns;
    }

    /**
     * Ids that may fill {@code slotKey} on {@code gunId}: attachments of that slot's type the gun
     * allows (TACZ's own {@code allowAttachment}, computed once per gun+slot), or armor of the
     * matching equipment slot. An empty gun id skips the per-gun check.
     */
    public static List<String> allowedFor(String gunId, String slotKey) {
        return ALLOWED.computeIfAbsent(gunId + "|" + slotKey, k -> {
            AttachmentType type = WeaponCatalogSource.SLOT_OF_KEY.get(slotKey);
            List<String> out = new ArrayList<>();
            if (type != null) {
                boolean checkGun = WeaponCatalogSource.gunMeta(gunId).isPresent();
                for (String id : attachments) {
                    if (WeaponCatalogSource.attachmentType(id) != type) continue;
                    if (checkGun && !WeaponCatalogSource.allows(gunId, id)) continue;
                    out.add(id);
                }
                return out;
            }
            EquipmentSlot want = LoadoutValidator.ARMOR_SLOT.get(slotKey);
            if (want == null) return out;
            for (String id : armor) {
                ResourceLocation rl = ResourceLocation.tryParse(id);
                Item item = rl == null ? null : ForgeRegistries.ITEMS.getValue(rl);
                if (item instanceof ArmorItem a && a.getEquipmentSlot() == want) out.add(id);
            }
            return out;
        });
    }

    /** Translated display name of a gun, attachment or item; empty when nothing is known. */
    public static String label(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return "";
        try {
            var gun = TimelessAPI.getClientGunIndex(rl);
            if (gun.isPresent()) return I18n.get(gun.get().getName());
            var att = TimelessAPI.getClientAttachmentIndex(rl);
            if (att.isPresent()) return I18n.get(att.get().getName());
            Item item = ForgeRegistries.ITEMS.getValue(rl);
            if (item != null && item != net.minecraft.world.item.Items.AIR) return item.getDescription().getString();
        } catch (Throwable ignored) {
            // Client index not built yet — the id alone still matches.
        }
        return "";
    }

    private static String haystack(String id) {
        return HAYSTACK.computeIfAbsent(id, k -> {
            StringBuilder sb = new StringBuilder(k).append(' ').append(label(k));
            WeaponCatalogSource.gunMeta(k).ifPresent(m -> sb.append(' ').append(m.type()).append(' ').append(m.ammoId()));
            return sb.toString().toLowerCase(Locale.ROOT);
        });
    }

    /**
     * Up to {@code limit} ids from {@code pool} matching every whitespace-separated token of
     * {@code typed} anywhere in id / name / type / ammo. Ranked: id prefix, path prefix, name word
     * prefix, then plain substring; shorter ids first within a rank.
     */
    public static List<String> match(String typed, Collection<String> pool, int limit) {
        String q = typed == null ? "" : typed.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return pool.stream().limit(limit).toList();
        List<String> tokens = Arrays.asList(q.split("\\s+"));
        String first = tokens.get(0);
        record Scored(String id, int score) {}
        List<Scored> hits = new ArrayList<>();
        for (String id : pool) {
            String hay = haystack(id);
            if (!tokens.stream().allMatch(hay::contains)) continue;
            String lower = id.toLowerCase(Locale.ROOT);
            int colon = lower.indexOf(':');
            int score;
            if (lower.startsWith(first)) score = 0;
            else if (colon >= 0 && lower.startsWith(first, colon + 1)) score = 1;
            else if (hay.contains(" " + first)) score = 2;
            else score = 3;
            hits.add(new Scored(id, score));
        }
        hits.sort(Comparator.comparingInt(Scored::score).thenComparingInt(s -> s.id().length())
                .thenComparing(Scored::id));
        return hits.stream().limit(limit).map(Scored::id).toList();
    }
}
