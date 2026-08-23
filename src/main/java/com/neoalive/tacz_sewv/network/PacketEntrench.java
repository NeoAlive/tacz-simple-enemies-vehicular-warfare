package com.neoalive.tacz_sewv.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkEvent;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.crew.OrderAuth;
import com.neoalive.tacz_sewv.entity.ai.support.EntrenchSupport;
import com.neoalive.tacz_sewv.order.OrderFailure;
import com.neoalive.tacz_sewv.order.OrderReport;

/**
 * Player → server ENTRENCHED order: assign owned units into the trench network at {@code hitPos}.
 * {@link #MODE_DISMISS} clears the task without moving crews.
 */
public class PacketEntrench {

    public static final int MODE_ASSIGN = 0;
    public static final int MODE_DISMISS = -1;

    private final List<Integer> unitIds;
    private final int mode;
    private final BlockPos hitPos;

    public PacketEntrench(List<Integer> unitIds, int mode, BlockPos hitPos) {
        this.unitIds = unitIds;
        this.mode = mode;
        this.hitPos = hitPos;
    }

    public static PacketEntrench dismiss(List<Integer> unitIds) {
        return new PacketEntrench(unitIds, MODE_DISMISS, BlockPos.ZERO);
    }

    public PacketEntrench(FriendlyByteBuf buf) {
        this.unitIds = PacketLists.readUnitIds(buf);
        this.mode = buf.readVarInt();
        this.hitPos = buf.readBlockPos();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeCollection(this.unitIds, FriendlyByteBuf::writeVarInt);
        buf.writeVarInt(this.mode);
        buf.writeBlockPos(this.hitPos);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            Player player = ctx.get().getSender();
            if (!(player instanceof ServerPlayer sp)) return;
            if (!(sp.level() instanceof ServerLevel level)) return;

            List<AbstractUnit> units = new ArrayList<>();
            for (int id : this.unitIds) {
                Entity e = level.getEntity(id);
                if (!(e instanceof PmcUnitEntity pmc)) {
                    OrderReport.fail(sp, OrderFailure.NOT_A_UNIT);
                    continue;
                }
                if (!OrderAuth.check(sp, pmc, "PacketEntrench")) {
                    OrderReport.fail(sp, OrderFailure.NOT_OWNED);
                    continue;
                }
                units.add(pmc);
            }
            if (units.isEmpty()) {
                NetworkHandler.orderFeedback(sp, "order.tacz_sewv.entrench", 0, ChatFormatting.GRAY);
                return;
            }

            if (this.mode == MODE_DISMISS) {
                for (AbstractUnit unit : units) {
                    EntrenchSupport.clear(unit);
                }
                NetworkHandler.orderFeedback(sp, "order.tacz_sewv.entrench.dismiss", units.size(),
                        ChatFormatting.YELLOW);
                return;
            }

            int accepted = EntrenchSupport.assign(level, units, this.hitPos);
            // Units were eligible and there is still nowhere to put them, so the trench itself is
            // what is missing — the one thing the aggregate line could never distinguish.
            if (accepted == 0) OrderReport.fail(sp, OrderFailure.NO_TRENCH);
            NetworkHandler.orderFeedback(sp, "order.tacz_sewv.entrench", accepted, ChatFormatting.YELLOW);
        });
        ctx.get().setPacketHandled(true);
    }
}
