package com.neoalive.tacz_sewv.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.territory.TerritoryManager;

/** Client→server: everything the RTS panel can ask of Territory Mode. Validated entirely server-side. */
public class PacketTerritoryCommand {

    public enum Action { SET_MODE, PANEL_OPEN, FRONTLINE, RELEASE, MANUAL_FRONTLINE, CLEAR_LINE, PLAN_BAKE, PLAN_START, PLAN_STOP, PLAN_CLEAR }

    /** Selection sent with a Frontline order; a generous cap so a forged packet cannot allocate freely. */
    private static final int MAX_IDS = 256;
    /** A drawn line's chunks: far more than any claim's front will ever need, but bounded against a forged packet. */
    private static final int MAX_CHUNKS = 1024;

    private final Action action;
    private final boolean flag;
    private final int chunkX;
    private final int chunkZ;
    private final List<Integer> ids;
    /** Manual mode: chunks visited by the drag, packed like {@code ChunkPos.asLong}, in drag order. */
    private final List<Long> chunks;

    private PacketTerritoryCommand(Action action, boolean flag, int chunkX, int chunkZ, List<Integer> ids,
                                  List<Long> chunks) {
        this.action = action;
        this.flag = flag;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.ids = ids;
        this.chunks = chunks;
    }

    public static PacketTerritoryCommand setMode(boolean on) {
        return new PacketTerritoryCommand(Action.SET_MODE, on, 0, 0, List.of(), List.of());
    }

    public static PacketTerritoryCommand panelOpen(boolean open) {
        return new PacketTerritoryCommand(Action.PANEL_OPEN, open, 0, 0, List.of(), List.of());
    }

    public static PacketTerritoryCommand frontline(int chunkX, int chunkZ, List<Integer> unitIds) {
        return new PacketTerritoryCommand(Action.FRONTLINE, false, chunkX, chunkZ, unitIds, List.of());
    }

    public static PacketTerritoryCommand release(int unitId) {
        return new PacketTerritoryCommand(Action.RELEASE, false, 0, 0, List.of(unitId), List.of());
    }

    /** Manual Frontline: the chunks the right-drag crossed, in order. Empty is legal; the server reports it. */
    public static PacketTerritoryCommand manualFrontline(List<Long> chunks, List<Integer> unitIds) {
        return new PacketTerritoryCommand(Action.MANUAL_FRONTLINE, false, 0, 0, unitIds, chunks);
    }

    public static PacketTerritoryCommand clearLine() {
        return new PacketTerritoryCommand(Action.CLEAR_LINE, false, 0, 0, List.of(), List.of());
    }

    /** Advance Plan: bake the painted region (an unordered chunk set; the server validates everything). */
    public static PacketTerritoryCommand planBake(List<Long> chunks) {
        return new PacketTerritoryCommand(Action.PLAN_BAKE, false, 0, 0, List.of(), chunks);
    }

    /** Start the baked plan with the selected posted units. */
    public static PacketTerritoryCommand planStart(List<Integer> unitIds) {
        return new PacketTerritoryCommand(Action.PLAN_START, false, 0, 0, unitIds, List.of());
    }

    public static PacketTerritoryCommand planStop() {
        return new PacketTerritoryCommand(Action.PLAN_STOP, false, 0, 0, List.of(), List.of());
    }

    public static PacketTerritoryCommand planClear() {
        return new PacketTerritoryCommand(Action.PLAN_CLEAR, false, 0, 0, List.of(), List.of());
    }

    public PacketTerritoryCommand(FriendlyByteBuf buf) {
        Action[] all = Action.values();
        int ordinal = buf.readByte();
        this.action = ordinal >= 0 && ordinal < all.length ? all[ordinal] : Action.PANEL_OPEN;
        this.flag = buf.readBoolean();
        this.chunkX = buf.readInt();
        this.chunkZ = buf.readInt();
        List<Integer> read = buf.readCollection(ArrayList::new, FriendlyByteBuf::readVarInt);
        this.ids = read.size() > MAX_IDS ? new ArrayList<>(read.subList(0, MAX_IDS)) : read;
        List<Long> drawn = buf.readCollection(ArrayList::new, FriendlyByteBuf::readLong);
        this.chunks = drawn.size() > MAX_CHUNKS ? new ArrayList<>(drawn.subList(0, MAX_CHUNKS)) : drawn;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeByte(action.ordinal());
        buf.writeBoolean(flag);
        buf.writeInt(chunkX);
        buf.writeInt(chunkZ);
        buf.writeCollection(ids, FriendlyByteBuf::writeVarInt);
        buf.writeCollection(chunks.size() > MAX_CHUNKS ? chunks.subList(0, MAX_CHUNKS) : chunks, FriendlyByteBuf::writeLong);
    }

    public Action action() { return action; }
    public boolean flag() { return flag; }
    public int chunkX() { return chunkX; }
    public int chunkZ() { return chunkZ; }
    public List<Integer> ids() { return ids; }
    public List<Long> chunks() { return chunks; }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) TerritoryManager.handle(player, this);
        });
        ctx.get().setPacketHandled(true);
    }
}
