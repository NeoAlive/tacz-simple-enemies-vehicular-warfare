package com.neoalive.tacz_sewv.command.quick;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.fob.FobInstance;
import com.neoalive.tacz_sewv.fob.FobResupplySupport;
import com.neoalive.tacz_sewv.fob.FobSupport;
import com.neoalive.tacz_sewv.notify.HudNotify;

/**
 * Quick Refill runner: units path to a chest / FOB stockpile, then pull ammo only within
 * {@link #REACH_BLOCKS}. Stale-safe — re-resolves by network id each poll.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID)
public final class QuickRefillTracker {

    private static final int POLL_INTERVAL = 10;
    private static final double REACH_BLOCKS = 2.0;
    private static final double REACH_SQ = REACH_BLOCKS * REACH_BLOCKS;

    private static final Map<UUID, Run> ACTIVE = new HashMap<>();

    private QuickRefillTracker() {}

    public static void start(UUID playerId, List<LegSpec> legs, long now) {
        long deadline = now + SewvConfig.QUICK_EVAC_BOARD_TIMEOUT_TICKS.get();
        List<Leg> live = new ArrayList<>(legs.size());
        for (LegSpec spec : legs) {
            live.add(new Leg(spec.unitId(), spec.chestPos(), spec.fobStockpile()));
        }
        ACTIVE.put(playerId, new Run(playerId, live, deadline));
    }

    /** Abort an in-flight refill for this player (Quick Cancel) and drop MOVE walks. */
    public static void cancelPlayer(UUID playerId, ServerLevel level) {
        Run run = ACTIVE.remove(playerId);
        if (run == null) return;
        for (Leg leg : run.legs) {
            if (!leg.done) dropRefillWalk(level, leg);
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || ACTIVE.isEmpty()) return;
        if (server.getTickCount() % POLL_INTERVAL != 0) return;

        Iterator<Map.Entry<UUID, Run>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Run> entry = it.next();
            Run run = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(run.playerId);
            if (player == null || !(player.level() instanceof ServerLevel level)) {
                it.remove();
                continue;
            }
            if (tick(level, player, run)) {
                it.remove();
            }
        }
    }

    private static boolean tick(ServerLevel level, ServerPlayer player, Run run) {
        long now = level.getGameTime();
        boolean timedOut = now >= run.deadline;

        for (Leg leg : run.legs) {
            if (leg.done) continue;
            if (timedOut) {
                dropRefillWalk(level, leg);
                leg.done = true;
                continue;
            }

            Entity e = level.getEntity(leg.unitId);
            if (!(e instanceof PmcUnitEntity pmc) || !pmc.isAlive() || !pmc.isOwnedBy(player)) {
                leg.done = true;
                continue;
            }
            if (pmc.getVehicle() != null) {
                dropRefillWalk(pmc);
                leg.done = true;
                continue;
            }

            BlockPos chest = leg.chestPos;
            if (chest == null || !level.isLoaded(chest)) {
                dropRefillWalk(pmc);
                leg.done = true;
                continue;
            }

            if (!chestStillEligible(level, pmc, chest, leg.fobStockpile)) {
                // Chest emptied / wrong stock — abort the walk instead of camping an empty box.
                dropRefillWalk(pmc);
                leg.done = true;
                continue;
            }

            double dx = pmc.getX() - (chest.getX() + 0.5);
            double dy = pmc.getY() - chest.getY();
            double dz = pmc.getZ() - (chest.getZ() + 0.5);
            if (dx * dx + dy * dy + dz * dz > REACH_SQ) {
                // Keep the walk order sticky while the run is live.
                pmc.setOrder(OrderType.MOVE_TO_POSITION);
                pmc.setMoveToTarget(Vec3.atBottomCenterOf(chest));
                continue;
            }

            boolean moved = false;
            if (leg.fobStockpile) {
                moved = FobResupplySupport.forceStockpileRefill(pmc);
            } else {
                BlockEntity be = level.getBlockEntity(chest);
                IItemHandler handler = be == null ? null
                        : be.getCapability(ForgeCapabilities.ITEM_HANDLER, null).orElse(null);
                moved = FobResupplySupport.refillFromHandler(pmc, handler);
            }
            if (moved) {
                HudNotify.clearAmmoOut(pmc);
            } else {
                dropRefillWalk(pmc);
            }
            leg.done = true;
        }

        for (Leg leg : run.legs) {
            if (!leg.done) return false;
        }
        return true;
    }

    /** Find nearest eligible chest (or FOB stockpile) for a unit within {@code radius}. */
    @Nullable
    public static BlockPos findRefillTarget(ServerLevel level, PmcUnitEntity pmc, double radius,
                                            boolean preferFobStockpile) {
        if (preferFobStockpile) {
            FobInstance fob = FobSupport.fobForEntity(pmc, level);
            if (fob != null && fob.fobCommandActive && fob.stockpilePos != null) {
                return fob.stockpilePos.immutable();
            }
        }

        AABB box = pmc.getBoundingBox().inflate(radius);
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        int minCx = SectionPos.blockToSectionCoord((int) Math.floor(box.minX));
        int maxCx = SectionPos.blockToSectionCoord((int) Math.floor(box.maxX));
        int minCz = SectionPos.blockToSectionCoord((int) Math.floor(box.minZ));
        int maxCz = SectionPos.blockToSectionCoord((int) Math.floor(box.maxZ));
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                if (!level.hasChunk(cx, cz)) continue;
                LevelChunk chunk = level.getChunk(cx, cz);
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    BlockPos pos = be.getBlockPos();
                    if (!box.contains(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)) continue;
                    IItemHandler handler = be.getCapability(ForgeCapabilities.ITEM_HANDLER, null)
                            .orElse(null);
                    if (!FobResupplySupport.handlerHasEligible(pmc, handler)) continue;
                    double d = pmc.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                    if (d < bestDist) {
                        bestDist = d;
                        best = pos.immutable();
                    }
                }
            }
        }
        return best;
    }

    private static boolean chestStillEligible(ServerLevel level, PmcUnitEntity pmc, BlockPos chest,
                                              boolean fobStockpile) {
        if (fobStockpile) {
            // Stockpile transfer gates itself inside forceStockpileRefill; keep walking until reach.
            return true;
        }
        BlockEntity be = level.getBlockEntity(chest);
        IItemHandler handler = be == null ? null
                : be.getCapability(ForgeCapabilities.ITEM_HANDLER, null).orElse(null);
        return FobResupplySupport.handlerHasEligible(pmc, handler);
    }

    private static void dropRefillWalk(ServerLevel level, Leg leg) {
        Entity e = level.getEntity(leg.unitId);
        if (e instanceof PmcUnitEntity pmc) dropRefillWalk(pmc);
    }

    private static void dropRefillWalk(PmcUnitEntity pmc) {
        if (pmc.getOrder() == OrderType.MOVE_TO_POSITION) {
            pmc.setOrder(OrderType.FREE_FIRE);
            pmc.getNavigation().stop();
        }
    }

    public record LegSpec(int unitId, BlockPos chestPos, boolean fobStockpile) {}

    private static final class Run {
        final UUID playerId;
        final List<Leg> legs;
        final long deadline;

        Run(UUID playerId, List<Leg> legs, long deadline) {
            this.playerId = playerId;
            this.legs = legs;
            this.deadline = deadline;
        }
    }

    private static final class Leg {
        final int unitId;
        final BlockPos chestPos;
        final boolean fobStockpile;
        boolean done;

        Leg(int unitId, BlockPos chestPos, boolean fobStockpile) {
            this.unitId = unitId;
            this.chestPos = chestPos;
            this.fobStockpile = fobStockpile;
        }
    }
}
