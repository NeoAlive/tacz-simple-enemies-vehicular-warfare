package com.neoalive.tacz_sewv.compat.minecolonies;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.colonyEvents.EventStatus;
import com.minecolonies.api.colony.colonyEvents.IColonyRaidEvent;
import com.minecolonies.api.util.BlockPosUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.nekoyuni.SimpleEnemyMod.registry.ModEntities;
import org.jetbrains.annotations.NotNull;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.support.MarchObjective;
import com.neoalive.tacz_sewv.spawn.TankSpawner;
import com.neoalive.tacz_sewv.util.VehicleDrops;

/**
 * An armored raid on a colony: a handful of crewed hulls that spawn at MineColonies' own raid
 * spawn point and drive at the colony centre.
 *
 * <p><b>Why this implements the interface directly rather than extending {@code HordeRaidEvent}.</b>
 * That base class is the whole vanilla raid machinery — campfires, a boss bar, respawn-on-unload,
 * a {@code Horde} of counted raiders — and every one of its maps is typed
 * {@code AbstractEntityMinecoloniesRaider}. SEM units are not that and cannot be made into it, so
 * inheriting would mean overriding almost everything to do nothing. The interface itself is small:
 * most of {@code IColonyEntitySpawnEvent} has defaults.
 *
 * <p><b>Nothing here can use MineColonies' spawn pipeline.</b> {@code RaiderMobUtils.spawn} hard
 * casts to {@code AbstractEntityMinecoloniesRaider} and {@code EventManager.registerEntity}
 * discards anything that is not one, so the hulls are spawned through this mod's own
 * {@code TankSpawner} and tracked in this event's own set of UUIDs. UUIDs rather than entity
 * references because the event is serialised into the colony's save: a network id or a hard
 * reference would mean nothing after a reload, and the hulls can sit in unloaded chunks between
 * colony ticks.
 *
 * <p><b>The crews are steered by {@link MarchObjective}, not by a raider AI.</b> An RU/US crew has
 * no order queue and MineColonies' raider pathing is for its own entity class, so the objective is
 * written onto each hull and this mod's existing {@code VehicleTargeting.resolveDestination} does
 * the driving. A crew that meets a guard on the way fights it and then carries on.
 *
 * <p>Lifecycle mirrors {@code HordeRaidEvent} where it can: {@code onStart} spawns and moves to
 * {@code PROGRESSING}, {@code onUpdate} (every 500 ticks, from {@code EventManager.onColonyTick})
 * prunes the dead and ends when none are left, and {@code onNightFall} counts down a three-night
 * timeout so a raid whose hulls got stuck somewhere cannot last forever.
 *
 * <p>It deliberately does <b>not</b> call {@code IRaiderManager.onRaidEventFinished}. That method
 * reads {@code raidHistories.get(0)} unguarded, and the history entry is written inside the part
 * of {@code raiderEvent} this raid replaces — so calling it would throw on the first raid of a
 * world. The one piece of bookkeeping that actually paces the next raid,
 * {@code setNightsSinceLastRaid(0)}, is done in {@link #onUpdate} exactly as the vanilla horde
 * does it.
 */
public class SewvArmoredRaidEvent implements IColonyRaidEvent {

    public static final ResourceLocation TYPE_ID =
            // The minecolonies namespace is not a mistake: EventManager.readFromNBT rebuilds the
            // type id as new ResourceLocation(MOD_ID, storedPath), hardcoding its own namespace,
            // so an event type registered under any other one could never be deserialised.
            new ResourceLocation("minecolonies", "sewv_armored_raid");

    private static final String TAG_ID = "mc_event_id";
    private static final String TAG_STATUS = "eventStatus";
    private static final String TAG_SPAWN = "spawnPos";
    private static final String TAG_DAYS = "daysToGo";
    private static final String TAG_FACTION = "sewvFaction";
    private static final String TAG_HULLS = "sewvHulls";

    /** Same three-night ceiling the vanilla horde uses, for the same reason. */
    private static final int DEFAULT_DAYS_TO_GO = 3;

    private IColony colony;
    private int id;
    private BlockPos spawnPoint = BlockPos.ZERO;
    private EventStatus status = EventStatus.STARTING;
    private int daysToGo = DEFAULT_DAYS_TO_GO;
    private TankSpawner.TankFaction faction = TankSpawner.TankFaction.RU;
    private final Set<UUID> hulls = new LinkedHashSet<>();

    public SewvArmoredRaidEvent(@NotNull IColony colony) {
        this.colony = colony;
        this.id = colony.getEventManager().getAndTakeNextEventID();
    }

    /** Deserialisation constructor for the registry entry — see {@link #loadFromNBT}. */
    private SewvArmoredRaidEvent(@NotNull IColony colony, @NotNull CompoundTag compound) {
        this.colony = colony;
        deserializeNBT(compound);
    }

    public static SewvArmoredRaidEvent loadFromNBT(@NotNull IColony colony, @NotNull CompoundTag compound) {
        return new SewvArmoredRaidEvent(colony, compound);
    }

    public void setFaction(TankSpawner.TankFaction faction) {
        this.faction = faction;
    }

    /**
     * How many hulls this raid fields, from the colony's own raid size. MineColonies counts
     * individual raiders and a colony that would face thirty barbarians must not face thirty
     * tanks, so the count is scaled right down and then capped by config.
     */
    public static int vehicleCountFor(int raiderAmount) {
        int scaled = Math.max(1, raiderAmount / 8);
        return Math.min(scaled, SewvConfig.MINECOLONIES_RAID_MAX_VEHICLES.get());
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onStart() {
        if (!(colony.getWorld() instanceof ServerLevel level)) {
            status = EventStatus.CANCELED;
            return;
        }

        int count = vehicleCountFor(colony.getRaiderManager().calculateRaiderAmount(colony.getRaiderManager().getColonyRaidLevel()));
        BlockPos objective = colony.getCenter();

        for (int i = 0; i < count; i++) {
            // Fan the hulls out a little so they do not spawn inside one another; TankSpawner's
            // own clear-spawn search handles the rest.
            BlockPos at = TankSpawner.adjustHeight(level, spawnPoint.offset((i % 3) * 4 - 4, 0, (i / 3) * 4));
            // The ground pool specifically, not spawnCombatVehicleWithCrew's ground-or-rotary
            // pick: the march below is read by the ground crew's destination resolver, and a
            // helicopter — which has its own flight goal and never consults it — would take off
            // and loiter over its spawn instead of raiding anything.
            VehicleEntity hull = TankSpawner.spawnTankWithCrew(level, at, faction, null);
            if (hull == null) continue;
            VehicleDrops.markCrewAndHull(hull);
            MarchObjective.set(hull, objective);
            hulls.add(hull.getUUID());
        }

        if (hulls.isEmpty()) {
            // Nothing fielded (empty pool, no room) — cancel rather than sit as a raid that never
            // arrives, which would keep isRaided() true and block the colony's next raid.
            TaczSewv.LOGGER.debug("MineColonies armored raid fielded no vehicles at {} — cancelled", spawnPoint);
            status = EventStatus.CANCELED;
            return;
        }

        status = EventStatus.PROGRESSING;
    }

    @Override
    public void onUpdate() {
        // Same as the vanilla horde: while a raid is running the colony must not count nights
        // toward the next one.
        colony.getRaiderManager().setNightsSinceLastRaid(0);

        if (!(colony.getWorld() instanceof ServerLevel level)) return;

        // Forget a hull only once it is resolvable and dead. A hull in an unloaded chunk also
        // resolves to null, so treating null as dead would end the raid the moment the players
        // walked away from it; the night countdown is what stops one lasting forever instead.
        hulls.removeIf(uuid -> {
            Entity entity = level.getEntity(uuid);
            if (entity == null) return false;
            return !entity.isAlive() || (entity instanceof VehicleEntity hull && hull.isWreck());
        });

        if (hulls.isEmpty()) {
            status = EventStatus.DONE;
        }
    }

    @Override
    public void onNightFall() {
        if (--daysToGo < 0) {
            status = EventStatus.DONE;
        }
    }

    @Override
    public void onFinish() {
        for (Entity entity : getEntities()) {
            if (entity instanceof VehicleEntity hull) MarchObjective.clear(hull);
            entity.remove(Entity.RemovalReason.DISCARDED);
        }
        hulls.clear();
    }

    // ---------------------------------------------------------------- entities

    @Override
    public List<Entity> getEntities() {
        if (!(colony.getWorld() instanceof ServerLevel level)) return List.of();
        List<Entity> found = new ArrayList<>(hulls.size());
        for (UUID uuid : hulls) {
            Entity entity = level.getEntity(uuid);
            if (entity != null) found.add(entity);
        }
        return found;
    }

    /**
     * The hulls' current positions, which MineColonies hands its guards as approach hints. Not a
     * plotted path like the ship raids build — an armored raid drives where its crews decide, so
     * the honest answer is where they actually are.
     */
    @Override
    public List<BlockPos> getWayPoints() {
        List<BlockPos> points = new ArrayList<>();
        for (Entity entity : getEntities()) points.add(entity.blockPosition());
        return points;
    }

    /** No structure to place, so no spawner block to register — the ship raids' concern, not ours. */
    @Override
    public void addSpawner(BlockPos pos) {}

    // The raider types MineColonies asks about are its own notion of a horde's make-up; this raid
    // has crews, not archers and bosses, so all three answer with the faction's line unit. They
    // are read for messaging and tab-completion, never to spawn anything here.
    @Override
    public EntityType<?> getNormalRaiderType() {
        return faction == TankSpawner.TankFaction.US ? ModEntities.USUNIT.get() : ModEntities.RUUNIT.get();
    }

    @Override
    public EntityType<?> getArcherRaiderType() {
        return getNormalRaiderType();
    }

    @Override
    public EntityType<?> getBossRaiderType() {
        return getNormalRaiderType();
    }

    // ---------------------------------------------------------------- plumbing

    @Override
    public EventStatus getStatus() {
        return status;
    }

    @Override
    public void setStatus(EventStatus status) {
        this.status = status;
    }

    @Override
    public int getID() {
        return id;
    }

    @Override
    public ResourceLocation getEventTypeID() {
        return TYPE_ID;
    }

    @Override
    public void setColony(@NotNull IColony colony) {
        this.colony = colony;
    }

    @Override
    public void setSpawnPoint(BlockPos spawnPoint) {
        this.spawnPoint = spawnPoint;
    }

    @Override
    public BlockPos getSpawnPos() {
        return spawnPoint;
    }

    @Override
    public CompoundTag serializeNBT() {
        CompoundTag compound = new CompoundTag();
        compound.putInt(TAG_ID, id);
        compound.putInt(TAG_STATUS, status.ordinal());
        compound.putInt(TAG_DAYS, daysToGo);
        compound.putString(TAG_FACTION, faction.name());
        BlockPosUtil.write(compound, TAG_SPAWN, spawnPoint);

        ListTag hullList = new ListTag();
        for (UUID uuid : hulls) hullList.add(NbtUtils.createUUID(uuid));
        compound.put(TAG_HULLS, hullList);
        return compound;
    }

    @Override
    public void deserializeNBT(CompoundTag compound) {
        id = compound.getInt(TAG_ID);
        status = EventStatus.values()[compound.getInt(TAG_STATUS)];
        daysToGo = compound.getInt(TAG_DAYS);
        spawnPoint = BlockPosUtil.read(compound, TAG_SPAWN);
        try {
            faction = TankSpawner.TankFaction.valueOf(compound.getString(TAG_FACTION));
        } catch (IllegalArgumentException e) {
            faction = TankSpawner.TankFaction.RU;
        }

        hulls.clear();
        for (Tag tag : compound.getList(TAG_HULLS, Tag.TAG_INT_ARRAY)) {
            hulls.add(NbtUtils.loadUUID(tag));
        }
    }
}
