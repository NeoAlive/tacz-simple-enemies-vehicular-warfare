package com.neoalive.tacz_sewv.network;

import com.neoalive.tacz_sewv.client.InvasionBillboards;
import com.neoalive.tacz_sewv.invasion.InvasionBillboard;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Server → client: replace the client's invasion billboard list for the sender's dimension view. */
public class PacketInvasionBillboards {

    private final List<InvasionBillboard> billboards;

    public PacketInvasionBillboards(List<InvasionBillboard> billboards) {
        this.billboards = billboards;
    }

    public PacketInvasionBillboards(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<InvasionBillboard> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(InvasionBillboard.decode(buf));
        }
        this.billboards = list;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(this.billboards.size());
        for (InvasionBillboard b : this.billboards) {
            b.encode(buf);
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> InvasionBillboards.accept(this.billboards)));
        ctx.get().setPacketHandled(true);
    }
}
