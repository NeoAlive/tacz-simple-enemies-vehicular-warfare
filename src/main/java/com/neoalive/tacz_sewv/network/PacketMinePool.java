package com.neoalive.tacz_sewv.network;

import java.util.List;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import com.neoalive.tacz_sewv.client.radial.FieldChoice;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;
import com.neoalive.tacz_sewv.util.WorldVehiclePools;

/**
 * Server to client: the PMC MINE pool, so the wheel's Deploy Minefield leaf can step through the mine
 * types the server will actually accept. Sent on login and whenever the pools are saved.
 */
public class PacketMinePool {

    private final List<String> ids;

    public PacketMinePool(List<String> ids) {
        this.ids = List.copyOf(ids);
    }

    public PacketMinePool(FriendlyByteBuf buf) {
        this.ids = buf.readList(b -> b.readUtf(256));
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeCollection(this.ids, (b, s) -> b.writeUtf(s, 256));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> FieldChoice.setMinePool(this.ids)));
        ctx.get().setPacketHandled(true);
    }

    public static void sendTo(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        List<String> ids = WorldVehiclePools.get(level).list(TankFaction.PMC, WorldVehiclePools.Category.MINE);
        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new PacketMinePool(ids));
    }
}
