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
 * <p>The salt is rolled once on apply and stays sticky so every client picks the same pool member
 * via {@code salt % poolSize}. Plain {@code path_faction.png} files ignore it.
 */
public final class VehicleSkinSupport {

    public static final String TAG = "sewv:vehicle_skin";
    public static final String TAG_SALT = "sewv:vehicle_skin_salt";

    private VehicleSkinSupport() {
    }

    @Nullable
    public static CrewFacts.Faction get(VehicleEntity hull) {
        CompoundTag data = hull.getPersistentData();
        if (!data.contains(TAG)) return null;
        return parse(data.getString(TAG));
    }

    public static int getSalt(VehicleEntity hull) {
        CompoundTag data = hull.getPersistentData();
        return data.contains(TAG_SALT) ? data.getInt(TAG_SALT) : 0;
    }

    /** Idempotent: re-applying the same faction keeps the sticky salt (no re-roll). */
    public static void apply(VehicleEntity hull, @Nullable CrewFacts.Faction faction) {
        if (faction == null) return;
        if (faction == get(hull)) {
            // Keep SBW skinId aligned for the spray GUI selection highlight.
            hull.setSkinId(faction.name().toLowerCase(Locale.ROOT));
            return;
        }
        int salt = hull.getRandom().nextInt();
        String id = faction.name().toLowerCase(Locale.ROOT);
        hull.getPersistentData().putString(TAG, id);
        hull.getPersistentData().putInt(TAG_SALT, salt);
        hull.setSkinId(id);
        sync(hull, faction, salt);
    }

    /** Command/event crewed spawns — always paint the hull in the spawning faction's colours. */
    public static void applySpawnFaction(VehicleEntity hull, TankSpawner.TankFaction faction) {
        apply(hull, switch (faction) {
            case RU -> CrewFacts.Faction.RU;
            case US -> CrewFacts.Faction.US;
            case PMC -> CrewFacts.Faction.PMC;
        });
    }

    /** Set sticky paint, or clear to stock when {@code faction} is null. */
    public static void set(VehicleEntity hull, @Nullable CrewFacts.Faction faction) {
        if (faction == null) {
            clear(hull);
        } else {
            apply(hull, faction);
        }
    }

    public static void clear(VehicleEntity hull) {
        CompoundTag data = hull.getPersistentData();
        boolean hadSticky = data.contains(TAG) || data.contains(TAG_SALT);
        boolean hadSkinId = hull.getSkinId() != null && !hull.getSkinId().isBlank();
        if (!hadSticky && !hadSkinId) return;
        data.remove(TAG);
        data.remove(TAG_SALT);
        hull.setSkinId("");
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
    private static CrewFacts.Faction parse(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "ru" -> CrewFacts.Faction.RU;
            case "us" -> CrewFacts.Faction.US;
            case "pmc" -> CrewFacts.Faction.PMC;
            default -> null;
        };
    }
}
