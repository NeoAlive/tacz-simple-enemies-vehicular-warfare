package com.neoalive.tacz_sewv.command.quick;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiPredicate;

import javax.annotation.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
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
import com.neoalive.tacz_sewv.block.StockpileBlockEntity;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.support.RoleKit;
import com.neoalive.tacz_sewv.fob.FobInstance;
import com.neoalive.tacz_sewv.fob.FobManager;
import com.neoalive.tacz_sewv.invasion.PmcOwnerSupport;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.order.OrderReport;

/**
 * Search equipment runner: each unit walks to one role item (on the ground or in a container), takes
 * a single one within {@link #REACH_BLOCKS} and equips it. Targets are claimed per run, so two units
 * are never sent for the same item. One report per run when the last walk ends.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID)
public final class GearSearchTracker {

    private static final int POLL_INTERVAL = 10;
    private static final double REACH_BLOCKS = 2.0;
    private static final double REACH_SQ = REACH_BLOCKS * REACH_BLOCKS;

    private static final Map<UUID, Run> ACTIVE = new HashMap<>();

    private GearSearchTracker() {}

    /** Item on the ground ({@code itemId >= 0}) or a container at {@code pos}. */
    record Target(int itemId, BlockPos pos) {
        Object claimKey() {
            return this.itemId >= 0 ? (Object) this.itemId : this.pos;
        }
    }

    static final class Run {
        final UUID playerId;
        final RoleKit.Kit kit;
        final List<Leg> legs = new ArrayList<>();
        final Map<Object, Integer> claims = new HashMap<>();
        long deadline;
        int equipped;
        int missing;

        Run(UUID playerId, RoleKit.Kit kit) {
            this.playerId = playerId;
            this.kit = kit;
        }

        void claim(Target t) {
            this.claims.merge(t.claimKey(), 1, Integer::sum);
        }

        void unclaim(Target t) {
            this.claims.computeIfPresent(t.claimKey(), (k, v) -> v <= 1 ? null : v - 1);
        }

        int claimed(Object key) {
            return this.claims.getOrDefault(key, 0);
        }
    }

    static final class Leg {
        final int unitId;
        Target target;
        boolean retried;
        boolean done;

        Leg(int unitId, Target target) {
            this.unitId = unitId;
            this.target = target;
        }
    }

    static Run newRun(ServerPlayer issuer, RoleKit.Kit kit) {
        return new Run(issuer.getUUID(), kit);
    }

    static void addLeg(Run run, PmcUnitEntity pmc, Target target) {
        run.claim(target);
        run.legs.add(new Leg(pmc.getId(), target));
        walkTo(pmc, target.pos());
    }

    static void start(Run run, long now) {
        run.deadline = now + SewvConfig.QUICK_GEAR_SEARCH_TIMEOUT_TICKS.get();
        ACTIVE.put(run.playerId, run);
    }

    /** Quick Cancel: drop every walk of this player's run, no report. */
    public static boolean cancelPlayer(UUID playerId, @Nullable ServerLevel level) {
        Run run = ACTIVE.remove(playerId);
        if (run == null) return false;
        if (level != null) {
            for (Leg leg : run.legs) {
                if (!leg.done && level.getEntity(leg.unitId) instanceof PmcUnitEntity pmc) dropWalk(pmc);
            }
        }
        return true;
    }

    public static boolean isActive(UUID playerId) {
        return ACTIVE.containsKey(playerId);
    }

    public static boolean isSearching(PmcUnitEntity pmc) {
        for (Run run : ACTIVE.values()) {
            for (Leg leg : run.legs) {
                if (!leg.done && leg.unitId == pmc.getId()) return true;
            }
        }
        return false;
    }

    /** A new order took the unit: forget its leg without touching the order it now has. */
    public static void release(PmcUnitEntity pmc) {
        if (ACTIVE.isEmpty()) return;
        for (Run run : ACTIVE.values()) {
            for (Leg leg : run.legs) {
                if (!leg.done && leg.unitId == pmc.getId()) {
                    run.unclaim(leg.target);
                    run.missing++;
                    leg.done = true;
                }
            }
        }
    }

    /** Nearest item of {@code kit} within the search radius that this run has not already claimed. */
    @Nullable
    static Target findTarget(ServerLevel level, UUID issuer, PmcUnitEntity pmc, RoleKit.Kit kit, Run run) {
        double radius = SewvConfig.QUICK_GEAR_SEARCH_RADIUS.get();
        Target best = null;
        double bestDist = Double.MAX_VALUE;
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, pmc.getBoundingBox().inflate(radius),
                e -> e.isAlive() && kit.matches(e.getItem()))) {
            if (item.getItem().getCount() - run.claimed(item.getId()) <= 0) continue;
            double d = pmc.distanceToSqr(item);
            if (d < bestDist) {
                bestDist = d;
                best = new Target(item.getId(), item.blockPosition());
            }
        }

        BlockPos ownStockpile = ownStockpile(level, issuer);
        BlockPos chest = nearestContainer(level, pmc, radius, (be, handler) -> {
            if (be instanceof StockpileBlockEntity && !be.getBlockPos().equals(ownStockpile)) return false;
            return count(handler, kit) - run.claimed(be.getBlockPos()) > 0;
        });
        if (chest != null && pmc.distanceToSqr(Vec3.atCenterOf(chest)) < bestDist) {
            best = new Target(-1, chest);
        }
        return best;
    }

    /** Nearest loaded block entity within {@code radius} whose item handler passes {@code test}. */
    @Nullable
    static BlockPos nearestContainer(ServerLevel level, Entity origin, double radius,
                                            BiPredicate<BlockEntity, IItemHandler> test) {
        AABB box = origin.getBoundingBox().inflate(radius);
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
                    if (handler == null || !test.test(be, handler)) continue;
                    double d = origin.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                    if (d < bestDist) {
                        bestDist = d;
                        best = pos.immutable();
                    }
                }
            }
        }
        return best;
    }

    @Nullable
    private static BlockPos ownStockpile(ServerLevel level, UUID issuer) {
        FobInstance fob = FobManager.get(level).getFobForOwner(issuer);
        return fob == null ? null : fob.stockpilePos;
    }

    private static int count(IItemHandler handler, RoleKit.Kit kit) {
        int n = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack s = handler.getStackInSlot(slot);
            if (kit.matches(s)) n += s.getCount();
        }
        return n;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ACTIVE.isEmpty()) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || server.getTickCount() % POLL_INTERVAL != 0) return;

        Iterator<Map.Entry<UUID, Run>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            Run run = it.next().getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(run.playerId);
            if (player == null || !(player.level() instanceof ServerLevel level)) {
                it.remove();
                continue;
            }
            if (tick(level, player, run)) {
                it.remove();
                report(player, run);
            }
        }
    }

    private static boolean tick(ServerLevel level, ServerPlayer player, Run run) {
        boolean timedOut = level.getGameTime() >= run.deadline;
        for (Leg leg : run.legs) {
            if (leg.done) continue;
            Entity e = level.getEntity(leg.unitId);
            if (!(e instanceof PmcUnitEntity pmc) || !pmc.isAlive() || !PmcOwnerSupport.isOwner(player, pmc)) {
                finish(run, leg, false);
                continue;
            }
            if (timedOut || pmc.getVehicle() != null) {
                dropWalk(pmc);
                finish(run, leg, false);
                continue;
            }
            tickLeg(level, player, run, leg, pmc);
        }
        for (Leg leg : run.legs) {
            if (!leg.done) return false;
        }
        return true;
    }

    private static void tickLeg(ServerLevel level, ServerPlayer player, Run run, Leg leg, PmcUnitEntity pmc) {
        Vec3 at = targetPos(level, leg.target, run.kit);
        if (at == null) {
            retarget(level, player, run, leg, pmc);
            return;
        }
        if (pmc.distanceToSqr(at) > REACH_SQ) {
            pmc.setOrder(OrderType.MOVE_TO_POSITION);
            pmc.setMoveToTarget(at);
            return;
        }
        // Hands stashed mid throw: wait for them to come back rather than write under the stash.
        if (RoleKit.busy(pmc)) return;

        ItemStack one = take(level, leg.target, run.kit);
        if (one.isEmpty()) {
            retarget(level, player, run, leg, pmc);
            return;
        }
        RoleKit.equipPickup(pmc, run.kit, one);
        dropWalk(pmc);
        finish(run, leg, true);
    }

    private static void retarget(ServerLevel level, ServerPlayer player, Run run, Leg leg, PmcUnitEntity pmc) {
        run.unclaim(leg.target);
        Target next = leg.retried ? null : findTarget(level, player.getUUID(), pmc, run.kit, run);
        if (next == null) {
            dropWalk(pmc);
            leg.done = true;
            run.missing++;
            return;
        }
        leg.retried = true;
        leg.target = next;
        run.claim(next);
        walkTo(pmc, next.pos());
    }

    @Nullable
    private static Vec3 targetPos(ServerLevel level, Target t, RoleKit.Kit kit) {
        if (t.itemId() >= 0) {
            Entity e = level.getEntity(t.itemId());
            return e instanceof ItemEntity item && item.isAlive() && kit.matches(item.getItem())
                    ? item.position() : null;
        }
        if (!level.isLoaded(t.pos())) return null;
        IItemHandler h = handlerAt(level, t.pos());
        return h != null && count(h, kit) > 0 ? Vec3.atBottomCenterOf(t.pos()) : null;
    }

    private static ItemStack take(ServerLevel level, Target t, RoleKit.Kit kit) {
        if (t.itemId() >= 0) {
            if (!(level.getEntity(t.itemId()) instanceof ItemEntity item) || !kit.matches(item.getItem())) {
                return ItemStack.EMPTY;
            }
            ItemStack rest = item.getItem().copy();
            ItemStack one = rest.split(1);
            if (rest.isEmpty()) {
                item.discard();
            } else {
                item.setItem(rest);
            }
            return one;
        }
        IItemHandler h = handlerAt(level, t.pos());
        if (h == null) return ItemStack.EMPTY;
        for (int slot = 0; slot < h.getSlots(); slot++) {
            if (kit.matches(h.getStackInSlot(slot))) {
                ItemStack one = h.extractItem(slot, 1, false);
                if (!one.isEmpty()) return one;
            }
        }
        return ItemStack.EMPTY;
    }

    @Nullable
    private static IItemHandler handlerAt(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        return be == null ? null : be.getCapability(ForgeCapabilities.ITEM_HANDLER, null).orElse(null);
    }

    private static void finish(Run run, Leg leg, boolean equipped) {
        run.unclaim(leg.target);
        leg.done = true;
        if (equipped) {
            run.equipped++;
        } else {
            run.missing++;
        }
    }

    static void report(ServerPlayer player, Run run) {
        Component role = Component.translatable(run.kit.labelKey());
        NetworkHandler.orderFeedback(player, "search_gear", "message.tacz_sewv.search_gear", run.equipped,
                ChatFormatting.GREEN, role);
        if (run.missing > 0) {
            OrderReport.ok(player, NetworkHandler.orderLine("search_gear", run.missing,
                    Component.translatable("message.tacz_sewv.search_gear.missing", role)
                            .withStyle(ChatFormatting.GRAY)));
        }
    }

    private static void walkTo(PmcUnitEntity pmc, BlockPos pos) {
        pmc.setOrder(OrderType.MOVE_TO_POSITION);
        pmc.setMoveToTarget(Vec3.atBottomCenterOf(pos));
    }

    private static void dropWalk(PmcUnitEntity pmc) {
        if (pmc.getOrder() == OrderType.MOVE_TO_POSITION) {
            pmc.setOrder(OrderType.FREE_FIRE);
            pmc.getNavigation().stop();
        }
    }
}
