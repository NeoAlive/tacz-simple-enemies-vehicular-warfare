package com.neoalive.tacz_sewv.client.xaero;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import xaero.map.gui.IRightClickableElement;
import xaero.map.gui.dropdown.rightclick.RightClickOption;

import com.neoalive.tacz_sewv.client.PreferredPathwaysClient;
import com.neoalive.tacz_sewv.client.TdtSelection;
import com.neoalive.tacz_sewv.map.VehicleMarker;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketFunnelPreferredPathway;
import com.neoalive.tacz_sewv.network.PacketSavePreferredPathway;

/** Map menu entries for saved preferred pathways: funnel, edit, delete. */
public final class PathwayPathMenuOptions {

    private PathwayPathMenuOptions() {}

    public static List<RightClickOption> all(int startIndex, IRightClickableElement target,
                                             ResourceKey<Level> dimension) {
        Map<String, List<BlockPos>> paths = PreferredPathwaysClient.forDimension(dimension);
        if (paths.isEmpty()) return List.of();

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        boolean sameDimension = player != null && dimension.equals(player.level().dimension());
        int onFoot = nearbyGroundCount();

        List<RightClickOption> options = new ArrayList<>();
        int idx = startIndex;
        for (Map.Entry<String, List<BlockPos>> entry : paths.entrySet()) {
            String pathId = entry.getKey();
            options.add(new FunnelOption(idx++, target, pathId, dimension, onFoot, sameDimension));
            options.add(new EditOption(idx++, target, pathId, dimension, entry.getValue(), sameDimension));
            options.add(new DeleteOption(idx++, target, pathId, dimension, sameDimension));
        }
        return options;
    }

    static int nearbyGroundCount() {
        TdtSelection.scan();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        int count = 0;
        for (TdtSelection.Entry e : TdtSelection.scanned()) {
            if (e.kind() == VehicleMarker.Kind.ROTARY_WING
                    || e.kind() == VehicleMarker.Kind.FIXED_WING
                    || e.kind() == VehicleMarker.Kind.SURFACE_COMBATANT) {
                continue;
            }
            int key = e.vehicleId() >= 0 ? e.vehicleId() : e.id();
            if (seen.add(key)) count++;
        }
        return count;
    }

    private static final class FunnelOption extends RightClickOption {
        private final String pathId;
        private final ResourceKey<Level> dimension;

        FunnelOption(int index, IRightClickableElement target, String pathId,
                     ResourceKey<Level> dimension, int unitCount, boolean sameDimension) {
            super("gui.tacz_sewv.map.funnel_path", Style.EMPTY.withColor(ChatFormatting.AQUA),
                    index, target);
            this.pathId = pathId;
            this.dimension = dimension;
            setActive(sameDimension);
            setNameFormatArgs(pathId, unitCount);
        }

        @Override
        public void onAction(net.minecraft.client.gui.screens.Screen screen) {
            Player player = Minecraft.getInstance().player;
            if (player == null || !this.dimension.equals(player.level().dimension())) return;
            NetworkHandler.CHANNEL.sendToServer(new PacketFunnelPreferredPathway(List.of(), this.pathId));
        }
    }

    private static final class EditOption extends RightClickOption {
        private final String pathId;
        private final ResourceKey<Level> dimension;
        private final List<BlockPos> nodes;

        EditOption(int index, IRightClickableElement target, String pathId,
                   ResourceKey<Level> dimension, List<BlockPos> nodes, boolean sameDimension) {
            super("gui.tacz_sewv.map.edit_path", Style.EMPTY.withColor(ChatFormatting.YELLOW),
                    index, target);
            this.pathId = pathId;
            this.dimension = dimension;
            this.nodes = nodes;
            setActive(sameDimension && nodes.size() >= 2);
            setNameFormatArgs(pathId);
        }

        @Override
        public void onAction(net.minecraft.client.gui.screens.Screen screen) {
            Player player = Minecraft.getInstance().player;
            if (player == null || !this.dimension.equals(player.level().dimension())) return;
            if (PathwayPlot.armEdit(this.dimension, this.pathId, this.nodes)) {
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.translatable(
                                "message.tacz_sewv.pathway.editing", this.pathId)
                                .withStyle(ChatFormatting.GREEN),
                        true);
            }
        }
    }

    private static final class DeleteOption extends RightClickOption {
        private final String pathId;
        private final ResourceKey<Level> dimension;

        DeleteOption(int index, IRightClickableElement target, String pathId,
                     ResourceKey<Level> dimension, boolean sameDimension) {
            super("gui.tacz_sewv.map.delete_path", Style.EMPTY.withColor(ChatFormatting.RED),
                    index, target);
            this.pathId = pathId;
            this.dimension = dimension;
            setActive(sameDimension);
            setNameFormatArgs(pathId);
        }

        @Override
        public void onAction(net.minecraft.client.gui.screens.Screen screen) {
            Player player = Minecraft.getInstance().player;
            if (player == null || !this.dimension.equals(player.level().dimension())) return;
            NetworkHandler.CHANNEL.sendToServer(
                    new PacketSavePreferredPathway(this.pathId, this.dimension, List.of(), true));
        }
    }
}
