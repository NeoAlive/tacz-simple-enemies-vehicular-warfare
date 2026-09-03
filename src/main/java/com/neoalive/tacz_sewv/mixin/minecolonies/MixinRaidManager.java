package com.neoalive.tacz_sewv.mixin.minecolonies;

import java.util.List;

import com.minecolonies.api.colony.managers.interfaces.IRaiderManager;
import com.minecolonies.core.colony.Colony;
import com.minecolonies.core.colony.events.raid.RaidManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.neoalive.tacz_sewv.compat.MineColoniesCompat;
import com.neoalive.tacz_sewv.compat.minecolonies.SewvArmoredRaidEvent;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.spawn.TankSpawner;

/**
 * Turns some of a colony's raid nights into an armored assault.
 *
 * <p>Riding MineColonies' own scheduler is the whole point: everything that makes a raid feel
 * timed — the nights-between-raids pacing, the difficulty that tracks how the last raid went, the
 * spawn-point search that finds somewhere outside the walls, the player messaging — belongs to
 * {@code RaidManager} and is reused here rather than rebuilt. This mod's own procedural events
 * fire on SEM's flat 60-second roll and have no idea a colony exists.
 *
 * <p>Injecting into {@code raiderEvent} rather than registering a raid type is not a shortcut.
 * {@code minecolonies:colonyeventtypes} looks like the seam but is not one: {@code raiderEvent} is
 * a hardcoded if/else over biome and {@code raidSettings.raidType()} that ends in
 * {@code NO_SPAWN_POINT}, and never consults the registry to decide what to spawn. The registry
 * buys persistence and nothing else — see {@code MineColoniesCompat.Access.registerEventType}.
 *
 * <p><b>Every bail-out falls through to vanilla rather than answering itself.</b> A miss on the
 * chance roll, an unusable spawn point, a colony too small to raid — all of them simply return
 * without cancelling, and MineColonies runs its own chain and produces its own {@code
 * RaidSpawnResult}. That keeps this mixin from having to be right about MineColonies' answers,
 * which is also why the roll happens before the expensive checks: a 75% miss costs one
 * {@code nextDouble}.
 *
 * <p>Two settings are deliberately left to MineColonies. An explicit {@code raidType} means
 * somebody named the raid they wanted (a command, a ship raid), so it is never hijacked; and
 * {@code canRaid()} is asked in the same order vanilla asks it, so peaceful difficulty and the
 * colony's own raid toggle keep working untouched.
 *
 * <p>The bookkeeping duplicated below is deliberately the minimum: the history entry (so the
 * colony's difficulty scaling counts this raid like any other) and {@code nightsSinceLastRaid},
 * which is what paces the next one. {@code nextRaid} and {@code extraDaysToNextRaid} are cleared
 * by {@code onNightFall} itself on a {@code SUCCESS}, so touching them here would be duplicating
 * the caller. This is the version-fragile part of the compat, which is why the config is
 * {@code required: false} and the mixin is gated by {@code MineColoniesMixinPlugin}.
 */
@Mixin(RaidManager.class)
public abstract class MixinRaidManager {

    @Shadow(remap = false)
    @Final
    private Colony colony;

    @Shadow(remap = false)
    private int nightsSinceLastRaid;

    @Shadow(remap = false)
    @Final
    private List<RaidManager.RaidHistory> raidHistories;

    @Inject(
            method = "raiderEvent",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void tacz_sewv$armoredRaid(IRaiderManager.RaidSettings raidSettings,
                                       CallbackInfoReturnable<IRaiderManager.RaidSpawnResult> cir) {
        if (!MineColoniesCompat.present()) return;
        double chance = SewvConfig.MINECOLONIES_RAID_CHANCE.get();
        if (chance <= 0.0) return;
        // Somebody asked for a specific raid; that request is not ours to answer.
        if (raidSettings.raidType() != null) return;
        if (!(colony.getWorld() instanceof ServerLevel level)) return;
        if (level.random.nextDouble() >= chance) return;

        IRaiderManager manager = (IRaiderManager) (Object) this;
        if (!raidSettings.forcedSpawn() && !manager.canRaid()) return;

        int amount = raidSettings.raiderAmount() != null
                ? raidSettings.raiderAmount()
                : manager.calculateRaiderAmount(manager.getColonyRaidLevel());
        if (amount <= 0) return;

        // One spawn point, never the split into several that a big vanilla horde gets: a handful
        // of hulls converging from one direction is a raid, and the same handful divided by three
        // is three lone tanks.
        BlockPos spawnPoint = raidSettings.location() != null
                ? raidSettings.location()
                : manager.calculateSpawnLocation();
        if (spawnPoint == null
                || spawnPoint.equals(colony.getCenter())
                || !level.getWorldBorder().isWithinBounds(spawnPoint)) {
            return;
        }

        // Decided here rather than in the event so an empty vehicle pool falls through to a
        // vanilla raid, instead of the colony spending a raid night on an event that will cancel
        // itself the moment it tries to spawn.
        TankSpawner.TankFaction faction = level.random.nextBoolean()
                ? TankSpawner.TankFaction.RU : TankSpawner.TankFaction.US;
        if (!TankSpawner.hasSpawnableVehicle(level, faction)) {
            faction = faction == TankSpawner.TankFaction.RU
                    ? TankSpawner.TankFaction.US : TankSpawner.TankFaction.RU;
            if (!TankSpawner.hasSpawnableVehicle(level, faction)) return;
        }

        SewvArmoredRaidEvent event = new SewvArmoredRaidEvent(colony);
        event.setSpawnPoint(spawnPoint);
        event.setFaction(faction);

        RaidManager.RaidHistory history = new RaidManager.RaidHistory(amount, level.getGameTime());
        history.spawnData.add(new RaidManager.RaidSpawnInfo(SewvArmoredRaidEvent.TYPE_ID, spawnPoint));
        raidHistories.add(history);
        nightsSinceLastRaid = 0;

        colony.getEventManager().addEvent(event);
        colony.markDirty();
        cir.setReturnValue(IRaiderManager.RaidSpawnResult.SUCCESS);
    }
}
