package com.neoalive.tacz_sewv.skin;

import java.util.Locale;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

import com.neoalive.tacz_sewv.crew.CrewFacts;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketVehicleSkin;
import com.neoalive.tacz_sewv.spawn.TankSpawner;

/**
 * Sticky faction paint on a hull ({@code sewv:vehicle_skin} + optional
 * {@code sewv:vehicle_skin_salt}). Server never opens PNGs — clients resolve the texture (and any
 * numbered RNG pool) from {@link com.neoalive.tacz_sewv.client.skin.VehicleSkinRegistry}.
 *
 * <p>Spray-GUI ids are {@code ru}/{@code us}/{@code pmc} for a plain file, or {@code ru_0} for a
 * numbered pool member. SBW {@code skinId} is only written for those sewv catalog picks — spawn /
 * mount sticky paint stays in NBT (+ packet) so datapack skins from other mods are not overwritten.
 */
public final class VehicleSkinSupport {

    public static final String TAG = "sewv:vehicle_skin";
    public static final String TAG_SALT = "sewv:vehicle_skin_salt";

    private VehicleSkinSupport() {
    }

    /** {@code ru} / {@code us} / {@code pmc}, or a numbered pool member ({@code ru_0}). */
    public static boolean isSewvSkinId(@Nullable String skinId) {
        return parseSkinId(skinId) != null;
    }

    @Nullable
    public static CrewFacts.Faction get(VehicleEntity hull) {
        CompoundTag data = hull.getPersistentData();
        if (!data.contains(TAG)) return null;
        return parseFaction(data.getString(TAG));
    }

    public static int getSalt(VehicleEntity hull) {
        CompoundTag data = hull.getPersistentData();
        return data.contains(TAG_SALT) ? data.getInt(TAG_SALT) : 0;
    }

    /**
     * Idempotent sticky paint. Does <b>not</b> write SBW {@code skinId} — that field is shared with
     * other mods' datapack skins; clients resolve faction art from sticky NBT / packet instead.
     */
    public static void apply(VehicleEntity hull, @Nullable CrewFacts.Faction faction) {
        if (faction == null) return;
        String id = faction.name().toLowerCase(Locale.ROOT);
        if (faction == get(hull)) {
            if (faction == CrewFacts.Faction.PMC) {
                PmcVehicleLogoSupport.applyIfPmcCaptured(hull, CrewFacts.pmcOwner(hull));
            }
            return;
        }
        int salt = hull.getRandom().nextInt();
        hull.getPersistentData().putString(TAG, id);
        hull.getPersistentData().putInt(TAG_SALT, salt);
        sync(hull, faction, salt);
    }

    /** Spray-GUI pick of a sewv catalog id ({@code ru}, {@code ru_0}, …). */
    public static void setFromSkinId(VehicleEntity hull, @Nullable String skinId) {
        if (skinId == null || skinId.isBlank()) {
            clear(hull);
            return;
        }
        Parsed parsed = parseSkinId(skinId);
        if (parsed == null) {
            return;
        }
        int salt = parsed.variant >= 0 ? parsed.variant : hull.getRandom().nextInt();
        String factionKey = parsed.faction.name().toLowerCase(Locale.ROOT);
        hull.getPersistentData().putString(TAG, factionKey);
        hull.getPersistentData().putInt(TAG_SALT, salt);
        hull.setSkinId(skinId.toLowerCase(Locale.ROOT));
        sync(hull, parsed.faction, salt);
    }

    /** Command/event crewed spawns — always paint the hull in the spawning faction's colours. */
    public static void applySpawnFaction(VehicleEntity hull, TankSpawner.TankFaction faction) {
        applySpawnFaction(hull, faction, null);
    }

    /** Command/event crewed spawns with optional PMC owner for dogTag logo stamping. */
    public static void applySpawnFaction(VehicleEntity hull, TankSpawner.TankFaction faction,
                                         @Nullable java.util.UUID pmcOwner) {
        CrewFacts.Faction paint = switch (faction) {
            case RU -> CrewFacts.Faction.RU;
            case US -> CrewFacts.Faction.US;
            case PMC -> CrewFacts.Faction.PMC;
        };
        apply(hull, paint);
        // Spawn paint runs before passengers board — stamp from the spawn faction, not crew.
        PmcVehicleLogoSupport.applySpawnFaction(hull, paint, pmcOwner);
    }

    /** Set sticky paint, or clear to stock when {@code faction} is null. */
    public static void set(VehicleEntity hull, @Nullable CrewFacts.Faction faction) {
        if (faction == null) {
            clear(hull);
        } else {
            apply(hull, faction);
        }
    }

    /** Drop sticky paint only — leave SBW {@code skinId} alone (foreign datapack cams). */
    public static void clearSticky(VehicleEntity hull) {
        CompoundTag data = hull.getPersistentData();
        if (!data.contains(TAG) && !data.contains(TAG_SALT)) return;
        data.remove(TAG);
        data.remove(TAG_SALT);
        sync(hull, null, 0);
    }

    public static void clear(VehicleEntity hull) {
        CompoundTag data = hull.getPersistentData();
        boolean hadSticky = data.contains(TAG) || data.contains(TAG_SALT);
        boolean hadSewvSkinId = isSewvSkinId(hull.getSkinId());
        if (!hadSticky && !hadSewvSkinId) return;
        data.remove(TAG);
        data.remove(TAG_SALT);
        if (hadSewvSkinId) {
            hull.setSkinId("");
        }
        sync(hull, null, 0);
    }

    public static void syncTo(ServerPlayer player, VehicleEntity hull) {
        NetworkHandler.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new PacketVehicleSkin(hull.getId(), get(hull), getSalt(hull)));
    }

    private static void sync(VehicleEntity hull, @Nullable CrewFacts.Faction faction, int salt) {
        if (!(hull.level() instanceof ServerLevel)) return;
        NetworkHandler.CHANNEL.send(
                PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> hull),
                new PacketVehicleSkin(hull.getId(), faction, salt));
    }

    @Nullable
    private static CrewFacts.Faction parseFaction(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "ru" -> CrewFacts.Faction.RU;
            case "us" -> CrewFacts.Faction.US;
            case "pmc" -> CrewFacts.Faction.PMC;
            default -> null;
        };
    }

    /** Server-side mirror of {@code VehicleSkinRegistry.parseSkinId} (no client classpath). */
    @Nullable
    private static Parsed parseSkinId(@Nullable String skinId) {
        if (skinId == null || skinId.isBlank()) return null;
        String raw = skinId.toLowerCase(Locale.ROOT);
        int under = raw.lastIndexOf('_');
        if (under > 0 && under < raw.length() - 1) {
            String tail = raw.substring(under + 1);
            if (isAllDigits(tail)) {
                CrewFacts.Faction faction = parseFaction(raw.substring(0, under));
                if (faction == null) return null;
                try {
                    return new Parsed(faction, Integer.parseInt(tail));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        CrewFacts.Faction faction = parseFaction(raw);
        return faction == null ? null : new Parsed(faction, -1);
    }

    private static boolean isAllDigits(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    private record Parsed(CrewFacts.Faction faction, int variant) {
    }
}
