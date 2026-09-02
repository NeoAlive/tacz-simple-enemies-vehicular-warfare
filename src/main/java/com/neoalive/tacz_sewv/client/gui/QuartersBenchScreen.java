package com.neoalive.tacz_sewv.client.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.chat.Component;

import com.neoalive.tacz_sewv.fob.FobGuiSnapshot;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketAssignFobLiving;
import com.neoalive.tacz_sewv.network.PacketPlayFobAlarm;
import com.neoalive.tacz_sewv.network.PacketRouteToFob;
import com.neoalive.tacz_sewv.network.PacketToggleFobCommand;

public class QuartersBenchScreen extends FobPoolScreen {

    public QuartersBenchScreen(FobGuiSnapshot snapshot) {
        super(Component.translatable("gui.tacz_sewv.fob.title"), snapshot);
        refreshRows();
    }

    @Override
    protected FobGuiSnapshot.GuiKind guiKind() {
        return FobGuiSnapshot.GuiKind.COMMAND;
    }

    @Override
    protected List<FobButton> extraFooterButtons() {
        return List.of(
                new FobButton(
                        Component.translatable(this.snapshot.fobCommandActive()
                                ? "gui.tacz_sewv.fob.command_on" : "gui.tacz_sewv.fob.command_off"),
                        () -> NetworkHandler.CHANNEL.sendToServer(
                                new PacketToggleFobCommand(this.snapshot.commandPos()))),
                new FobButton(
                        Component.translatable("gui.tacz_sewv.fob.test_alarm"),
                        () -> NetworkHandler.CHANNEL.sendToServer(
                                new PacketPlayFobAlarm(this.snapshot.commandPos()))),
                new FobButton(
                        Component.translatable("gui.tacz_sewv.fob.route"),
                        () -> NetworkHandler.CHANNEL.sendToServer(
                                new PacketRouteToFob(this.snapshot.commandPos(), this.snapshot.anchorPos(),
                                        guiKind()))));
    }

    @Override
    protected void refreshRows() {
        List<FobRow> out = new ArrayList<>();
        for (FobGuiSnapshot.LivingRow row : this.snapshot.living()) {
            out.add(new FobRow(row.uuid(), row.name(), row.assigned()));
        }
        this.rows = out;
    }

    @Override
    protected void toggleRow(int index) {
        if (index < 0 || index >= this.rows.size()) return;
        FobRow row = this.rows.get(index);
        NetworkHandler.CHANNEL.sendToServer(
                new PacketAssignFobLiving(this.snapshot.commandPos(), row.id(), !row.assigned(),
                        this.snapshot.anchorPos(), guiKind()));
    }

    @Override
    protected Component listHint() {
        return Component.translatable("gui.tacz_sewv.fob.units_hint", assignedCount(), this.rows.size());
    }

    @Override
    protected Component listCaption() {
        return Component.translatable("gui.tacz_sewv.fob.units");
    }
}
