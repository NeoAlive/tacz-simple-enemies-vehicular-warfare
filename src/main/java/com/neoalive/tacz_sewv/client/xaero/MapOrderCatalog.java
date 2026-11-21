package com.neoalive.tacz_sewv.client.xaero;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.entity.player.Player;

import com.neoalive.tacz_sewv.bridge.IHelicopterPilot;
import com.neoalive.tacz_sewv.client.MapMarkers;
import com.neoalive.tacz_sewv.client.radial.OrderScales;
import com.neoalive.tacz_sewv.client.radial.WedgeEntry;
import com.neoalive.tacz_sewv.client.radial.WheelUi;
import com.neoalive.tacz_sewv.command.quick.QuickCommandRegistry;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketExitPlatoon;
import com.neoalive.tacz_sewv.network.PacketHelicopterCommand;
import com.neoalive.tacz_sewv.network.PacketQuickCommand;
import com.neoalive.tacz_sewv.network.PacketToggleAutoOrders;

/**
 * The world map's orders, sorted the way the quick wheel sorts them. Each category becomes one row in
 * Xaero's right-click menu and opens {@link OrderFlyout}. Only orders that make sense from the map
 * are here: anything that needs the player's own position, view or a unit on foot stays on the
 * wheel. A category left with no entry is not offered.
 */
public final class MapOrderCatalog {

    public interface Run {
        void run(UnitOrderOption.MapClick click);
    }

    /**
     * @param wheelId the wheel leaf this mirrors, for its description (may be empty)
     * @param label   the drawn label, glyph included
     * @param scale   a value the row steps with the scroll wheel, or null
     */
    public record Entry(String wheelId, String label, int accent, boolean enabled, @Nullable OrderScales.Scale scale,
                        Run run) {}

    public record Category(String id, String label, String glyph, int accent, List<Entry> entries) {}

    private MapOrderCatalog() {}

    public static List<Category> categories(int selectedCount, int attackTargetId) {
        List<Category> out = new ArrayList<>();
        add(out, "general", java.util.Arrays.asList(
                action(QuickCommandRegistry.ID_QUICK_FOLLOW, UnitOrderOption.Action.FOLLOW, selectedCount, attackTargetId),
                action(QuickCommandRegistry.ID_QUICK_HOLD, UnitOrderOption.Action.HOLD, selectedCount, attackTargetId),
                action(QuickCommandRegistry.ID_QUICK_MOVE, UnitOrderOption.Action.MOVE, selectedCount, attackTargetId),
                attackTargetId >= 0
                        ? action(QuickCommandRegistry.ID_QUICK_ATTACK, UnitOrderOption.Action.ATTACK_THAT,
                        selectedCount, attackTargetId) : null,
                action(QuickCommandRegistry.ID_QUICK_SUPPRESS, UnitOrderOption.Action.SUPPRESS_HERE, selectedCount,
                        attackTargetId),
                ceaseFire(selectedCount, attackTargetId),
                action(QuickCommandRegistry.ID_QUICK_FREE_FIRE, UnitOrderOption.Action.FREE_FIRE, selectedCount,
                        attackTargetId),
                action(QuickCommandRegistry.ID_SET_GUARD, UnitOrderOption.Action.SET_GUARD, selectedCount, attackTargetId),
                action(QuickCommandRegistry.ID_REACH_GUARD, UnitOrderOption.Action.REACH_GUARD, selectedCount,
                        attackTargetId)));
        add(out, "crew", List.of(
                action(QuickCommandRegistry.ID_BAIL_OUT, UnitOrderOption.Action.BAIL_OUT, selectedCount, attackTargetId)));
        add(out, "land", List.of(
                action(QuickCommandRegistry.ID_QUICK_ENTRENCH, UnitOrderOption.Action.ENTRENCH_HERE, selectedCount,
                        attackTargetId),
                action(QuickCommandRegistry.ID_QUICK_ROUTE_FOB, UnitOrderOption.Action.ROUTE_TO_FOB, selectedCount,
                        attackTargetId)));
        add(out, "air", List.of(
                scaled(action(QuickCommandRegistry.ID_QUICK_TAKEOFF, UnitOrderOption.Action.TAKEOFF, selectedCount,
                        attackTargetId), OrderScales.Scale.ALTITUDE),
                action(QuickCommandRegistry.ID_QUICK_LANDING, UnitOrderOption.Action.LAND_AIRPORT, selectedCount,
                        attackTargetId),
                leaf(QuickCommandRegistry.ID_QUICK_LAND_HELIPAD, selectedCount > 0, null, c -> {
                    Set<Integer> drivers = MapMarkers.selected();
                    NetworkHandler.CHANNEL.sendToServer(new PacketHelicopterCommand(new ArrayList<>(drivers),
                            IHelicopterPilot.HELI_CMD_LAND_HELIPAD, null, 0));
                }),
                action(QuickCommandRegistry.ID_QUICK_EMERGENCY_LAND, UnitOrderOption.Action.EMERGENCY_LAND, selectedCount,
                        attackTargetId),
                action(QuickCommandRegistry.ID_PARATROOP, UnitOrderOption.Action.PARATROOP, selectedCount,
                        attackTargetId)));
        add(out, "procedures", List.of(
                scaled(action(QuickCommandRegistry.ID_QUICK_SEARCH, UnitOrderOption.Action.SAD_HERE, selectedCount,
                        attackTargetId), OrderScales.Scale.SEARCH),
                scaled(action(QuickCommandRegistry.ID_QUICK_PATROL, UnitOrderOption.Action.PATROL_HERE, selectedCount,
                        attackTargetId), OrderScales.Scale.PATROL),
                leaf(QuickCommandRegistry.ID_QUICK_COVER_HOLD, selectedCount > 0, OrderScales.Scale.HOLD, c -> {
                    Player player = Minecraft.getInstance().player;
                    if (player == null) return;
                    String id = QuickCommandRegistry.coverHoldId(OrderScales.get(OrderScales.Scale.HOLD));
                    NetworkHandler.CHANNEL.sendToServer(new PacketQuickCommand(id, new ArrayList<>(MapMarkers.selected()),
                            true, UnitOrderOption.mapPos(player, c.x(), c.y(), c.z())));
                }),
                action(QuickCommandRegistry.ID_DISMISS, UnitOrderOption.Action.DISMISS, selectedCount, attackTargetId)));
        add(out, "platoon", List.of(
                leaf(QuickCommandRegistry.ID_PLATOON_EXIT, selectedCount > 0, null, c ->
                        NetworkHandler.CHANNEL.sendToServer(new PacketExitPlatoon(new ArrayList<>(MapMarkers.selected())))),
                leaf(QuickCommandRegistry.ID_PLATOON_AUTO_ORDERS, selectedCount > 0, null, c ->
                        NetworkHandler.CHANNEL.sendToServer(
                                new PacketToggleAutoOrders(new ArrayList<>(MapMarkers.selected()))))));
        List<Entry> map = new ArrayList<>();
        map.add(mapAction(UnitOrderOption.Action.CRUISE, selectedCount, attackTargetId));
        map.add(mapAction(UnitOrderOption.Action.PREFERRED_PATHWAYS, selectedCount, attackTargetId));
        out.add(new Category("map", I18n.get("gui.tacz_sewv.map.cat.map"), "▦", 0x9AA7B4, map));
        return out;
    }

    /** The wheel's root Cancel, offered as a plain row of its own. */
    public static Entry cancel(int selectedCount) {
        return leaf(QuickCommandRegistry.ID_QUICK_CANCEL, selectedCount > 0, null, c ->
                NetworkHandler.CHANNEL.sendToServer(new PacketQuickCommand(QuickCommandRegistry.ID_QUICK_CANCEL,
                        new ArrayList<>(MapMarkers.selected()), true, null)));
    }

    private static void add(List<Category> out, String id, List<Entry> entries) {
        List<Entry> kept = new ArrayList<>();
        for (Entry e : entries) {
            if (e != null) kept.add(e);
        }
        if (kept.isEmpty()) return;
        String key = QuickCommandRegistry.LABEL_PREFIX + "cat." + id;
        WedgeEntry.CategoryEntry wheel = wheelCategory(key);
        String glyph = wheel != null ? wheel.icon() : "•";
        int accent = wheel != null ? wheel.accentRgb() : 0xFFFFFF;
        out.add(new Category(id, I18n.get(key), glyph, accent, kept));
    }

    @Nullable
    private static WedgeEntry.CategoryEntry wheelCategory(String labelKey) {
        for (WedgeEntry e : QuickCommandRegistry.rootWedges()) {
            if (e instanceof WedgeEntry.CategoryEntry c && c.labelKey().equals(labelKey)) return c;
        }
        return null;
    }

    private static Entry action(String wheelId, UnitOrderOption.Action action, int selectedCount, int attackTargetId) {
        WedgeEntry.PipelineEntry leaf = WheelUi.leaf(wheelId);
        int accent = leaf != null ? leaf.accentRgb() : 0xFFFFFF;
        String label = I18n.get(UnitOrderOption.labelKey(action), selectedCount);
        return new Entry(wheelId, label, accent, UnitOrderOption.active(action, selectedCount, attackTargetId), null,
                c -> UnitOrderOption.perform(action, c));
    }

    /** Cease Fire with its time scale: 0 keeps SEM's own order packet, a time goes as the timed quick order. */
    private static Entry ceaseFire(int selectedCount, int attackTargetId) {
        Entry plain = action(QuickCommandRegistry.ID_QUICK_CEASE_FIRE, UnitOrderOption.Action.CEASE_FIRE, selectedCount,
                attackTargetId);
        return new Entry(plain.wheelId(), plain.label(), plain.accent(), plain.enabled(), OrderScales.Scale.CEASE_FIRE,
                c -> {
                    int seconds = OrderScales.get(OrderScales.Scale.CEASE_FIRE);
                    if (seconds <= 0) {
                        plain.run().run(c);
                        return;
                    }
                    NetworkHandler.CHANNEL.sendToServer(new PacketQuickCommand(QuickCommandRegistry.ceaseFireId(seconds),
                            new ArrayList<>(MapMarkers.selected()), true, null));
                });
    }

    private static Entry mapAction(UnitOrderOption.Action action, int selectedCount, int attackTargetId) {
        return new Entry("", I18n.get(UnitOrderOption.labelKey(action), selectedCount), 0x9AA7B4,
                UnitOrderOption.active(action, selectedCount, attackTargetId), null,
                c -> UnitOrderOption.perform(action, c));
    }

    private static Entry scaled(Entry e, OrderScales.Scale scale) {
        return new Entry(e.wheelId(), e.label(), e.accent(), e.enabled(), scale, e.run());
    }

    private static Entry leaf(String wheelId, boolean enabled, @Nullable OrderScales.Scale scale, Run run) {
        WedgeEntry.PipelineEntry leaf = WheelUi.leaf(wheelId);
        String glyph = leaf != null ? leaf.icon() : "•";
        int accent = leaf != null ? leaf.accentRgb() : 0xFFFFFF;
        return new Entry(wheelId, glyph + " " + I18n.get(QuickCommandRegistry.labelKey(wheelId)), accent, enabled, scale,
                run);
    }
}
