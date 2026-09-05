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
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;

import com.neoalive.tacz_sewv.crew.CrewFacts;
import com.neoalive.tacz_sewv.crew.NpcIdentity;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleWeapons;
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

    // --- Ammo / engage / energy -----------------------------------------------------------

    private static final String TAG_AMMO_OUT = "sewv:ammo_out_notified";

    /** Clears the ammo-out rising-edge latch (e.g. after a mortar restocks). */
    public static void clearAmmoOut(Entity host) {
        if (host != null) host.getPersistentData().putBoolean(TAG_AMMO_OUT, false);
    }

    private static final String TAG_ENERGY_LOW = "sewv:energy_low_notified";
    private static final String TAG_VEHICLE_WATCH_AT = "sewv:hud_vehicle_watch_at";
    private static final String TAG_INFANTRY_WATCH_AT = "sewv:hud_infantry_watch_at";
    private static final String TAG_ENGAGE_CD = "sewv:engage_notify_cd";
    private static final long VEHICLE_WATCH_INTERVAL = 20L;
    private static final long INFANTRY_WATCH_INTERVAL = 40L;
    private static final long ENGAGE_UNIT_CD_TICKS = 300L; // 15s
    private static final long ENGAGE_OWNER_CD_TICKS = 100L; // 5s squad throttle
    private static final long ENGAGE_GLOBAL_CD_MS = 8000L;
    private static final float ENERGY_LOW = 0.10F;
    private static final float ENERGY_CLEAR = 0.15F;
    private static final java.util.concurrent.ConcurrentHashMap<UUID, Long> OWNER_ENGAGE_CD =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile long lastEngageNotifyMs;

    /**
     * Driver-only rising-edge watch for ammo-out and energy&lt;10% on a PMC-crewed hull.
     * Throttled to once per second of game time per hull.
     */
    public static void watchPmcVehicle(AbstractUnit unit, VehicleEntity hull) {
        if (hull == null || hull.level().isClientSide()) return;
        if (!(unit instanceof PmcUnitEntity pmc) || pmc.getOwnerUUID() == null) return;
        if (hull.getFirstPassenger() != unit) return;

        long now = hull.level().getGameTime();
        var data = hull.getPersistentData();
        if (now < data.getLong(TAG_VEHICLE_WATCH_AT)) return;
        data.putLong(TAG_VEHICLE_WATCH_AT, now + VEHICLE_WATCH_INTERVAL);

        watchEnergy(pmc, hull, data);
        int seat = hull.getSeatIndex(unit);
        boolean dry = seat >= 0 && VehicleWeapons.isSelectedWeaponDry(hull, seat);
        risingAmmoOut(pmc, hull, dry, vehicleKindLabel(hull), data);
    }

    /** Rising-edge ammo-out for on-foot PMC TACZ guns. */
    public static void watchPmcInfantryAmmo(PmcUnitEntity pmc) {
        if (pmc.level().isClientSide() || pmc.getOwnerUUID() == null) return;
        if (pmc.getVehicle() instanceof VehicleEntity) return;

        long now = pmc.level().getGameTime();
        var data = pmc.getPersistentData();
        if (now < data.getLong(TAG_INFANTRY_WATCH_AT)) return;
        data.putLong(TAG_INFANTRY_WATCH_AT, now + INFANTRY_WATCH_INTERVAL);

        risingAmmoOut(pmc, pmc, isInfantryTaczDry(pmc), kindLabel(infantryKind(pmc)), data);
    }

    /** Emplacement / mortar / TOW dry — call once when a load attempt fails. */
    public static void pmcAmmoOut(PmcUnitEntity pmc, Component kind) {
        if (pmc == null || pmc.getOwnerUUID() == null) return;
        risingAmmoOut(pmc, pmc, true, kind, pmc.getPersistentData());
    }

    public static void pmcAmmoOut(PmcUnitEntity pmc, VehicleEntity weapon, Component kind) {
        if (pmc == null || pmc.getOwnerUUID() == null || weapon == null) return;
        risingAmmoOut(pmc, weapon, true, kind, weapon.getPersistentData());
    }

    /**
     * Owned PMC acquired a hostile target. Only fires for players, SEM units, or contacts
     * in/as a vehicle — zombies and other noise targets are ignored. Per-unit, per-owner,
     * and global cooldowns further suppress retarget spam. Driver-only when mounted.
     */
    public static void pmcEngaging(PmcUnitEntity pmc, LivingEntity target) {
        if (pmc == null || target == null || !target.isAlive()) return;
        if (pmc.getOwnerUUID() == null) return;
        if (pmc.getVehicle() instanceof VehicleEntity hull && hull.getFirstPassenger() != pmc) return;
        if (VehicleTargeting.isFriendly(pmc, target) || VehicleTargeting.isMedic(target)) return;
        // Combat-relevant contacts only — not Monster / animals / misc LivingEntity noise.
        if (!(target instanceof Player
                || target instanceof AbstractUnit
                || target.getVehicle() instanceof VehicleEntity)) {
            return;
        }

        long now = pmc.level().getGameTime();
        var data = pmc.getPersistentData();
        if (now < data.getLong(TAG_ENGAGE_CD)) return;
        Long ownerCd = OWNER_ENGAGE_CD.get(pmc.getOwnerUUID());
        if (ownerCd != null && now < ownerCd) return;
        long wallNow = System.currentTimeMillis();
        if (wallNow - lastEngageNotifyMs < ENGAGE_GLOBAL_CD_MS) return;

        ServerPlayer owner = ownerOf(pmc);
        if (owner == null) return;

        data.putLong(TAG_ENGAGE_CD, now + ENGAGE_UNIT_CD_TICKS);
        OWNER_ENGAGE_CD.put(pmc.getOwnerUUID(), now + ENGAGE_OWNER_CD_TICKS);
        lastEngageNotifyMs = wallNow;

        String name = unitName(pmc);
        Component kind = pmc.getVehicle() instanceof VehicleEntity hull
                ? vehicleKindLabel(hull) : kindLabel(infantryKind(pmc));
        Component contact = entityLabel(target);
        if (contact == null) contact = Component.translatable("notification.tacz_sewv.faction.unknown");
        BlockPos pos = pmc.blockPosition();
        send(owner,
                Component.translatable("notification.tacz_sewv.pmc_engaging.title", name, contact),
                Component.translatable("notification.tacz_sewv.pmc_engaging.body",
                        kind, name, contact, pos.getX(), pos.getY(), pos.getZ()));
    }

    private static void watchEnergy(PmcUnitEntity pmc, VehicleEntity hull,
                                    net.minecraft.nbt.CompoundTag data) {
        float frac = energyFraction(hull);
        if (Float.isNaN(frac)) {
            data.remove(TAG_ENERGY_LOW);
            return;
        }
        if (frac >= ENERGY_CLEAR) {
            data.putBoolean(TAG_ENERGY_LOW, false);
            return;
        }
        if (frac >= ENERGY_LOW) return;
        if (data.getBoolean(TAG_ENERGY_LOW)) return;

        ServerPlayer owner = ownerOf(pmc);
        if (owner == null) return;
        data.putBoolean(TAG_ENERGY_LOW, true);

        Component kind = vehicleKindLabel(hull);
        BlockPos pos = hull.blockPosition();
        int pct = Math.max(0, Math.round(frac * 100f));
        send(owner,
                Component.translatable("notification.tacz_sewv.pmc_energy_low.title", kind),
                Component.translatable("notification.tacz_sewv.pmc_energy_low.body",
                        kind, pct, pos.getX(), pos.getY(), pos.getZ()));
    }

    private static void risingAmmoOut(PmcUnitEntity pmc, Entity flagHost, boolean dry,
                                      Component kind, net.minecraft.nbt.CompoundTag data) {
        if (!dry) {
            data.putBoolean(TAG_AMMO_OUT, false);
            return;
        }
        if (data.getBoolean(TAG_AMMO_OUT)) return;

        ServerPlayer owner = ownerOf(pmc);
        if (owner == null) return;
        data.putBoolean(TAG_AMMO_OUT, true);

        String name = unitName(pmc);
        BlockPos pos = flagHost.blockPosition();
        send(owner,
                Component.translatable("notification.tacz_sewv.pmc_ammo_out.title", name),
                Component.translatable("notification.tacz_sewv.pmc_ammo_out.body",
                        kind, name, pos.getX(), pos.getY(), pos.getZ()));
    }

    /** NaN when the hull has no energy storage. */
    private static float energyFraction(VehicleEntity hull) {
        try {
            if (!hull.hasEnergyStorage()) return Float.NaN;
            int max = hull.getMaxEnergy();
            if (max <= 0 || max == Integer.MAX_VALUE) return Float.NaN;
            return Mth.clamp(hull.getEnergy() / (float) max, 0.0F, 1.0F);
        } catch (Throwable ignored) {
            return Float.NaN;
        }
    }

    private static boolean isInfantryTaczDry(PmcUnitEntity pmc) {
        return isTaczStackDry(pmc, pmc.getMainHandItem())
                || isTaczStackDry(pmc, pmc.getOffhandItem());
    }

    private static boolean isTaczStackDry(PmcUnitEntity pmc, net.minecraft.world.item.ItemStack stack) {
        com.tacz.guns.api.item.IGun gun = com.tacz.guns.api.item.IGun.getIGunOrNull(stack);
        if (gun == null || gun.useDummyAmmo(stack)) return false;
        if (gun.getCurrentAmmoCount(stack) > 0) return false;
        return !gun.hasInventoryAmmo(pmc, stack, false);
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
