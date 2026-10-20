package com.neoalive.tacz_sewv.entity.ai.support;

import java.util.List;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

import com.neoalive.tacz_sewv.bridge.IEscort;
import com.neoalive.tacz_sewv.bridge.ILodUnit;
import com.neoalive.tacz_sewv.bridge.IVehicleBoarder;
import com.neoalive.tacz_sewv.config.SewvConfig;

/**
 * Distance-based AI level of detail for SEM units: far from every player, a unit makes its goal
 * decisions every 4 or 8 ticks instead of vanilla's every 2.
 *
 * <p>Only DECISIONS are thinned. {@code MixinMobUnitLod} turns a skipped {@code GoalSelector.tick()}
 * into {@code tickRunningGoals(false)} — exactly what vanilla does on its odd ticks — so goals that
 * require every-tick updates keep running and navigation, movement and physics are untouched: a
 * throttled unit still walks, takes cover it already chose, and takes damage normally. What slows
 * is everything counted in goal ticks (SEM rifle fire attempts and burst cooldowns, target-scan
 * rolls, repath counters); game-time timers do not stretch. Far units are throttled whether or not
 * they are fighting — remote battles run slower for both sides alike, by design.
 *
 * <p>The tier is assigned from {@code CombatantIndex}'s rebuild, which already walks every unit,
 * so there is no scan of its own; {@link #due} reads only fields. A tier older than
 * {@link #STALE_TICKS} reads as FULL, so a stopped index can never leave units throttled. Being
 * hurt or acquiring a NEW target wakes a unit to full rate for {@code unitLodWakeTicks}.
 */
public final class UnitLod {

    /** Twice the default index interval: a missed rebuild is tolerated, a stopped index is not. */
    private static final long STALE_TICKS = 100;

    // Config snapshot, refreshed each assignment pass so the hot path never reads config.
    private static volatile boolean enabled;
    private static volatile int midStride = 4;
    private static volatile int farStride = 8;
    private static volatile int wakeTicks = 40;

    // Perf: tiers at each dimension's last pass ({full, mid, far, exempt}), summed when printed —
    // one shared set was overwritten by whichever (empty) dimension rebuilt last.
    private static final java.util.Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, int[]>
            LAST_TIERS = new java.util.concurrent.ConcurrentHashMap<>();
    private static long evaluated;
    private static long skipped;
    private static long wakes;

    private UnitLod() {}

    /**
     * Whether this unit runs its full goal selection on this (vanilla-even) tick. Called once per
     * selector, so twice per unit per evaluation tick.
     */
    public static boolean due(AbstractUnit unit) {
        if (!enabled) return true;
        ILodUnit lod = (ILodUnit) unit;
        byte tier = lod.sewv$lodTier();
        if (tier == UnitLodPolicy.FULL) return true;
        long now = unit.level().getGameTime();
        if (now - lod.sewv$lodStamp() > STALE_TICKS || now < lod.sewv$lodWakeUntil()) return true;
        int stride = UnitLodPolicy.strideFor(tier, midStride, farStride);
        if (UnitLodPolicy.due(unit.getServer().getTickCount(), unit.getId(), stride)) {
            evaluated++;
            return true;
        }
        skipped++;
        return false;
    }

    /** Assigns every unit's tier for this rebuild. Called from {@code CombatantIndex.rebuild}. */
    public static void assignTiers(ServerLevel level, List<? extends LivingEntity> units, long now) {
        refreshConfig();
        int full = 0, mid = 0, far = 0, exempt = 0;
        if (!enabled) {
            LAST_TIERS.clear();
            return;
        }
        double near = nearBlocks();
        double farB = farBlocks();
        List<ServerPlayer> players = level.players();
        for (LivingEntity e : units) {
            if (!(e instanceof AbstractUnit unit)) continue;
            boolean isExempt = exempt(unit);
            double best = Double.MAX_VALUE;
            boolean hasPlayer = false;
            if (!isExempt) {
                for (ServerPlayer p : players) {
                    if (p.isSpectator()) continue;
                    double dx = p.getX() - unit.getX();
                    double dz = p.getZ() - unit.getZ();
                    double d = dx * dx + dz * dz;
                    if (d < best) best = d;
                    hasPlayer = true;
                }
            }
            byte tier = UnitLodPolicy.tierFor(best, near, farB, isExempt, hasPlayer);
            ((ILodUnit) unit).sewv$setLod(tier, now);
            if (isExempt) exempt++;
            else if (tier == UnitLodPolicy.FULL) full++;
            else if (tier == UnitLodPolicy.MID) mid++;
            else far++;
        }
        LAST_TIERS.put(level.dimension(), new int[] {full, mid, far, exempt});
    }

    /**
     * Never throttled: mounted crews (their drive goals have their own LOD and gunners must stay
     * crisp), the player's own PMC, mortar crews, and units walking to a hull or escorting one.
     */
    private static boolean exempt(AbstractUnit unit) {
        if (unit instanceof PmcUnitEntity) return true;
        if (VehicleCrew.suppressOnFootAi(unit)) return true;
        if (MortarSupport.hasMortarClaim(unit)) return true;
        if (unit instanceof IVehicleBoarder b
                && (b.tacz_sewv$getMountTargetId() != -1 || b.tacz_sewv$isBoarding())) {
            return true;
        }
        return unit instanceof IEscort escort && escort.tacz_sewv$isEscorting();
    }

    /** Full rate for a while: a unit that is shot at, or sees a new enemy, reacts at vanilla speed. */
    public static void wake(AbstractUnit unit) {
        if (!enabled || wakeTicks <= 0) return;
        ILodUnit lod = (ILodUnit) unit;
        if (lod.sewv$lodTier() == UnitLodPolicy.FULL) return;
        long until = unit.level().getGameTime() + wakeTicks;
        if (until > lod.sewv$lodWakeUntil()) {
            lod.sewv$setLodWakeUntil(until);
            wakes++;
        }
    }

    /** Called on an accepted setTarget; wakes only on a change of target. */
    public static void onTargetAcquired(AbstractUnit unit, LivingEntity target) {
        ILodUnit lod = (ILodUnit) unit;
        if (lod.sewv$lodWakeTargetId() == target.getId()) return;
        lod.sewv$setLodWakeTargetId(target.getId());
        wake(unit);
    }

    @SubscribeEvent
    public static void onHurt(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof AbstractUnit unit && !unit.level().isClientSide()) wake(unit);
    }

    private static void refreshConfig() {
        try {
            enabled = SewvConfig.UNIT_LOD_ENABLED.get();
            midStride = UnitLodPolicy.normaliseStride(SewvConfig.UNIT_LOD_MID_STRIDE.get());
            farStride = UnitLodPolicy.normaliseStride(SewvConfig.UNIT_LOD_FAR_STRIDE.get());
            wakeTicks = SewvConfig.UNIT_LOD_WAKE_TICKS.get();
        } catch (Throwable unbaked) {
            enabled = false;
        }
    }

    private static double nearBlocks() {
        try {
            return SewvConfig.UNIT_LOD_NEAR_BLOCKS.get();
        } catch (Throwable unbaked) {
            return 128.0;
        }
    }

    private static double farBlocks() {
        try {
            return SewvConfig.UNIT_LOD_FAR_BLOCKS.get();
        } catch (Throwable unbaked) {
            return 256.0;
        }
    }

    public static String snapshotAndReset() {
        long total = evaluated + skipped;
        int[] t = new int[4];
        for (int[] c : LAST_TIERS.values()) for (int i = 0; i < 4; i++) t[i] += c[i];
        String s = String.format(
                "unit lod enabled=%s tiers full=%d mid=%d far=%d exempt=%d | throttled selector calls run=%d"
                        + " skipped=%d (%.0f%% skipped) | wakes=%d",
                enabled, t[0], t[1], t[2], t[3], evaluated, skipped,
                total == 0 ? 0.0 : 100.0 * skipped / total, wakes);
        evaluated = 0;
        skipped = 0;
        wakes = 0;
        return s;
    }
}
