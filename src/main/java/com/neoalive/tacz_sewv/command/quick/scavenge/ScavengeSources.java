package com.neoalive.tacz_sewv.command.quick.scavenge;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
import net.minecraftforge.items.IItemHandlerModifiable;
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

/**
 * Finding, reaching and emptying scavenge sources. Server thread only.
 *
 * <p>Every candidate is first planned against what the run has already reserved there, and only a
 * source with something left for this unit counts. The nearest of those wins, so a group splits
 * across sources once one cannot serve them all.
 */
final class ScavengeSources {

    private static final double BLOCK_REACH = 2.5;
    private static final double ENTITY_REACH = 2.0;
    /** The reservation key of a whole hull or mine. */
    private static final String WHOLE = "whole";

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

    /** A source and what this unit means to take from it. */
    record Pick(ScavengeSource source, Map<String, Integer> plan) {}

    /** One thing a slot can hand over: the slot's own stack, or the rounds in a dropped gun. */
    private record Offer(int slot, String key, ItemStack sample, int count, boolean magazine) {}

    @Nullable
    static Pick find(Context ctx, ScavengeWants wants, ScavengeStage stage, double radius, Reservations reserved,
                     Set<Object> tried) {
        PmcUnitEntity unit = wants.unit;
        ServerLevel level = ctx.level();
        AABB box = unit.getBoundingBox().inflate(radius);
        Pick best = null;
        double bestDist = Double.MAX_VALUE;

        for (Entity e : level.getEntities((Entity) null, box, ScavengeSources::interesting)) {
            double d = unit.distanceToSqr(e);
            if (d >= bestDist) continue;
            Pick pick = entityPick(ctx, wants, stage, e, reserved, tried);
            if (pick != null) {
                bestDist = d;
                best = pick;
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
                    double d = unit.distanceToSqr(Vec3.atCenterOf(pos));
                    if (d >= bestDist) continue;
                    ScavengeSource src = ScavengeSource.container(pos);
                    if (tried.contains(src.key())) continue;
                    if (be instanceof StockpileBlockEntity && !pos.equals(ctx.ownStockpile())) continue;
                    IItemHandler h = be.getCapability(ForgeCapabilities.ITEM_HANDLER, null).orElse(null);
                    if (h == null) continue;
                    Map<String, Integer> plan = plan(wants, stage, offers(h, stage, false), src.key(), reserved);
                    if (plan.isEmpty() || !ScavengeAccess.claimAllows(level, pos, ctx.issuer())) continue;
                    bestDist = d;
                    best = new Pick(src, plan);
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
    private static Pick entityPick(Context ctx, ScavengeWants wants, ScavengeStage stage, Entity e,
                                   Reservations reserved, Set<Object> tried) {
        ServerLevel level = ctx.level();
        ScavengeSource src;
        Map<String, Integer> plan;
        if (e instanceof ItemEntity item) {
            src = ScavengeSource.entity(ScavengeSource.Kind.ITEM, item.blockPosition(), item.getId());
            if (tried.contains(src.key())) return null;
            plan = plan(wants, stage, offers(new GroundItem(item), stage, true), src.key(), reserved);
        } else if (CorpseCompat.isCorpse(e)) {
            src = ScavengeSource.entity(ScavengeSource.Kind.BODY, e.blockPosition(), e.getId());
            if (tried.contains(src.key())) return null;
            IItemHandler h = CorpseCompat.inventory(e);
            if (h == null) return null;
            plan = plan(wants, stage, offers(h, stage, true), src.key(), reserved);
        } else if (stage == ScavengeStage.EMPLACEMENT && mineKind(e) != null) {
            src = ScavengeSource.entity(ScavengeSource.Kind.MINE, e.blockPosition(), e.getId());
            if (tried.contains(src.key()) || reserved.any(src.key()) || wants.mineRoom() <= 0) return null;
            if (!ScavengeAccess.mineAllows(level, e, ctx.issuer())) return null;
            return new Pick(src, Map.of(WHOLE, 1));
        } else if (stage == ScavengeStage.EMPLACEMENT && e instanceof VehicleEntity hull) {
            src = ScavengeSource.entity(ScavengeSource.Kind.HULL, hull.blockPosition(), hull.getId());
            if (tried.contains(src.key()) || reserved.any(src.key()) || wants.emplacementRoom() <= 0) return null;
            if (!packable(ctx, hull) || !ScavengeAccess.hullAllows(level, hull, ctx.issuer())) return null;
            return new Pick(src, Map.of(WHOLE, 1));
        } else {
            return null;
        }
        if (plan.isEmpty() || !ScavengeAccess.claimAllows(level, e.blockPosition(), ctx.issuer())) return null;
        return new Pick(src, plan);
    }

    /**
     * Rounds are unloaded from guns only where a gun was dropped (the ground, a body), never from
     * a container someone stocked.
     */
    private static List<Offer> offers(IItemHandler h, ScavengeStage stage, boolean unloadGuns) {
        List<Offer> out = new java.util.ArrayList<>();
        for (int slot = 0; slot < h.getSlots(); slot++) {
            ItemStack s = h.getStackInSlot(slot);
            if (s.isEmpty()) continue;
            if (unloadGuns && stage == ScavengeStage.AMMO) {
                ItemStack round = ScavengeItems.magazineRound(s);
                if (!round.isEmpty()) {
                    out.add(new Offer(slot, ScavengeTally.keyOf(round), round, ScavengeItems.magazineCount(s), true));
                    continue;
                }
            }
            out.add(new Offer(slot, ScavengeTally.keyOf(s), s, s.getCount(), false));
        }
        return out;
    }

    /** Per item: what the unit wants of it, out of what is there and not already reserved. */
    private static Map<String, Integer> plan(ScavengeWants wants, ScavengeStage stage, List<Offer> offers,
                                             Object sourceKey, Reservations reserved) {
        Map<String, Integer> avail = new LinkedHashMap<>();
        Map<String, ItemStack> samples = new HashMap<>();
        for (Offer o : offers) {
            avail.merge(o.key(), o.count(), Integer::sum);
            samples.putIfAbsent(o.key(), o.sample());
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> a : avail.entrySet()) {
            ItemStack sample = samples.get(a.getKey());
            int n = ScavengeRules.planned(wants.wanted(stage, sample), a.getValue(),
                    reserved.reserved(sourceKey, a.getKey()));
            if (n > 0 && stage != ScavengeStage.ARMOR) n = Math.min(n, fits(wants, sample.copyWithCount(n)));
            if (n > 0) out.put(a.getKey(), n);
        }
        return out;
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

    /** Takes up to the unit's plan from the source. Returns how many items moved. */
    static int take(Context ctx, ScavengeWants wants, ScavengeStage stage, ScavengeSource src,
                    Map<String, Integer> plan, ScavengeTally tally) {
        ServerLevel level = ctx.level();
        return switch (src.kind()) {
            case CONTAINER -> {
                IItemHandler h = handlerAt(level, src.pos());
                yield h == null ? 0 : drain(wants, stage, h, false, plan, tally);
            }
            case BODY -> {
                IItemHandler h = CorpseCompat.inventory(level.getEntity(src.entityId()));
                yield h == null ? 0 : drain(wants, stage, h, true, plan, tally);
            }
            case ITEM -> level.getEntity(src.entityId()) instanceof ItemEntity item
                    ? drain(wants, stage, new GroundItem(item), true, plan, tally) : 0;
            case HULL -> level.getEntity(src.entityId()) instanceof VehicleEntity hull
                    ? pack(ctx, wants, hull, tally) : 0;
            case MINE -> {
                Entity mine = level.getEntity(src.entityId());
                yield mine == null ? 0 : lift(ctx, wants, mine, tally);
            }
        };
    }

    private static int drain(ScavengeWants wants, ScavengeStage stage, IItemHandler source, boolean unloadGuns,
                             Map<String, Integer> plan, ScavengeTally tally) {
        Map<String, Integer> left = new HashMap<>(plan);
        int moved = 0;
        for (Offer o : offers(source, stage, unloadGuns)) {
            int limit = left.getOrDefault(o.key(), 0);
            if (limit <= 0) continue;
            int n = Math.min(limit, Math.min(o.count(), wants.wanted(stage, o.sample())));
            if (n <= 0) continue;
            ItemStack got;
            if (o.magazine()) {
                if (!(source instanceof IItemHandlerModifiable m)) continue;
                n = stage == ScavengeStage.ARMOR ? n : Math.min(n, fits(wants, o.sample().copyWithCount(n)));
                if (n <= 0) continue;
                m.setStackInSlot(o.slot(), ScavengeItems.unload(source.getStackInSlot(o.slot()), n));
                got = o.sample().copyWithCount(n);
            } else {
                if (stage != ScavengeStage.ARMOR) {
                    n = Math.min(n, fits(wants, source.extractItem(o.slot(), n, true)));
                    if (n <= 0) continue;
                }
                got = source.extractItem(o.slot(), n, false);
                if (got.isEmpty()) continue;
            }
            int count = got.getCount();
            receive(wants, stage, got, source);
            tally.add(stage, got.copyWithCount(1), count);
            left.merge(o.key(), -count, Integer::sum);
            moved += count;
        }
        return moved;
    }

    /** Into the pockets, or onto the body for armor. What does not stay goes back to the source. */
    private static void receive(ScavengeWants wants, ScavengeStage stage, ItemStack got, IItemHandler source) {
        if (stage == ScavengeStage.ARMOR) {
            ItemStack old = wants.wear(got);
            if (!old.isEmpty()) giveBack(wants, source, old);
            return;
        }
        ItemStack rest = ItemHandlerHelper.insertItemStacked(wants.pockets, got.copy(), false);
        if (!rest.isEmpty()) giveBack(wants, source, rest);
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

    /** An item on the ground as a one-slot, take-only handler. */
    private static final class GroundItem implements IItemHandlerModifiable {

        private final ItemEntity item;

        GroundItem(ItemEntity item) {
            this.item = item;
        }

        @Override
        public int getSlots() {
            return 1;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return this.item.isAlive() ? this.item.getItem() : ItemStack.EMPTY;
        }

        @Override
        public void setStackInSlot(int slot, ItemStack stack) {
            if (stack.isEmpty()) {
                this.item.discard();
            } else {
                this.item.setItem(stack);
            }
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return stack;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            ItemStack in = getStackInSlot(slot);
            if (in.isEmpty() || amount <= 0) return ItemStack.EMPTY;
            int n = Math.min(amount, in.getCount());
            ItemStack out = in.copyWithCount(n);
            if (!simulate) {
                ItemStack rest = in.copy();
                rest.shrink(n);
                setStackInSlot(slot, rest);
            }
            return out;
        }

        @Override
        public int getSlotLimit(int slot) {
            return 64;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return false;
        }
    }
}
