package com.neoalive.tacz_sewv.network;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.client.invasion.InvasionEditorClient;
import com.neoalive.tacz_sewv.invasion.force.ForceKind;
import com.neoalive.tacz_sewv.invasion.force.TeamBaseConfig;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;

/** Server → client: open the team_base config screen. */
public class PacketOpenTeamBaseGui {

    private final BlockPos pos;
    private final TeamBaseConfig config;
    private final List<String> teams;
    private final List<String> onlinePlayerNames;
    private final List<String> onlinePlayerUuids;
    private final List<String> catalog;
    /**
     * Current world pools per faction for each vehicle {@link ForceKind} — the source of the
     * editor's "fill from world pool", same data as {@code /sewv pool}.
     */
    private final Map<TankFaction, Map<ForceKind, List<String>>> worldPools;

    public PacketOpenTeamBaseGui(BlockPos pos, TeamBaseConfig config, List<String> teams,
                                 List<String> onlinePlayerNames, List<String> onlinePlayerUuids,
                                 List<String> catalog, Map<TankFaction, Map<ForceKind, List<String>>> worldPools) {
        this.pos = pos;
        this.config = config;
        this.teams = teams;
        this.onlinePlayerNames = onlinePlayerNames;
        this.onlinePlayerUuids = onlinePlayerUuids;
        this.catalog = catalog;
        this.worldPools = worldPools;
    }

    public PacketOpenTeamBaseGui(FriendlyByteBuf buf) {
        this.pos = buf.readBlockPos();
        this.config = TeamBaseConfig.read(buf);
        this.teams = PacketOpenPoolEditor.readStringList(buf);
        this.onlinePlayerNames = PacketOpenPoolEditor.readStringList(buf);
        this.onlinePlayerUuids = PacketOpenPoolEditor.readStringList(buf);
        this.catalog = PacketOpenPoolEditor.readCatalogList(buf);
        Map<TankFaction, Map<ForceKind, List<String>>> pools = new EnumMap<>(TankFaction.class);
        for (TankFaction faction : TankFaction.values()) {
            Map<ForceKind, List<String>> byKind = new EnumMap<>(ForceKind.class);
            for (ForceKind kind : ForceKind.VEHICLES) {
                byKind.put(kind, PacketOpenPoolEditor.readStringList(buf));
            }
            pools.put(faction, byKind);
        }
        this.worldPools = pools;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(this.pos);
        this.config.write(buf);
        PacketOpenPoolEditor.writeStringList(buf, this.teams);
        PacketOpenPoolEditor.writeStringList(buf, this.onlinePlayerNames);
        PacketOpenPoolEditor.writeStringList(buf, this.onlinePlayerUuids);
        PacketOpenPoolEditor.writeCatalogList(buf, this.catalog);
        for (TankFaction faction : TankFaction.values()) {
            Map<ForceKind, List<String>> byKind = this.worldPools.getOrDefault(faction, Map.of());
            for (ForceKind kind : ForceKind.VEHICLES) {
                PacketOpenPoolEditor.writeStringList(buf, byKind.getOrDefault(kind, List.of()));
            }
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                InvasionEditorClient.openTeamBase(this.pos, this.config, this.teams,
                        this.onlinePlayerNames, this.onlinePlayerUuids, this.catalog, this.worldPools)));
        ctx.get().setPacketHandled(true);
    }
}
