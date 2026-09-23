package com.neoalive.tacz_sewv.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import com.neoalive.tacz_sewv.client.territory.TerritoryClient;

/**
 * Server→client: the whole Territory Mode picture for one player — the mode flag, the roster of their
 * loaded PMCs, and (mode on) the front with per-chunk coverage. Pushed about once a second while the panel is
 * open or the mode is on; the client store is replaced wholesale, never merged.
 */
public class PacketTerritoryState {

    /** Roster status: why a unit cannot be posted. {@code OK} = eligible; ordinals are wire values. */
    public static final byte ST_OK = 0, ST_FOB = 1, ST_AIR = 2, ST_CREW = 3, ST_DOWNED = 4, ST_SWEEP = 5;

    /** {@code kind}: 0 on foot, 1 driving a hull. */
    public record Row(int id, String name, byte kind, float health, byte status,
                      boolean posted, boolean lost, int chunkX, int chunkZ) {}

    private final boolean modeOn;
    private final List<Row> roster;
    /** Front chunks packed like {@code ChunkPos.asLong}, and the live units posted on each (parallel arrays). */
    private final long[] front;
    private final int[] coverage;
    /** The drawn manual line (chunks in drag order) for the player's current dimension; empty = none. */
    private final long[] manualLine;

    public PacketTerritoryState(boolean modeOn, List<Row> roster, long[] front, int[] coverage, long[] manualLine) {
        this.modeOn = modeOn;
        this.roster = roster;
        this.front = front;
        this.coverage = coverage;
        this.manualLine = manualLine;
    }

    public PacketTerritoryState(FriendlyByteBuf buf) {
        this.modeOn = buf.readBoolean();
        int rows = buf.readVarInt();
        this.roster = new ArrayList<>(rows);
        for (int i = 0; i < rows; i++) {
            roster.add(new Row(buf.readVarInt(), buf.readUtf(64), buf.readByte(), buf.readFloat(), buf.readByte(),
                    buf.readBoolean(), buf.readBoolean(), buf.readInt(), buf.readInt()));
        }
        int n = buf.readVarInt();
        this.front = new long[n];
        this.coverage = new int[n];
        for (int i = 0; i < n; i++) {
            front[i] = buf.readLong();
            coverage[i] = buf.readVarInt();
        }
        int lineLen = buf.readVarInt();
        this.manualLine = new long[lineLen];
        for (int i = 0; i < lineLen; i++) manualLine[i] = buf.readLong();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(modeOn);
        buf.writeVarInt(roster.size());
        for (Row r : roster) {
            buf.writeVarInt(r.id());
            buf.writeUtf(r.name(), 64);
            buf.writeByte(r.kind());
            buf.writeFloat(r.health());
            buf.writeByte(r.status());
            buf.writeBoolean(r.posted());
            buf.writeBoolean(r.lost());
            buf.writeInt(r.chunkX());
            buf.writeInt(r.chunkZ());
        }
        buf.writeVarInt(front.length);
        for (int i = 0; i < front.length; i++) {
            buf.writeLong(front[i]);
            buf.writeVarInt(coverage[i]);
        }
        buf.writeVarInt(manualLine.length);
        for (long key : manualLine) buf.writeLong(key);
    }

    public boolean modeOn() { return modeOn; }
    public List<Row> roster() { return roster; }
    public long[] front() { return front; }
    public int[] coverage() { return coverage; }
    public long[] manualLine() { return manualLine; }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> TerritoryClient.accept(this)));
        ctx.get().setPacketHandled(true);
    }

    public static void sendTo(ServerPlayer player, PacketTerritoryState state) {
        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), state);
    }
}
