package com.neoalive.tacz_sewv.client.xaero;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import xaero.map.WorldMapSession;
import xaero.map.gui.GuiMap;

import com.neoalive.tacz_sewv.client.zone.ZoneViewClient;

/** Opens Xaero's World Map in spawn zone view. The only class that names GuiMap for it. */
public final class XaeroZoneView {

    private XaeroZoneView() {}

    public static void open(Screen parent) {
        Minecraft mc = Minecraft.getInstance();
        WorldMapSession session = WorldMapSession.getCurrentSession();
        if (session == null || !session.isUsable() || mc.getCameraEntity() == null) {
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.translatable("gui.tacz_sewv.zones.map_unavailable"), true);
            }
            return;
        }
        ZoneViewClient.openView();
        mc.setScreen(new GuiMap(parent, parent, session.getMapProcessor(), mc.getCameraEntity()));
    }
}
