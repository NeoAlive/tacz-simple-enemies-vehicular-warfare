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

    public enum Action { SET_MODE, PANEL_OPEN, FRONTLINE, RELEASE }

    /** Selection sent with a Frontline order; a generous cap so a forged packet cannot allocate freely. */
    private static final int MAX_IDS = 256;

    private final Action action;
    private final boolean flag;
    private final int chunkX;
    private final int chunkZ;
    private final List<Integer> ids;

    private PacketTerritoryCommand(Action action, boolean flag, int chunkX, int chunkZ, List<Integer> ids) {
        this.action = action;
        this.flag = flag;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.ids = ids;
    }

    public static PacketTerritoryCommand setMode(boolean on) {
        return new PacketTerritoryCommand(Action.SET_MODE, on, 0, 0, List.of());
    }

    public static PacketTerritoryCommand panelOpen(boolean open) {
        return new PacketTerritoryCommand(Action.PANEL_OPEN, open, 0, 0, List.of());
    }

    public static PacketTerritoryCommand frontline(int chunkX, int chunkZ, List<Integer> unitIds) {
        return new PacketTerritoryCommand(Action.FRONTLINE, false, chunkX, chunkZ, unitIds);
    }

    public static PacketTerritoryCommand release(int unitId) {
        return new PacketTerritoryCommand(Action.RELEASE, false, 0, 0, List.of(unitId));
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
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeByte(action.ordinal());
        buf.writeBoolean(flag);
        buf.writeInt(chunkX);
        buf.writeInt(chunkZ);
        buf.writeCollection(ids, FriendlyByteBuf::writeVarInt);
    }

    public Action action() { return action; }
    public boolean flag() { return flag; }
    public int chunkX() { return chunkX; }
    public int chunkZ() { return chunkZ; }
    public List<Integer> ids() { return ids; }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) TerritoryManager.handle(player, this);
        });
        ctx.get().setPacketHandled(true);
    }
}
