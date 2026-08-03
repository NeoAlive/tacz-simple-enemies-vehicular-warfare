package com.neoalive.tacz_sewv.util;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketVehicleSkin;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * Sticky faction paint on a hull ({@code sewv:vehicle_skin}). Server never opens PNGs — clients
 * resolve the texture from {@link com.neoalive.tacz_sewv.client.VehicleSkinRegistry}.
 */
public final class VehicleSkinSupport {

    public static final String TAG = "sewv:vehicle_skin";

    private VehicleSkinSupport() {
    }

    @Nullable
    public static CrewFacts.Faction get(VehicleEntity hull) {
        CompoundTag data = hull.getPersistentData();
        if (!data.contains(TAG)) return null;
        return parse(data.getString(TAG));
    }

    /** Idempotent: re-applying the same faction is a no-op. */
    public static void apply(VehicleEntity hull, @Nullable CrewFacts.Faction faction) {
        if (faction == null) return;
        if (faction == get(hull)) return;
        hull.getPersistentData().putString(TAG, faction.name().toLowerCase(Locale.ROOT));
        sync(hull, faction);
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
        if (!data.contains(TAG)) return;
        data.remove(TAG);
        sync(hull, null);
    }

    public static void syncTo(ServerPlayer player, VehicleEntity hull) {
        NetworkHandler.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new PacketVehicleSkin(hull.getId(), get(hull)));
    }

    private static void sync(VehicleEntity hull, @Nullable CrewFacts.Faction faction) {
        if (!(hull.level() instanceof ServerLevel)) return;
        NetworkHandler.CHANNEL.send(
                PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> hull),
                new PacketVehicleSkin(hull.getId(), faction));
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
