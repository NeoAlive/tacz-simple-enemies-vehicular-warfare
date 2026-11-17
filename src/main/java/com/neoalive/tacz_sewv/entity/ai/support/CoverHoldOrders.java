package com.neoalive.tacz_sewv.entity.ai.support;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.order.OrderReport;

/**
 * The player's Cover and Hold order: reach the cover each side defines for itself (SEM's finder on
 * foot, {@code CoverQuery} for a hull), then stay there for a set time or until another order.
 *
 * <p>Persisted in the taker's {@code getPersistentData()}: a forever hold must survive a reload, and
 * everything stored is id-free (block positions and absolute game times). The taker is the unit on
 * foot, or the driver for a crew. The unit is put on FREE_FIRE when ordered, so an expired hold
 * needs nothing beyond dropping this tag.
 *
 * <p>Reports are transient batches, as in {@link SuppressOrders}: one line when the last member is in
 * position, one when the last timed hold ends. A batch lost to a reload just stays quiet.
 */
public final class CoverHoldOrders {

    public static final String TAG = "sewv:cover_hold";
    /** Hold times the wheel offers, seconds; 0 is forever. */
    public static final int MAX_HOLD_SECONDS = 120;
    public static final int HOLD_STEP_SECONDS = 10;
    /** SEM's cover search radius for the order. */
    public static final int FOOT_SEARCH_RADIUS = 12;

    private static final String SPOT = "Spot";
    private static final String ORIGIN_X = "OriginX";
    private static final String ORIGIN_Z = "OriginZ";
    private static final String THREAT_X = "ThreatX";
    private static final String THREAT_Y = "ThreatY";
    private static final String THREAT_Z = "ThreatZ";
    private static final String HOLDING = "Holding";
    private static final String HOLD_TICKS = "HoldTicks";
    private static final String DEADLINE = "Deadline";
    private static final String MOUNTED = "Mounted";
    private static final String OPEN = "Open";

    /** A read of the tag. {@code spot} is null until a cover search has run (SEM's per-tick budget). */
    public record Order(@Nullable BlockPos spot, double originX, double originZ, Vec3 threat,
                        boolean holding, boolean mounted, boolean open) {}

    private static final class Batch {
        @Nullable final UUID owner;
        int members;
        int arrived;
        int open;
        int released;

        Batch(@Nullable UUID owner) {
            this.owner = owner;
        }
    }

    private static final Map<Integer, Batch> BATCHES = new HashMap<>();

    private CoverHoldOrders() {}

    /** One batch for every unit given the order together; their tags are written by {@link #write}. */
    public static void beginBatch(Collection<? extends AbstractUnit> units, @Nullable UUID owner) {
        if (units.isEmpty()) return;
        Batch batch = new Batch(owner);
        for (AbstractUnit unit : units) {
            leaveBatch(unit);
            BATCHES.put(unit.getId(), batch);
            batch.members++;
        }
    }

    public static void write(AbstractUnit unit, @Nullable BlockPos spot, double originX, double originZ,
                             Vec3 threat, long holdTicks, boolean mounted, boolean open) {
        CompoundTag t = new CompoundTag();
        if (spot != null) t.putLong(SPOT, spot.asLong());
        t.putDouble(ORIGIN_X, originX);
        t.putDouble(ORIGIN_Z, originZ);
        t.putDouble(THREAT_X, threat.x);
        t.putDouble(THREAT_Y, threat.y);
        t.putDouble(THREAT_Z, threat.z);
        t.putLong(HOLD_TICKS, holdTicks);
        t.putBoolean(MOUNTED, mounted);
        t.putBoolean(OPEN, open);
        unit.getPersistentData().put(TAG, t);
    }

    /** Cheap presence test for hot paths; {@link #active} is the full read. */
    public static boolean has(AbstractUnit unit) {
        return unit.getPersistentData().contains(TAG);
    }

    /**
     * The live order, or null. Clears an expired hold (lazily, so an unloaded unit needs no sweep) and an
     * order whose executor no longer applies: a crew order on a unit that stopped driving, a foot order
     * on a unit that mounted.
     */
    @Nullable
    public static Order active(AbstractUnit unit, long now) {
        CompoundTag all = unit.getPersistentData();
        if (!all.contains(TAG)) return null;
        CompoundTag t = all.getCompound(TAG);
        boolean mounted = t.getBoolean(MOUNTED);
        boolean driving = unit.getVehicle() instanceof VehicleEntity hull && hull.getFirstPassenger() == unit;
        if (mounted ? !driving : unit.getVehicle() != null) {
            clear(unit);
            return null;
        }
        boolean holding = t.getBoolean(HOLDING);
        if (holding && now >= t.getLong(DEADLINE)) {
            expire(unit);
            return null;
        }
        BlockPos spot = t.contains(SPOT) ? BlockPos.of(t.getLong(SPOT)) : null;
        return new Order(spot, t.getDouble(ORIGIN_X), t.getDouble(ORIGIN_Z),
                new Vec3(t.getDouble(THREAT_X), t.getDouble(THREAT_Y), t.getDouble(THREAT_Z)), holding, mounted,
                t.getBoolean(OPEN));
    }

    public static boolean holding(AbstractUnit unit) {
        return has(unit) && unit.getPersistentData().getCompound(TAG).getBoolean(HOLDING);
    }

    public static boolean transit(AbstractUnit unit) {
        return has(unit) && !unit.getPersistentData().getCompound(TAG).getBoolean(HOLDING);
    }

    /** A late cover search (the order was written before SEM's budget let it run) fills the spot in. */
    public static void setSpot(AbstractUnit unit, BlockPos spot, boolean open) {
        if (!has(unit)) return;
        CompoundTag t = unit.getPersistentData().getCompound(TAG);
        t.putLong(SPOT, spot.asLong());
        t.putBoolean(OPEN, open);
    }

    /** In position: start the hold clock. Idempotent. */
    public static void arrive(AbstractUnit unit, long now) {
        if (!has(unit)) return;
        CompoundTag t = unit.getPersistentData().getCompound(TAG);
        if (t.getBoolean(HOLDING)) return;
        t.putBoolean(HOLDING, true);
        t.putLong(DEADLINE, CoverHoldGeometry.deadline(now, t.getLong(HOLD_TICKS)));
        Batch batch = BATCHES.get(unit.getId());
        if (batch == null) return;
        batch.arrived++;
        if (t.getBoolean(OPEN)) batch.open++;
        if (batch.arrived == batch.members) reportInCover(unit.getServer(), batch);
    }

    /** Silent cancel: a new order replaced it. */
    public static void clear(AbstractUnit unit) {
        unit.getPersistentData().remove(TAG);
        leaveBatch(unit);
    }

    public static void clearAll() {
        BATCHES.clear();
    }

    private static void expire(AbstractUnit unit) {
        unit.getPersistentData().remove(TAG);
        Batch batch = BATCHES.remove(unit.getId());
        if (batch == null) return;
        batch.released++;
        if (batch.released == batch.members) reportReleased(unit.getServer(), batch);
    }

    private static void leaveBatch(AbstractUnit unit) {
        Batch batch = BATCHES.remove(unit.getId());
        if (batch != null) batch.members--;
    }

    private static void reportInCover(@Nullable MinecraftServer server, Batch batch) {
        ServerPlayer player = owner(server, batch);
        if (player == null) return;
        Component what = batch.open == 0
                ? Component.translatable("message.tacz_sewv.cover_hold.in_cover").withStyle(ChatFormatting.GREEN)
                : Component.translatable("message.tacz_sewv.cover_hold.in_cover_open", batch.open)
                        .withStyle(ChatFormatting.YELLOW);
        OrderReport.ok(player, NetworkHandler.orderLine("cover_hold", batch.members, what));
    }

    private static void reportReleased(@Nullable MinecraftServer server, Batch batch) {
        ServerPlayer player = owner(server, batch);
        if (player == null) return;
        OrderReport.ok(player, NetworkHandler.orderLine("cover_hold", batch.members,
                Component.translatable("message.tacz_sewv.cover_hold.released").withStyle(ChatFormatting.YELLOW)));
    }

    @Nullable
    private static ServerPlayer owner(@Nullable MinecraftServer server, Batch batch) {
        if (server == null || batch.owner == null || batch.members <= 0) return null;
        return server.getPlayerList().getPlayer(batch.owner);
    }
}
