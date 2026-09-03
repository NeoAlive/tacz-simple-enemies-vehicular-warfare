package com.neoalive.tacz_sewv.notify;

import java.util.UUID;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType;
import com.atsuishio.superbwarfare.entity.vehicle.DroneEntity;
import com.atsuishio.superbwarfare.entity.vehicle.TowEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;

import com.neoalive.tacz_sewv.crew.CrewFacts;
import com.neoalive.tacz_sewv.crew.NpcIdentity;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.support.SupportRole;
import com.neoalive.tacz_sewv.entity.unit.PmcCommanderEntity;
import com.neoalive.tacz_sewv.entity.unit.RuCombatEngineerEntity;
import com.neoalive.tacz_sewv.entity.unit.UsCombatEngineerEntity;
import com.neoalive.tacz_sewv.fob.FobSupport;
import com.neoalive.tacz_sewv.map.VehicleMarker;
import com.neoalive.tacz_sewv.network.PacketHudNotification;

/**
 * Server-side HUD toast fan-out. Call sites hand a victim/source; this resolves owner, labels,
 * and {@link PacketHudNotification}.
 */
public final class HudNotify {

    private HudNotify() {}

    public static void pmcDowned(PmcUnitEntity pmc, DamageSource source) {
        ServerPlayer owner = ownerOf(pmc);
        if (owner == null) return;
        String name = unitName(pmc);
        Component kind = kindLabel(infantryKind(pmc));
        Component killer = killerLabel(source);
        BlockPos pos = pmc.blockPosition();
        if (killer != null) {
            send(owner,
                    Component.translatable("notification.tacz_sewv.pmc_downed.title_by", name, killer),
                    Component.translatable("notification.tacz_sewv.pmc_downed.body_by",
                            kind, name, pos.getX(), pos.getY(), pos.getZ(), killer));
        } else {
            send(owner,
                    Component.translatable("notification.tacz_sewv.pmc_downed.title", name),
                    Component.translatable("notification.tacz_sewv.pmc_downed.body",
                            kind, name, pos.getX(), pos.getY(), pos.getZ()));
        }
    }

    public static void pmcKilled(PmcUnitEntity pmc, DamageSource source) {
        // Crew death while still seated is covered by the hull toast — avoid a double notify.
        if (pmc.getVehicle() instanceof VehicleEntity) return;
        ServerPlayer owner = ownerOf(pmc);
        if (owner == null) return;
        String name = unitName(pmc);
        Component kind = kindLabel(infantryKind(pmc));
        Component killer = killerLabel(source);
        BlockPos pos = pmc.blockPosition();
        if (killer != null) {
            send(owner,
                    Component.translatable("notification.tacz_sewv.pmc_killed.title_by", name, killer),
                    Component.translatable("notification.tacz_sewv.pmc_killed.body_by",
                            kind, name, pos.getX(), pos.getY(), pos.getZ(), killer));
        } else {
            send(owner,
                    Component.translatable("notification.tacz_sewv.pmc_killed.title", name),
                    Component.translatable("notification.tacz_sewv.pmc_killed.body",
                            kind, name, pos.getX(), pos.getY(), pos.getZ()));
        }
    }

    public static void vehicleDestroyed(VehicleEntity hull) {
        if (hull.level().isClientSide()) return;
        ServerPlayer owner = ownerOfHull(hull);
        if (owner == null) return;
        Component kind = vehicleKindLabel(hull);
        Component killer = killerLabel(hull.getLastDamageSource());
        if (killer == null) killer = entityLabel(hull.getLastAttacker());
        BlockPos pos = hull.blockPosition();
        if (killer != null) {
            send(owner,
                    Component.translatable("notification.tacz_sewv.vehicle_destroyed.title_by", kind, killer),
                    Component.translatable("notification.tacz_sewv.vehicle_destroyed.body_by",
                            kind, pos.getX(), pos.getY(), pos.getZ(), killer));
        } else {
            send(owner,
                    Component.translatable("notification.tacz_sewv.vehicle_destroyed.title", kind),
                    Component.translatable("notification.tacz_sewv.vehicle_destroyed.body",
                            kind, pos.getX(), pos.getY(), pos.getZ()));
        }
    }

    public static void eventNearby(ServerPlayer player, String eventId, BlockPos pos) {
        if (player == null || eventId == null || eventId.isEmpty()) return;
        Component eventName = Component.translatable("notification.tacz_sewv.event.name." + eventId);
        send(player,
                Component.translatable("notification.tacz_sewv.event.title", eventName),
                Component.translatable("notification.tacz_sewv.event.body",
                        eventName, pos.getX(), pos.getY(), pos.getZ()));
    }

    public static void medicCaptured(AbstractUnit medic, DamageSource source) {
        ServerPlayer notify = notifyPlayerFromSource(source);
        if (notify == null) return;
        Component faction = factionLabel(CrewFacts.factionOfCrew(medic));
        BlockPos pos = medic.blockPosition();
        send(notify,
                Component.translatable("notification.tacz_sewv.medic_captured.title", faction),
                Component.translatable("notification.tacz_sewv.medic_captured.body",
                        faction, pos.getX(), pos.getY(), pos.getZ()));
    }

    private static void send(ServerPlayer player, Component title, Component body) {
        PacketHudNotification.sendTo(player, title, body);
    }

    @Nullable
    private static ServerPlayer ownerOf(PmcUnitEntity pmc) {
        UUID id = pmc.getOwnerUUID();
        if (id == null || !(pmc.level() instanceof ServerLevel level)) return null;
        return level.getServer().getPlayerList().getPlayer(id);
    }

    @Nullable
    private static ServerPlayer ownerOfHull(VehicleEntity hull) {
        UUID id = CrewFacts.pmcOwner(hull);
        if (id == null) id = FobSupport.playerOwnerOfEmptyHull(hull);
        if (id == null || !(hull.level() instanceof ServerLevel level)) return null;
        return level.getServer().getPlayerList().getPlayer(id);
    }

    @Nullable
    private static ServerPlayer notifyPlayerFromSource(DamageSource source) {
        Entity e = source != null ? source.getEntity() : null;
        if (e instanceof ServerPlayer sp) return sp;
        if (e instanceof PmcUnitEntity pmc) return ownerOf(pmc);
        return null;
    }

    private static String unitName(PmcUnitEntity pmc) {
        String full = NpcIdentity.fullName(pmc);
        if (full != null && !full.isBlank()) return full.strip();
        if (pmc.getCustomName() != null) return pmc.getCustomName().getString();
        return pmc.getName().getString();
    }

    @Nullable
    private static Component killerLabel(DamageSource source) {
        if (source == null) return null;
        Entity e = source.getEntity();
        if (e == null) e = source.getDirectEntity();
        return entityLabel(e);
    }

    @Nullable
    private static Component entityLabel(@Nullable Entity e) {
        if (e == null) return null;
        if (e instanceof Player p) return Component.literal(p.getGameProfile().getName());
        if (e instanceof PmcUnitEntity pmc) {
            return Component.translatable("notification.tacz_sewv.killer.pmc", unitName(pmc));
        }
        if (e instanceof RUunitEntity) {
            return Component.translatable("notification.tacz_sewv.killer.ru");
        }
        if (e instanceof USunitEntity) {
            return Component.translatable("notification.tacz_sewv.killer.us");
        }
        if (e instanceof VehicleEntity) {
            return Component.translatable("notification.tacz_sewv.killer.vehicle");
        }
        return e.getDisplayName();
    }

    private static Component factionLabel(@Nullable CrewFacts.Faction faction) {
        if (faction == null) {
            return Component.translatable("notification.tacz_sewv.faction.unknown");
        }
        return Component.translatable("notification.tacz_sewv.faction." + faction.name().toLowerCase());
    }

    private static Component kindLabel(VehicleMarker.Kind kind) {
        return Component.translatable("notification.tacz_sewv.kind." + kind.name().toLowerCase());
    }

    private static Component vehicleKindLabel(VehicleEntity hull) {
        if (hull instanceof TowEntity) {
            return Component.translatable("notification.tacz_sewv.kind.tow");
        }
        return kindLabel(vehicleKind(hull));
    }

    private static VehicleMarker.Kind infantryKind(AbstractUnit unit) {
        if (unit instanceof PmcCommanderEntity) return VehicleMarker.Kind.INFANTRY_COMMANDER;
        SupportRole role = SupportRole.of(unit);
        if (VehicleTargeting.isMedic(unit) || role == SupportRole.MEDIC) {
            return VehicleMarker.Kind.INFANTRY_MEDIC;
        }
        if (unit instanceof RuCombatEngineerEntity || unit instanceof UsCombatEngineerEntity
                || role == SupportRole.COMBAT_ENGINEER) {
            return VehicleMarker.Kind.INFANTRY_COMBAT_ENGINEER;
        }
        if (VehicleTargeting.isEngineer(unit) || role == SupportRole.ENGINEER) {
            return VehicleMarker.Kind.INFANTRY_ENGINEER;
        }
        return VehicleMarker.Kind.INFANTRY;
    }

    private static VehicleMarker.Kind vehicleKind(VehicleEntity hull) {
        if (hull instanceof DroneEntity) return VehicleMarker.Kind.DRONE;
        EngineType engine;
        try {
            engine = hull.computed().getEngineType();
        } catch (Throwable ignored) {
            engine = null;
        }
        if (engine == EngineType.SHIP) return VehicleMarker.Kind.SURFACE_COMBATANT;
        if (engine == EngineType.AIRCRAFT) return VehicleMarker.Kind.FIXED_WING;
        if (engine == EngineType.HELICOPTER) return VehicleMarker.Kind.ROTARY_WING;
        if (HullFacts.isArtilleryHull(hull)) return VehicleMarker.Kind.ARTILLERY;
        if (engine == EngineType.FIXED) return VehicleMarker.Kind.EMPLACEMENT;
        if (HullFacts.isMissileSystemHull(hull)) return VehicleMarker.Kind.MISSILE_SYSTEM;
        if (HullFacts.isAntiAirHull(hull)) return VehicleMarker.Kind.ANTI_AIR;
        return HullFacts.isIfvHull(hull) ? VehicleMarker.Kind.MECHANIZED : VehicleMarker.Kind.ARMOR;
    }
}
