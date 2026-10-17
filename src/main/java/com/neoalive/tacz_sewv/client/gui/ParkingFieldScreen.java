package com.neoalive.tacz_sewv.client.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.chat.Component;

import com.neoalive.tacz_sewv.fob.FobGuiSnapshot;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketAssignFobVehicle;
import com.neoalive.tacz_sewv.network.PacketRouteToFob;

public class ParkingFieldScreen extends FobPoolScreen {

    public ParkingFieldScreen(FobGuiSnapshot snapshot) {
        super(Component.translatable("gui.tacz_sewv.fob.parking.title"), snapshot);
        refreshRows();
    }

    @Override
    protected FobGuiSnapshot.GuiKind guiKind() {
        return FobGuiSnapshot.GuiKind.PARKING;
    }

    @Override
    protected List<FobButton> extraFooterButtons() {
        return List.of(new FobButton(
                Component.translatable("gui.tacz_sewv.fob.route"),
                () -> NetworkHandler.CHANNEL.sendToServer(
                        new PacketRouteToFob(this.snapshot.commandPos(), this.snapshot.anchorPos(),
                                guiKind()))));
    }

    @Override
    protected void refreshRows() {
        List<FobRow> out = new ArrayList<>();
        for (FobGuiSnapshot.VehicleRow row : this.snapshot.vehicles()) {
            out.add(new FobRow(row.uuid(), row.registryId() + " @ " + row.positionText(), row.assigned()));
        }
        this.rows = out;
    }

    @Override
    protected void toggleRow(int index) {
        if (index < 0 || index >= this.rows.size()) return;
        FobRow row = this.rows.get(index);
        NetworkHandler.CHANNEL.sendToServer(
                new PacketAssignFobVehicle(this.snapshot.commandPos(), row.id(), !row.assigned(),
                        this.snapshot.anchorPos(), guiKind()));
    }

    @Override
    protected Component listHint() {
        return Component.translatable("gui.tacz_sewv.fob.vehicles_hint", assignedCount(), this.rows.size());
    }

    @Override
    protected Component listCaption() {
        return Component.translatable("gui.tacz_sewv.fob.vehicles");
    }
}
