package com.neoalive.tacz_sewv.airport;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;

import com.neoalive.tacz_sewv.block.HelipadBlockEntity;
import com.neoalive.tacz_sewv.block.RunwayBlockEntity;

/**
 * Voids a cleared strip or pad when the world changes under it. Clearance is baked once, so without
 * this a cratered runway or a pad with a block dropped on it stays registered and keeps taking
 * aircraft.
 *
 * <p>A runway is checked against what Check Clearance tested: its air layer (one above the runway
 * block, across the strip) must stay empty, so a block placed there voids it, and its floor (the
 * runway block's layer and the one below) must stay intact, so a solid block broken or blown out
 * there voids it too. A pad voids on any block placed in its reserved volume above the pad layer; a broken
 * pad part already removes the whole pad.
 */
public final class RunwayIntegrity {

    private static final double NOTIFY_RADIUS = 128.0;

    private RunwayIntegrity() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDetonate(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        List<BlockPos> blocks = event.getAffectedBlocks();
        if (blocks.isEmpty()) return;
        AABB reach = bounds(blocks);
        for (Map.Entry<Long, AirportRegistry.Airport> e : snapshot(level)) {
            BlockPos runway = BlockPos.of(e.getKey());
            AABB strip = floorBox(runway, e.getValue());
            if (!strip.intersects(reach)) continue;
            for (BlockPos p : blocks) {
                if (inside(strip, p) && level.getBlockState(p).blocksMotion()) {
                    voidRunway(level, runway, p, null);
                    break;
                }
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level)) return;
        BlockPos p = event.getPos();
        // Grass and flowers on the strip are not its floor.
        if (!event.getState().blocksMotion()) return;
        for (Map.Entry<Long, AirportRegistry.Airport> e : snapshot(level)) {
            BlockPos runway = BlockPos.of(e.getKey());
            if (runway.equals(p)) continue;
            if (inside(floorBox(runway, e.getValue()), p)) {
                voidRunway(level, runway, p, event.getPlayer());
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level)) return;
        BlockPos p = event.getPos();
        Entity who = event.getEntity();
        for (Map.Entry<Long, AirportRegistry.Airport> e : snapshot(level)) {
            BlockPos runway = BlockPos.of(e.getKey());
            if (inside(airBox(runway, e.getValue()), p)) {
                voidRunway(level, runway, p, who);
            }
        }
        for (BlockPos pad : HelipadRegistry.get(level).pads()) {
            if (p.getY() > pad.getY() && inside(HelipadClearance.volume(pad), p)) {
                voidPad(level, pad, p, who);
            }
        }
    }

    private static Collection<Map.Entry<Long, AirportRegistry.Airport>> snapshot(ServerLevel level) {
        Map<Long, AirportRegistry.Airport> all = AirportRegistry.get(level).entries();
        return all.isEmpty() ? List.of() : new ArrayList<>(all.entrySet());
    }

    private static AABB floorBox(BlockPos runway, AirportRegistry.Airport airport) {
        AABB a = airport.slots().area();
        return new AABB(a.minX, runway.getY() - 1, a.minZ, a.maxX, runway.getY() + 1, a.maxZ);
    }

    private static AABB airBox(BlockPos runway, AirportRegistry.Airport airport) {
        AABB a = airport.slots().area();
        return new AABB(a.minX, runway.getY() + 1, a.minZ, a.maxX, runway.getY() + 2, a.maxZ);
    }

    private static boolean inside(AABB box, BlockPos p) {
        return box.contains(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
    }

    private static AABB bounds(List<BlockPos> blocks) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : blocks) {
            minX = Math.min(minX, p.getX());
            minY = Math.min(minY, p.getY());
            minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX());
            maxY = Math.max(maxY, p.getY());
            maxZ = Math.max(maxZ, p.getZ());
        }
        return new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
    }

    private static void voidRunway(ServerLevel level, BlockPos runway, BlockPos cause, @Nullable Entity who) {
        if (loadedBlockEntity(level, runway) instanceof RunwayBlockEntity be) {
            be.clearClearance();
        } else {
            AirportRegistry.get(level).invalidateUnloaded(runway);
        }
        notify(level, runway, who,
                Component.translatable("gui.tacz_sewv.airport.status.obstructed",
                        cause.getX(), cause.getY(), cause.getZ()));
    }

    private static void voidPad(ServerLevel level, BlockPos pad, BlockPos cause, @Nullable Entity who) {
        if (loadedBlockEntity(level, pad) instanceof HelipadBlockEntity be) {
            be.clearClearance();
        } else {
            HelipadRegistry.get(level).invalidateUnloaded(pad);
        }
        notify(level, pad, who,
                Component.translatable("gui.tacz_sewv.helipad.obstructed",
                        cause.getX(), cause.getY(), cause.getZ()));
    }

    @Nullable
    private static BlockEntity loadedBlockEntity(LevelAccessor level, BlockPos pos) {
        if (!(level instanceof ServerLevel server)) return null;
        LevelChunk chunk = server.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk == null ? null : chunk.getBlockEntity(pos);
    }

    private static void notify(ServerLevel level, BlockPos at, @Nullable Entity who, Component line) {
        if (who instanceof ServerPlayer player) {
            player.displayClientMessage(line, true);
            return;
        }
        double r2 = NOTIFY_RADIUS * NOTIFY_RADIUS;
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5) <= r2) {
                player.displayClientMessage(line, true);
            }
        }
    }
}
