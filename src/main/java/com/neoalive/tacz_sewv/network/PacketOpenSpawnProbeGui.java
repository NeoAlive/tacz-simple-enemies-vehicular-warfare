package com.neoalive.tacz_sewv.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.block.SpawnProbeCategory;
import com.neoalive.tacz_sewv.block.SpawnProbeInfantryEntry;
import com.neoalive.tacz_sewv.client.SpawnProbeClient;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;

/** Server → client: open the spawn_probe config screen. */
public class PacketOpenSpawnProbeGui {

    private final BlockPos pos;
    private final SpawnProbeCategory category;
    private final TankFaction factionType;
    private final List<String> vehicleList;
    private final boolean preCrewedSpawn;
    private final List<SpawnProbeInfantryEntry> infantryList;
    private final List<String> vehicleCatalog;
    private final List<String> infantryCatalog;

    public PacketOpenSpawnProbeGui(BlockPos pos, SpawnProbeCategory category, TankFaction factionType,
                                   List<String> vehicleList, boolean preCrewedSpawn,
                                   List<SpawnProbeInfantryEntry> infantryList,
                                   List<String> vehicleCatalog, List<String> infantryCatalog) {
        this.pos = pos;
        this.category = category;
        this.factionType = factionType;
        this.vehicleList = vehicleList;
        this.preCrewedSpawn = preCrewedSpawn;
        this.infantryList = infantryList;
        this.vehicleCatalog = vehicleCatalog;
        this.infantryCatalog = infantryCatalog;
    }

    public PacketOpenSpawnProbeGui(FriendlyByteBuf buf) {
        this.pos = buf.readBlockPos();
        this.category = SpawnProbeCategory.parse(buf.readUtf(16));
        this.factionType = readFaction(buf);
        this.vehicleList = PacketOpenPoolEditor.readStringList(buf);
        this.preCrewedSpawn = buf.readBoolean();
        this.infantryList = readInfantryList(buf);
        this.vehicleCatalog = PacketOpenPoolEditor.readStringList(buf);
        this.infantryCatalog = PacketOpenPoolEditor.readStringList(buf);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(this.pos);
        buf.writeUtf(this.category.name(), 16);
        buf.writeUtf(this.factionType.name(), 16);
        PacketOpenPoolEditor.writeStringList(buf, this.vehicleList);
        buf.writeBoolean(this.preCrewedSpawn);
        writeInfantryList(buf, this.infantryList);
        PacketOpenPoolEditor.writeStringList(buf, this.vehicleCatalog);
        PacketOpenPoolEditor.writeStringList(buf, this.infantryCatalog);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                SpawnProbeClient.openScreen(
                        this.pos, this.category, this.factionType,
                        this.vehicleList, this.preCrewedSpawn, this.infantryList,
                        this.vehicleCatalog, this.infantryCatalog)));
        ctx.get().setPacketHandled(true);
    }

    static TankFaction readFaction(FriendlyByteBuf buf) {
        try {
            return TankFaction.valueOf(buf.readUtf(16));
        } catch (IllegalArgumentException e) {
            return TankFaction.RU;
        }
    }

    static List<SpawnProbeInfantryEntry> readInfantryList(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        if (n < 0 || n > PacketOpenPoolEditor.MAX_STRING_LIST) {
            throw new IllegalArgumentException("infantry list size out of range: " + n);
        }
        List<SpawnProbeInfantryEntry> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(SpawnProbeInfantryEntry.read(buf));
        }
        return list;
    }

    static void writeInfantryList(FriendlyByteBuf buf, List<SpawnProbeInfantryEntry> list) {
        buf.writeVarInt(list.size());
        for (SpawnProbeInfantryEntry entry : list) {
            entry.write(buf);
        }
    }
}
