package com.neoalive.tacz_sewv.command.quick.scavenge;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
import com.atsuishio.superbwarfare.entity.vehicle.Type63Entity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.registries.ForgeRegistries;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.block.StockpileBlockEntity;
import com.neoalive.tacz_sewv.compat.CorpseCompat;
import com.neoalive.tacz_sewv.entity.ai.support.MortarSupport;
import com.neoalive.tacz_sewv.entity.ai.support.Type63Support;
import com.neoalive.tacz_sewv.entity.ai.support.mines.MineKinds;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;
import com.neoalive.tacz_sewv.util.WorldVehiclePools;

/** Finding, reaching and emptying scavenge sources. Server thread only. */
final class ScavengeSources {

    private static final double BLOCK_REACH = 2.5;
    private static final double ENTITY_REACH = 2.0;

    private ScavengeSources() {}

    /** Per-run facts every search reads: who issued it and which stockpile and hull ids apply. */
    record Context(ServerLevel level, UUID issuer, @Nullable BlockPos ownStockpile, Set<String> emplacementIds) {

        static Context of(ServerLevel level, UUID issuer, @Nullable BlockPos ownStockpile) {
            Set<String> ids = new HashSet<>();
            WorldVehiclePools pools = WorldVehiclePools.get(level);
            for (TankFaction f : TankFaction.values()) {
                ids.addAll(pools.list(f, WorldVehiclePools.Category.TOW));
                ids.addAll(pools.list(f, WorldVehiclePools.Category.MORTAR));
            }
            return new Context(level, issuer, ownStockpile, Set.copyOf(ids));
        }
    }

    /**
     * Best source for this unit in this stage: nearest, with a container or body costing more for
     * each unit already heading there. Exclusive sources someone else holds, and anything this unit
     * already tried, are skipped.
     */
    @Nullable
    static ScavengeSource find(Context ctx, ScavengeWants wants, ScavengeStage stage, double radius,
                               Map<Object, Integer> claims, Set<Object> tried) {
        PmcUnitEntity unit = wants.unit;
        ServerLevel level = ctx.level();
        AABB box = unit.getBoundingBox().inflate(radius);
        ScavengeSource best = null;
        double bestRank = Double.MAX_VALUE;

        for (Entity e : level.getEntities((Entity) null, box, ScavengeSources::interesting)) {
            ScavengeSource src = entitySource(ctx, wants, stage, e);
            if (src == null || tried.contains(src.key())) continue;
            int claimed = claims.getOrDefault(src.key(), 0);
            if (src.kind().exclusive() && claimed > 0) continue;
            double rank = ScavengeRules.rank(Math.sqrt(unit.distanceToSqr(e)), claimed);
            if (rank < bestRank) {
                bestRank = rank;
                best = src;
            }
        }

        int minCx = (int) Math.floor(box.minX) >> 4;
        int maxCx = (int) Math.floor(box.maxX) >> 4;
        int minCz = (int) Math.floor(box.minZ) >> 4;
        int maxCz = (int) Math.floor(box.maxZ) >> 4;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    BlockPos pos = be.getBlockPos();
                    if (!box.contains(Vec3.atCenterOf(pos))) continue;
                    ScavengeSource src = ScavengeSource.container(pos);
                    if (tried.contains(src.key())) continue;
                    if (be instanceof StockpileBlockEntity && !pos.equals(ctx.ownStockpile())) continue;
                    IItemHandler h = be.getCapability(ForgeCapabilities.ITEM_HANDLER, null).orElse(null);
                    if (h == null || !anyWanted(wants, stage, h)) continue;
                    if (!ScavengeAccess.claimAllows(level, pos, ctx.issuer())) continue;
                    int claimed = claims.getOrDefault(src.key(), 0);
                    double rank = ScavengeRules.rank(Math.sqrt(unit.distanceToSqr(Vec3.atCenterOf(pos))), claimed);
                    if (rank < bestRank) {
                        bestRank = rank;
                        best = src;
                    }
                }
            }
        }
        return best;
    }

    private static boolean interesting(Entity e) {
        return e.isAlive() && (e instanceof ItemEntity || e instanceof VehicleEntity
                || mineKind(e) != null || CorpseCompat.isCorpse(e));
    }

    @Nullable
    private static ScavengeSource entitySource(Context ctx, ScavengeWants wants, ScavengeStage stage, Entity e) {
        ServerLevel level = ctx.level();
        if (e instanceof ItemEntity item) {
            if (wants.wanted(stage, item.getItem()) <= 0) return null;
            if (!ScavengeAccess.claimAllows(level, item.blockPosition(), ctx.issuer())) return null;
            return ScavengeSource.entity(ScavengeSource.Kind.ITEM, item.blockPosition(), item.getId());
        }
        if (stage == ScavengeStage.EMPLACEMENT) {
            MineKinds.Kind mine = mineKind(e);
            if (mine != null) {
                if (wants.mineRoom() <= 0 || !ScavengeAccess.mineAllows(level, e, ctx.issuer())) return null;
                return ScavengeSource.entity(ScavengeSource.Kind.MINE, e.blockPosition(), e.getId());
            }
            if (e instanceof VehicleEntity hull) {
                if (wants.emplacementRoom() <= 0 || !packable(ctx, hull)) return null;
                if (!ScavengeAccess.hullAllows(level, hull, ctx.issuer())) return null;
                return ScavengeSource.entity(ScavengeSource.Kind.HULL, hull.blockPosition(), hull.getId());
            }
        }
        if (CorpseCompat.isCorpse(e)) {
            IItemHandler h = CorpseCompat.inventory(e);
            if (h == null || !anyWanted(wants, stage, h)) return null;
            if (!ScavengeAccess.claimAllows(level, e.blockPosition(), ctx.issuer())) return null;
            return ScavengeSource.entity(ScavengeSource.Kind.BODY, e.blockPosition(), e.getId());
        }
        return null;
    }

    /** A TOW or mortar class hull nobody is crewing or about to crew. */
    private static boolean packable(Context ctx, VehicleEntity hull) {
        if (!hull.isAlive() || hull.isWreck() || !hull.getPassengers().isEmpty()) return false;
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(hull.getType());
        if (id == null || !ctx.emplacementIds().contains(id.toString())) return false;
        if (hull instanceof MortarEntity mortar && MortarSupport.crewOf(mortar, null) != null) return false;
        return !(hull instanceof Type63Entity t63) || !Type63Support.isClaimed(t63, null);
    }

    @Nullable
    private static MineKinds.Kind mineKind(Entity e) {
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(e.getType());
        if (id == null) return null;
        for (MineKinds.Kind kind : MineKinds.all().values()) {
            if (kind.entity().equals(id)) return kind;
        }
        return null;
    }

    private static boolean anyWanted(ScavengeWants wants, ScavengeStage stage, IItemHandler h) {
        for (int slot = 0; slot < h.getSlots(); slot++) {
            if (wants.wanted(stage, h.getStackInSlot(slot)) > 0) return true;
        }
        return false;
    }

    /** Where to walk, or null when the source is gone. */
    @Nullable
    static Vec3 position(ServerLevel level, ScavengeSource src) {
        if (src.kind() == ScavengeSource.Kind.CONTAINER) {
            if (!level.isLoaded(src.pos()) || handlerAt(level, src.pos()) == null) return null;
            return Vec3.atBottomCenterOf(src.pos());
        }
        Entity e = level.getEntity(src.entityId());
        return e != null && e.isAlive() ? e.position() : null;
    }

    static boolean inReach(ServerLevel level, PmcUnitEntity unit, ScavengeSource src, Vec3 at) {
        if (src.kind() == ScavengeSource.Kind.CONTAINER) {
            return unit.distanceToSqr(Vec3.atCenterOf(src.pos())) <= BLOCK_REACH * BLOCK_REACH;
        }
        Entity e = level.getEntity(src.entityId());
        double r = ENTITY_REACH + (e == null ? 0.0 : e.getBbWidth() * 0.5);
        double dx = unit.getX() - at.x;
        double dz = unit.getZ() - at.z;
        return dx * dx + dz * dz <= r * r && Math.abs(unit.getY() - at.y) <= 3.0;
    }

    /** Takes everything this stage wants from the source. Returns how many items moved. */
    static int take(Context ctx, ScavengeWants wants, ScavengeStage stage, ScavengeSource src, ScavengeTally tally) {
        ServerLevel level = ctx.level();
        switch (src.kind()) {
            case CONTAINER -> {
                IItemHandler h = handlerAt(level, src.pos());
                return h == null ? 0 : drain(wants, stage, h, tally);
            }
            case BODY -> {
                IItemHandler h = CorpseCompat.inventory(level.getEntity(src.entityId()));
                return h == null ? 0 : drain(wants, stage, h, tally);
            }
            case ITEM -> {
                return level.getEntity(src.entityId()) instanceof ItemEntity item
                        ? pickUp(wants, stage, item, tally) : 0;
            }
            case HULL -> {
                return level.getEntity(src.entityId()) instanceof VehicleEntity hull
                        ? pack(ctx, wants, hull, tally) : 0;
            }
            case MINE -> {
                Entity mine = level.getEntity(src.entityId());
                return mine == null ? 0 : lift(ctx, wants, mine, tally);
            }
        }
        return 0;
    }

    private static int drain(ScavengeWants wants, ScavengeStage stage, IItemHandler source, ScavengeTally tally) {
        int moved = 0;
        for (int slot = 0; slot < source.getSlots(); slot++) {
            ItemStack in = source.getStackInSlot(slot);
            int want = wants.wanted(stage, in);
            if (want <= 0) continue;
            ItemStack probe = source.extractItem(slot, Math.min(want, in.getCount()), true);
            int fit = fits(wants, probe);
            if (fit <= 0) continue;
            ItemStack taken = source.extractItem(slot, fit, false);
            if (taken.isEmpty()) continue;
            ItemStack copy = taken.copy();
            ItemStack rest = ItemHandlerHelper.insertItemStacked(wants.pockets, taken, false);
            int got = copy.getCount() - rest.getCount();
            if (!rest.isEmpty()) giveBack(wants, source, rest);
            tally.add(stage, copy, got);
            moved += got;
        }
        return moved;
    }

    private static int pickUp(ScavengeWants wants, ScavengeStage stage, ItemEntity item, ScavengeTally tally) {
        ItemStack in = item.getItem();
        int want = wants.wanted(stage, in);
        if (want <= 0) return 0;
        int fit = fits(wants, in.copyWithCount(Math.min(want, in.getCount())));
        if (fit <= 0) return 0;
        ItemStack rest = in.copy();
        ItemStack taken = rest.split(fit);
        ItemStack copy = taken.copy();
        ItemStack left = ItemHandlerHelper.insertItemStacked(wants.pockets, taken, false);
        rest.grow(left.getCount());
        if (rest.isEmpty()) {
            item.discard();
        } else {
            item.setItem(rest);
        }
        int got = copy.getCount() - left.getCount();
        tally.add(stage, copy, got);
        return got;
    }

    /** SBW's own crowbar list for the hull, all of it or nothing, then the hull is gone. */
    private static int pack(Context ctx, ScavengeWants wants, VehicleEntity hull, ScavengeTally tally) {
        if (wants.emplacementRoom() <= 0 || !packable(ctx, hull)) return 0;
        if (!ScavengeAccess.hullAllows(ctx.level(), hull, ctx.issuer())) return 0;
        List<ItemStack> items = hull.getRetrieveItems();
        if (items.isEmpty() || emptyPockets(wants) < items.size()) return 0;
        for (ItemStack stack : items) {
            ItemStack rest = ItemHandlerHelper.insertItemStacked(wants.pockets, stack.copy(), false);
            if (!rest.isEmpty()) {
                Containers.dropItemStack(ctx.level(), wants.unit.getX(), wants.unit.getY(), wants.unit.getZ(), rest);
            }
        }
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(hull.getType());
        tally.add(ScavengeStage.EMPLACEMENT, "hull|" + id, hull.getType().getDescription(), 1);
        hull.discard();
        return 1;
    }

    private static int lift(Context ctx, ScavengeWants wants, Entity mine, ScavengeTally tally) {
        MineKinds.Kind kind = mineKind(mine);
        if (kind == null || wants.mineRoom() <= 0 || !ScavengeAccess.mineAllows(ctx.level(), mine, ctx.issuer())) {
            return 0;
        }
        var item = ForgeRegistries.ITEMS.getValue(kind.item());
        if (item == null) return 0;
        ItemStack stack = new ItemStack(item);
        if (fits(wants, stack) <= 0) return 0;
        ItemStack copy = stack.copy();
        ItemHandlerHelper.insertItemStacked(wants.pockets, stack, false);
        mine.discard();
        tally.add(ScavengeStage.EMPLACEMENT, copy, 1);
        return 1;
    }

    /** How much of {@code stack} the pockets would actually hold. */
    private static int fits(ScavengeWants wants, ItemStack stack) {
        if (stack.isEmpty()) return 0;
        ItemStack rest = ItemHandlerHelper.insertItemStacked(wants.pockets, stack.copy(), true);
        return stack.getCount() - rest.getCount();
    }

    private static int emptyPockets(ScavengeWants wants) {
        int n = 0;
        for (int slot = 0; slot < wants.pockets.getSlots(); slot++) {
            if (wants.pockets.getStackInSlot(slot).isEmpty()) n++;
        }
        return n;
    }

    private static void giveBack(ScavengeWants wants, IItemHandler source, ItemStack rest) {
        ItemStack left = ItemHandlerHelper.insertItemStacked(source, rest, false);
        if (!left.isEmpty()) {
            Containers.dropItemStack(wants.unit.level(), wants.unit.getX(), wants.unit.getY(), wants.unit.getZ(), left);
        }
    }

    @Nullable
    private static IItemHandler handlerAt(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        return be == null ? null : be.getCapability(ForgeCapabilities.ITEM_HANDLER, null).orElse(null);
    }


}
