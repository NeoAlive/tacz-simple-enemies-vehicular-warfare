package com.neoalive.tacz_sewv.sweep;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.compat.OpenPacCompat;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.debug.SewvDiag;
import com.neoalive.tacz_sewv.diplomacy.DiplomacyData;
import com.neoalive.tacz_sewv.entity.ai.VehicleTargeting;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Player-triggered Sweep &amp; Advance: one in-memory operation per commander.
 * Cheap quiet checks every second; expensive AABB defensive scan only when quiet threshold hits.
 */
public final class SweepAdvancement {

    private static final Map<UUID, Operation> ACTIVE = new HashMap<>();

    private SweepAdvancement() {}

    public record ChunkRect(int left, int top, int right, int bottom) {
        public ChunkRect normalized() {
            return new ChunkRect(
                    Math.min(left, right), Math.min(top, bottom),
                    Math.max(left, right), Math.max(top, bottom));
        }

        public int width() {
            return right - left + 1;
        }

        public int height() {
            return bottom - top + 1;
        }

        public int area() {
            return width() * height();
        }
    }

    private static final class Operation {
        final UUID commanderId;
        final ResourceKey<Level> dim;
        ChunkRect rect;
        final Set<Integer> assigneeIds = new HashSet<>();
        int quietSeconds;
        boolean pendingDefensiveScan;

        Operation(UUID commanderId, ResourceKey<Level> dim, ChunkRect rect) {
            this.commanderId = commanderId;
            this.dim = dim;
            this.rect = rect;
        }
    }

    /** Replace any prior sweep for this player. Assignees are added via {@link #addAssignee}. */
    public static void begin(ServerPlayer player, ResourceKey<Level> dim, ChunkRect rect) {
        cancel(player, false);
        Operation op = new Operation(player.getUUID(), dim, rect.normalized());
        ACTIVE.put(player.getUUID(), op);
        SewvDiag.sweep("start player={} dim={} rect={},{}→{},{} area={}",
                player.getGameProfile().getName(), dim.location(),
                op.rect.left(), op.rect.top(), op.rect.right(), op.rect.bottom(), op.rect.area());
    }

    public static void addAssignee(UUID commanderId, int entityId) {
        Operation op = ACTIVE.get(commanderId);
        if (op != null) op.assigneeIds.add(entityId);
    }

    public static boolean isActive(UUID commanderId) {
        return ACTIVE.containsKey(commanderId);
    }

    @Nullable
    public static ChunkRect rectOf(UUID commanderId) {
        Operation op = ACTIVE.get(commanderId);
        return op == null ? null : op.rect;
    }

    /** Drop one unit from the operation; cancel without claim if none remain. */
    public static void unregisterUnit(PmcUnitEntity pmc) {
        UUID owner = pmc.getOwnerUUID();
        if (owner == null) return;
        Operation op = ACTIVE.get(owner);
        if (op == null) return;
        if (op.assigneeIds.remove(pmc.getId()) && op.assigneeIds.isEmpty()) {
            ServerPlayer player = pmc.getServer() != null
                    ? pmc.getServer().getPlayerList().getPlayer(owner) : null;
            if (player != null) cancel(player, true);
            else ACTIVE.remove(owner);
        }
    }

    public static void cancel(ServerPlayer player, boolean notify) {
        Operation removed = ACTIVE.remove(player.getUUID());
        if (removed == null) return;
        SewvDiag.sweep("cancel player={} assigneesLeft={}",
                player.getGameProfile().getName(), removed.assigneeIds.size());
        if (notify) {
            NetworkHandler.sendOrderFeedback(player,
                    Component.translatable("message.tacz_sewv.sweep.cancelled")
                            .withStyle(ChatFormatting.YELLOW));
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            ACTIVE.remove(sp.getUUID());
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % 20 != 0) return;
        if (ACTIVE.isEmpty()) return;

        int needQuiet = Math.max(1, SewvConfig.SWEEP_QUIET_SECONDS.get());
        Iterator<Map.Entry<UUID, Operation>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Operation> e = it.next();
            Operation op = e.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(op.commanderId);
            if (player == null) {
                it.remove();
                continue;
            }
            ServerLevel level = server.getLevel(op.dim);
            if (level == null) {
                it.remove();
                continue;
            }

            // --- cheap path only ---
            pruneAssignees(op, level, player);
            if (op.assigneeIds.isEmpty()) {
                SewvDiag.sweep("abandon empty assignees player={}", player.getGameProfile().getName());
                it.remove();
                NetworkHandler.sendOrderFeedback(player,
                        Component.translatable("message.tacz_sewv.sweep.cancelled")
                                .withStyle(ChatFormatting.YELLOW));
                continue;
            }

            if (anyAssigneeHasTarget(op, level)) {
                op.quietSeconds = 0;
                op.pendingDefensiveScan = false;
                continue;
            }

            op.quietSeconds++;
            SewvDiag.sweep("quietTick player={} quiet={}/{} assignees={}",
                    player.getGameProfile().getName(), op.quietSeconds, needQuiet, op.assigneeIds.size());

            if (op.quietSeconds < needQuiet) continue;

            // --- expensive path: once per quiet threshold ---
            if (defensiveHasEnemy(op, level, player)) {
                SewvDiag.sweep("dirtyRescan player={} — reset quiet", player.getGameProfile().getName());
                op.quietSeconds = 0;
                continue;
            }

            it.remove();
            completeAndClaim(player, level, op);
        }
    }

    private static void pruneAssignees(Operation op, ServerLevel level, ServerPlayer player) {
        op.assigneeIds.removeIf(id -> {
            if (!(level.getEntity(id) instanceof PmcUnitEntity pmc) || !pmc.isAlive()) return true;
            return !pmc.isOwnedBy(player);
        });
    }

    private static boolean anyAssigneeHasTarget(Operation op, ServerLevel level) {
        for (int id : op.assigneeIds) {
            if (level.getEntity(id) instanceof PmcUnitEntity pmc) {
                LivingEntity t = pmc.getTarget();
                if (t != null && t.isAlive()) return true;
            }
        }
        return false;
    }

    /** Expensive AABB scan — only called after quiet threshold. */
    private static boolean defensiveHasEnemy(Operation op, ServerLevel level, ServerPlayer player) {
        AABB box = chunkRectBox(level, op.rect);
        PmcUnitEntity probe = firstAssignee(op, level);
        if (probe == null) return false;

        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, LivingEntity::isAlive)) {
            if (e instanceof Player p && p.getUUID().equals(player.getUUID())) continue;
            if (e instanceof PmcUnitEntity pmc && pmc.isOwnedBy(player)) continue;
            if (!VehicleTargeting.isNonHostile(probe, e)) {
                SewvDiag.sweep("defensiveHit living={} id={}", e.getType().getDescriptionId(), e.getId());
                return true;
            }
        }
        for (VehicleEntity hull : level.getEntitiesOfClass(VehicleEntity.class, box, h -> true)) {
            for (var passenger : hull.getPassengers()) {
                if (passenger instanceof LivingEntity living
                        && !(living instanceof PmcUnitEntity pmc && pmc.isOwnedBy(player))
                        && !VehicleTargeting.isNonHostile(probe, living)) {
                    SewvDiag.sweep("defensiveHit vehiclePassenger={} hull={}",
                            living.getId(), hull.getId());
                    return true;
                }
            }
        }
        return false;
    }

    @Nullable
    private static PmcUnitEntity firstAssignee(Operation op, ServerLevel level) {
        for (int id : op.assigneeIds) {
            if (level.getEntity(id) instanceof PmcUnitEntity pmc && pmc.isAlive()) return pmc;
        }
        return null;
    }

    private static AABB chunkRectBox(ServerLevel level, ChunkRect r) {
        int minX = r.left() << 4;
        int minZ = r.top() << 4;
        int maxX = (r.right() << 4) + 16;
        int maxZ = (r.bottom() << 4) + 16;
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static void completeAndClaim(ServerPlayer player, ServerLevel level, Operation op) {
        if (!OpenPacCompat.isLoaded()) {
            NetworkHandler.sendOrderFeedback(player,
                    Component.translatable("message.tacz_sewv.sweep.no_openpac")
                            .withStyle(ChatFormatting.RED));
            return;
        }

        ChunkRect trimmed = allyEdgeTrim(level, player, op.rect);
        if (trimmed == null || trimmed.area() <= 0) {
            SewvDiag.sweep("completeEmpty after trim player={}", player.getGameProfile().getName());
            NetworkHandler.sendOrderFeedback(player,
                    Component.translatable("message.tacz_sewv.sweep.complete_empty")
                            .withStyle(ChatFormatting.YELLOW));
            return;
        }

        UUID partyOwner = OpenPacCompat.partyOwnerId(level.getServer(), player.getUUID());
        if (partyOwner == null) partyOwner = player.getUUID();

        DiplomacyData diplomacy = DiplomacyData.get(level);
        String cmdFaction = OpenPacCompat.factionName(level.getServer(), player.getUUID());

        int attempted = 0;
        int claimed = 0;
        int blocked = 0;
        for (int cx = trimmed.left(); cx <= trimmed.right(); cx++) {
            for (int cz = trimmed.top(); cz <= trimmed.bottom(); cz++) {
                UUID existing = OpenPacCompat.claimOwnerId(level, cx, cz);
                if (existing != null && cmdFaction != null) {
                    String claimFaction = OpenPacCompat.factionName(level.getServer(), existing);
                    if (claimFaction != null
                            && diplomacy.relation(cmdFaction, claimFaction) == DiplomacyData.Relation.ALLY
                            && !existing.equals(partyOwner)) {
                        blocked++;
                        attempted++;
                        SewvDiag.sweep("skipAllyOwned chunk={},{}", cx, cz);
                        continue;
                    }
                }
                attempted++;
                if (OpenPacCompat.claim(level, partyOwner, cx, cz)) {
                    // claim() returns true even on some soft failures — verify readback
                    UUID after = OpenPacCompat.claimOwnerId(level, cx, cz);
                    if (partyOwner.equals(after)) claimed++;
                    else blocked++;
                } else {
                    blocked++;
                }
            }
        }

        SewvDiag.sweep("complete claimed={}/{} blocked={} player={}",
                claimed, attempted, blocked, player.getGameProfile().getName());
        NetworkHandler.sendOrderFeedback(player,
                Component.translatable("message.tacz_sewv.sweep.complete", claimed, attempted, blocked)
                        .withStyle(blocked == 0 ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
    }

    /**
     * Pull each edge inward while that edge borders DiplomacyData ALLY claims.
     * Returns null if the rect collapses.
     */
    @Nullable
    static ChunkRect allyEdgeTrim(ServerLevel level, ServerPlayer player, ChunkRect rect) {
        String cmdFaction = OpenPacCompat.factionName(level.getServer(), player.getUUID());
        if (cmdFaction == null) return rect;

        DiplomacyData diplomacy = DiplomacyData.get(level);
        int left = rect.left();
        int right = rect.right();
        int top = rect.top();
        int bottom = rect.bottom();

        boolean changed;
        do {
            changed = false;
            if (edgeBordersAlly(level, diplomacy, cmdFaction, left - 1, left - 1, top, bottom)) {
                SewvDiag.sweep("trimEdge x- left {}→{}", left, left + 1);
                left++;
                changed = true;
            }
            if (left <= right
                    && edgeBordersAlly(level, diplomacy, cmdFaction, right + 1, right + 1, top, bottom)) {
                SewvDiag.sweep("trimEdge x+ right {}→{}", right, right - 1);
                right--;
                changed = true;
            }
            if (left <= right && top <= bottom
                    && edgeBordersAlly(level, diplomacy, cmdFaction, left, right, top - 1, top - 1)) {
                SewvDiag.sweep("trimEdge z- top {}→{}", top, top + 1);
                top++;
                changed = true;
            }
            if (left <= right && top <= bottom
                    && edgeBordersAlly(level, diplomacy, cmdFaction, left, right, bottom + 1, bottom + 1)) {
                SewvDiag.sweep("trimEdge z+ bottom {}→{}", bottom, bottom - 1);
                bottom--;
                changed = true;
            }
        } while (changed && left <= right && top <= bottom);

        if (left > right || top > bottom) return null;
        return new ChunkRect(left, top, right, bottom);
    }

    private static boolean edgeBordersAlly(ServerLevel level, DiplomacyData diplomacy, String cmdFaction,
                                           int x0, int x1, int z0, int z1) {
        for (int cx = x0; cx <= x1; cx++) {
            for (int cz = z0; cz <= z1; cz++) {
                UUID owner = OpenPacCompat.claimOwnerId(level, cx, cz);
                if (owner == null) continue;
                String f = OpenPacCompat.factionName(level.getServer(), owner);
                if (f != null && diplomacy.relation(cmdFaction, f) == DiplomacyData.Relation.ALLY) {
                    return true;
                }
            }
        }
        return false;
    }
}
