package com.neoalive.tacz_sewv.mixin;

import java.util.Random;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.nekoyuni.SimpleEnemyMod.event.common.VillageGarrisonHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.spawn.AmbientSpawnGate;
import com.neoalive.tacz_sewv.spawn.TankSpawner;

/**
 * Gives a share of SimpleEnemyMod's village garrisons a single crewed tank of the garrison's own
 * faction.
 *
 * <h2>Why only a crewed tank, never a parked one</h2>
 * A bare hull near a garrison is boarded by its own idle infantry within seconds
 * ({@code SeekAbandonedVehicleGoal} makes any empty, undamaged, unlocked vehicle a magnet). An
 * earlier version of this feature spawned a parked hull and was removed for exactly that. A crewed
 * hull is occupied, so nothing scavenges it, and {@code MixinVehicleFactionEnergy} keeps a crewed
 * RU/US hull fuelled on its own. So the safe garrison vehicle is the crewed one, full stop.
 *
 * <h2>Why {@code spawnGuard} HEAD, and how one-per-village 50% comes out of a per-guard injection</h2>
 * {@code VillageGarrisonHandler.onLevelTick} decides the garrison, but the faction coin-flip and the
 * position are loop-locals there. {@code spawnGuard(ServerLevel, BlockPos, boolean isRu)} is the only
 * place both arrive together — and SEM calls it 2-4 times per village with an <b>identical</b>
 * {@code basePos} (the villager's position; the ±jitter is applied inside spawnGuard) and one
 * {@code isRu} fixed before the loop.
 *
 * <p>So the roll is <b>seeded on {@code basePos}</b>: every guard of the same garrison computes the
 * same result, giving a true per-village chance rather than a per-guard one (which with 2-4 guards
 * would compound to 75-94%). The "already has a vehicle" guard then makes the first passing guard the
 * only one that spawns — subsequent guards see the hull and bail — and also matches SEM's own 40-block
 * garrison-dedupe radius, so a second nearby villager can't stack another tank on the same village.
 *
 * <p>Faction is SEM's own {@code isRu} for this garrison, not a majority scan of nearby infantry:
 * that scan could pick up a neighbouring opposite-faction garrison (or a stray event unit) and hand
 * the village a US helicopter from {@code usVehiclePool} while SEM's own guards were RU.
 */
@Mixin(VillageGarrisonHandler.class)
public abstract class MixinVillageGarrisonHandler {

    /** SEM's own garrison-dedupe radius, so "this village already has one" means the same thing here. */
    @Unique
    private static final double TACZ_SEWV$RADIUS = 40.0;

    /** Kept off the guards — a tank in someone's living room helps nobody. 6-12 blocks out. */
    @Unique
    private static final int TACZ_SEWV$MIN_OFFSET = 6;
    @Unique
    private static final int TACZ_SEWV$OFFSET_RANGE = 7;

    @Inject(method = "spawnGuard", at = @At("HEAD"), cancellable = true, remap = false)
    private static void tacz_sewv$gateAmbientGarrison(ServerLevel level, BlockPos basePos, boolean isRu,
                                                    CallbackInfo ci) {
        if (!AmbientSpawnGate.allows(level)) {
            ci.cancel();
        }
    }

    @Inject(method = "spawnGuard", at = @At("HEAD"), remap = false)
    private static void tacz_sewv$addGarrisonTank(ServerLevel level, BlockPos basePos, boolean isRu,
                                                  CallbackInfo ci) {
        // allowsAt, not allows: colonies are routinely built over villages, so a garrison tank is
        // the same "armour inside colony borders" problem the procedural events avoid, arriving
        // by a different route.
        if (!AmbientSpawnGate.allowsAt(level, basePos)) return;
        if (!SewvConfig.GARRISON_VEHICLES_ENABLED.get()) return;

        // Defer out of SEM's onLevelTick loop, which is iterating PENDING_GARRISONS while it calls
        // spawnGuard. Our spawn point is 6-22 blocks off basePos — possibly a chunk SEM never
        // verified loaded (it guards its own guard-spawns with isLoaded, we don't) — so the block
        // reads/addFreshEntity below can force-load it and surface a stored villager, whose join
        // SEM handles by PENDING_GARRISONS.add(): a ConcurrentModificationException mid-iteration.
        // Running at end of tick (as BerezkaStructureCompat does) side-steps the re-entrancy; the
        // seeded roll and the "already has one" dedupe still hold across the 2-4 deferred calls.
        level.getServer().execute(() -> {
            // Already has one (or a second villager's garrison put one here) — nothing to add.
            if (!level.getEntitiesOfClass(VehicleEntity.class, new AABB(basePos).inflate(TACZ_SEWV$RADIUS)).isEmpty()) {
                return;
            }

            // One roll per VILLAGE: seeded on the shared basePos so all 2-4 guard calls agree.
            int chance = (int) Math.round(SewvConfig.GARRISON_VEHICLE_CHANCE.get() * 100.0);
            if (new Random(basePos.asLong()).nextInt(100) >= chance) return;

            BlockPos spot = TankSpawner.adjustHeight(level, tacz_sewv$offset(level, basePos));
            TankSpawner.TankFaction faction = isRu ? TankSpawner.TankFaction.RU : TankSpawner.TankFaction.US;
            // Crewed: fuelled by the faction-energy rule and never scavenged. See the class doc.
            TankSpawner.spawnTankWithCrew(level, spot, faction, null);
        });
    }

    /** A point 6-12 blocks off the garrison in a random direction. */
    @Unique
    private static BlockPos tacz_sewv$offset(ServerLevel level, BlockPos basePos) {
        int dx = TACZ_SEWV$MIN_OFFSET + level.random.nextInt(TACZ_SEWV$OFFSET_RANGE);
        int dz = TACZ_SEWV$MIN_OFFSET + level.random.nextInt(TACZ_SEWV$OFFSET_RANGE);
        if (level.random.nextBoolean()) dx = -dx;
        if (level.random.nextBoolean()) dz = -dz;
        return basePos.offset(dx, 0, dz);
    }
}
