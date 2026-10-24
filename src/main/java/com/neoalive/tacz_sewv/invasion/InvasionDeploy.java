package com.neoalive.tacz_sewv.invasion;

import java.util.List;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.network.PacketDistributor;

import com.neoalive.tacz_sewv.block.TeamBaseBlockEntity;
import com.neoalive.tacz_sewv.debug.SewvDiag;
import com.neoalive.tacz_sewv.invasion.force.ForceKind;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketInvasionDeployPrompt;
import com.neoalive.tacz_sewv.spawn.TankSpawner;

/**
 * How a player of a player-owned base enters the fight, at match start and after every death:
 * they are put back at the base and offered every {@link ForceKind} the base allows
 * ({@link com.neoalive.tacz_sewv.invasion.force.BaseForce#deployOptions}). One option deploys at
 * once; several open the client's deploy screen and wait {@link #TIMEOUT_TICKS} for an answer,
 * after which the first option is taken — a player who tabbed out is never left standing at base.
 * <p>
 * The offer is server state ({@link InvasionSession}), so a choice packet is only honoured if an
 * offer is outstanding and names one of its options; anything else is ignored.
 */
public final class InvasionDeploy {

    public static final int TIMEOUT_TICKS = 20 * 20;

    /** An outstanding offer: the base it was made from, what was offered, and when it lapses. */
    public record Pending(long basePos, List<ForceKind> options, long deadline) {}

    private InvasionDeploy() {}

    public static void offer(ServerLevel level, TeamBaseBlockEntity base, ServerPlayer player) {
        placeAtBase(level, base, player);
        List<ForceKind> options = base.getForce().deployOptions();
        if (options.isEmpty()) {
            InvasionSession.clearPendingDeploy(level, player.getUUID());
            player.displayClientMessage(Component.translatable("message.tacz_sewv.invasion.deploy.none"), false);
            SewvDiag.invasion("deploy none player={} base={}", player.getGameProfile().getName(), base.getBlockPos());
            return;
        }
        if (options.size() == 1) {
            InvasionSession.clearPendingDeploy(level, player.getUUID());
            deploy(level, base, player, options.get(0));
            return;
        }
        long deadline = level.getGameTime() + TIMEOUT_TICKS;
        InvasionSession.setPendingDeploy(level, player.getUUID(),
                new Pending(base.getBlockPos().asLong(), List.copyOf(options), deadline));
        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new PacketInvasionDeployPrompt(options, TIMEOUT_TICKS / 20));
        SewvDiag.invasion("deploy offer player={} options={}", player.getGameProfile().getName(), options);
    }

    /** The client picked {@code kind}. Ignored unless it answers an outstanding offer. */
    public static void choose(ServerPlayer player, ForceKind kind) {
        if (!(player.level() instanceof ServerLevel level)) return;
        Pending pending = InvasionSession.pendingDeploy(level, player.getUUID());
        if (pending == null || !pending.options().contains(kind)) {
            SewvDiag.invasion("deploy choice refused player={} kind={} pending={}",
                    player.getGameProfile().getName(), kind, pending);
            return;
        }
        InvasionSession.clearPendingDeploy(level, player.getUUID());
        resolve(level, player, pending, kind);
    }

    /** The offer lapsed: take its first option. */
    static void expire(ServerLevel level, ServerPlayer player, Pending pending) {
        SewvDiag.invasion("deploy timeout player={} -> {}", player.getGameProfile().getName(), pending.options().get(0));
        resolve(level, player, pending, pending.options().get(0));
    }

    private static void resolve(ServerLevel level, ServerPlayer player, Pending pending, ForceKind kind) {
        if (!InvasionSession.hasFielded(level) || !player.isAlive()) return;
        if (player.getVehicle() instanceof VehicleEntity) return;
        TeamBaseBlockEntity base = InvasionSpawn.playerOwnedBaseFor(level, player);
        if (base == null || base.getBlockPos().asLong() != pending.basePos()) return;
        deploy(level, base, player, kind);
    }

    private static void deploy(ServerLevel level, TeamBaseBlockEntity base, ServerPlayer player, ForceKind kind) {
        if (kind == ForceKind.INFANTRY) {
            SewvDiag.invasion("deploy onFoot player={} base={}", player.getGameProfile().getName(), base.getBlockPos());
            return; // already standing at the base
        }
        List<String> pool = InvasionSpawn.fittingPool(base, kind);
        if (pool.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.tacz_sewv.invasion.deploy.failed"), false);
            return;
        }
        String vehicleId = InvasionSpawn.pickRandom(pool, level);
        BlockPos at = InvasionSpawn.offsetInRadius(level, base.getBlockPos(), base.getRadiusInBlocks(),
                player.getId() + (int) (level.getGameTime() & 0xff));
        Float airborneYaw = kind == ForceKind.PLANE ? InvasionSpawn.yawTowardFront(level, base) : null;
        VehicleEntity hull = TankSpawner.spawnPlayerDrivenWithOptionalCrew(
                level, at, player, vehicleId, pool, base.getForce().npcCrewForPlayers(), airborneYaw);
        if (hull == null) {
            SewvDiag.invasion("spawnFail player={} base={} kind={} id={}",
                    player.getGameProfile().getName(), base.getBlockPos(), kind, vehicleId);
            player.displayClientMessage(Component.translatable("message.tacz_sewv.invasion.deploy.failed"), false);
            return;
        }
        InvasionSpawn.tagHullAndCrew(hull, base, false, kind);
        InvasionSpawn.beginCaptureOrdersOnCrew(hull);
        SewvDiag.invasion("deploy player={} kind={} id={}", player.getGameProfile().getName(), kind, vehicleId);
    }

    private static void placeAtBase(ServerLevel level, TeamBaseBlockEntity base, ServerPlayer player) {
        if (player.getVehicle() != null) return;
        BlockPos near = InvasionSpawn.offsetInRadius(level, base.getBlockPos(), Math.max(2, base.getRadiusInBlocks()),
                player.getId());
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, near.getX(), near.getZ());
        player.teleportTo(near.getX() + 0.5, y, near.getZ() + 0.5);
    }
}
