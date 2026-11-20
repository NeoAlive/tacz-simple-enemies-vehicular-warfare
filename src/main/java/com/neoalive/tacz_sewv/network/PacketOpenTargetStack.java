package com.neoalive.tacz_sewv.network;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.client.editor.TargetStackClient;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;
import com.neoalive.tacz_sewv.util.WorldTargetStack;

/** Server to client: open the target priority stack editor with every faction's stack and the defaults. */
public class PacketOpenTargetStack {

    private final Map<TankFaction, WorldTargetStack.Stack> stacks;
    private final WorldTargetStack.Stack defaults;

    public PacketOpenTargetStack(Map<TankFaction, WorldTargetStack.Stack> stacks, WorldTargetStack.Stack defaults) {
        this.stacks = stacks;
        this.defaults = defaults;
    }

    public PacketOpenTargetStack(FriendlyByteBuf buf) {
        this.stacks = readStacks(buf);
        this.defaults = readStack(buf);
    }

    public void encode(FriendlyByteBuf buf) {
        writeStacks(buf, this.stacks);
        writeStack(buf, this.defaults);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> TargetStackClient.openScreen(this.stacks, this.defaults)));
        ctx.get().setPacketHandled(true);
    }

    static Map<TankFaction, WorldTargetStack.Stack> readStacks(FriendlyByteBuf buf) {
        Map<TankFaction, WorldTargetStack.Stack> map = new EnumMap<>(TankFaction.class);
        for (TankFaction f : TankFaction.values()) map.put(f, readStack(buf));
        return map;
    }

    static void writeStacks(FriendlyByteBuf buf, Map<TankFaction, WorldTargetStack.Stack> map) {
        for (TankFaction f : TankFaction.values()) {
            WorldTargetStack.Stack s = map.get(f);
            writeStack(buf, s == null ? new WorldTargetStack.Stack(List.of(), List.of()) : s);
        }
    }

    static WorldTargetStack.Stack readStack(FriendlyByteBuf buf) {
        List<String> priority = PacketOpenPoolEditor.readStringList(buf);
        List<String> excluded = PacketOpenPoolEditor.readStringList(buf);
        return new WorldTargetStack.Stack(priority, excluded);
    }

    static void writeStack(FriendlyByteBuf buf, WorldTargetStack.Stack s) {
        PacketOpenPoolEditor.writeStringList(buf, s.priority());
        PacketOpenPoolEditor.writeStringList(buf, s.excluded());
    }
}
