package com.neoalive.tacz_sewv.fpas;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.block.ZoneBeaconBlockEntity;
import com.neoalive.tacz_sewv.bridge.IPmcDowned;
import com.neoalive.tacz_sewv.command.quick.QuickCommandUnits;
import com.neoalive.tacz_sewv.crew.CrewRadio;
import com.neoalive.tacz_sewv.entity.ai.sensor.CombatantIndex;
import com.neoalive.tacz_sewv.entity.ai.support.FormationSubjects;
import com.neoalive.tacz_sewv.entity.ai.support.MortarSupport;
import com.neoalive.tacz_sewv.entity.ai.support.OrderStandDown;

/**
 * "Attack every contact of this class": the player's PMCs inside the radars' cover are paired with
 * the contacts by {@link SignalAssign} and given ATTACK_THAT_TARGET, the rest queued in
 * {@link SignalQueue}. Shared by the FPAS dashboard and the quickwheel Signal leaves.
 */
public final class SignalOrders {

    private SignalOrders() {}

    public static void attack(ServerPlayer player, List<ZoneBeaconBlockEntity> beacons, RadarContact.Cls cls) {
        List<RadarContact> enemies = new ArrayList<>();
        Map<Integer, PmcUnitEntity> allies = new LinkedHashMap<>();
        boolean anyRadar = false;
        for (ZoneBeaconBlockEntity be : beacons) {
            if (!be.radarOnline() || !be.mayEdit(player) || !(be.getLevel() instanceof ServerLevel level)) continue;
            anyRadar = true;
            for (RadarContact c : be.contacts()) {
                if (c.cls() == cls && enemies.stream().noneMatch(e -> e.targetId() == c.targetId())) enemies.add(c);
            }
            collectAllies(player, level, be, allies);
        }
        if (!anyRadar) {
            say(player, Component.translatable("message.tacz_sewv.signal.no_radar"), ChatFormatting.GRAY);
            return;
        }
        if (enemies.isEmpty()) {
            say(player, Component.translatable("message.tacz_sewv.signal.no_contacts", clsName(cls)),
                    ChatFormatting.GRAY);
            return;
        }
        if (allies.isEmpty()) {
            say(player, Component.translatable("message.tacz_sewv.signal.no_units"), ChatFormatting.GRAY);
            return;
        }
        List<PmcUnitEntity> units = new ArrayList<>(allies.values());
        double[][] a = new double[units.size()][];
        for (int i = 0; i < a.length; i++) {
            Vec3 p = units.get(i).getRootVehicle().position();
            a[i] = new double[] {p.x, p.z};
        }
        double[][] e = new double[enemies.size()][];
        for (int i = 0; i < e.length; i++) e[i] = new double[] {enemies.get(i).x(), enemies.get(i).z()};
        List<List<Integer>> plan = SignalAssign.assign(a, e);
        int sent = 0;
        for (int i = 0; i < units.size(); i++) {
            List<Integer> mine = plan.get(i);
            List<Integer> ids = new ArrayList<>(mine.size());
            for (int idx : mine) ids.add(enemies.get(idx).targetId());
            if (issue(player, units.get(i), ids)) sent++;
        }
        say(player, Component.translatable("message.tacz_sewv.signal.done", sent, enemies.size(), clsName(cls)),
                ChatFormatting.GREEN);
    }

    /** Owned PMCs in the radar's cover: drivers for crewed hulls, everyone else on foot. */
    private static void collectAllies(ServerPlayer player, ServerLevel level, ZoneBeaconBlockEntity be,
                                      Map<Integer, PmcUnitEntity> out) {
        CombatantIndex.Snapshot snap = CombatantIndex.snapshot(level);
        if (snap == null) return;
        Vec3 c = Vec3.atCenterOf(be.getBlockPos());
        double reach = be.reach();
        for (CombatantIndex.Entry entry : snap.query(c.x, c.z, reach, level.getMinBuildHeight(),
                level.getMaxBuildHeight())) {
            if (!(entry.living instanceof PmcUnitEntity pmc) || !pmc.isAlive()) continue;
            if (!player.getUUID().equals(pmc.getOwnerUUID())) continue;
            if (pmc.getVehicle() != null && !(pmc.getVehicle() instanceof VehicleEntity hull
                    && hull.getFirstPassenger() == pmc)) continue;
            double dx = entry.x - c.x, dz = entry.z - c.z;
            if (dx * dx + dz * dz > reach * reach) continue;
            if (pmc instanceof IPmcDowned d && d.sewv$isDowned()) continue;
            if (MortarSupport.hasMortarClaim(pmc)) continue;
            out.putIfAbsent(pmc.getId(), pmc);
        }
    }

    private static boolean issue(ServerPlayer player, PmcUnitEntity pmc, List<Integer> targets) {
        if (targets.isEmpty() || QuickCommandUnits.refuseFobOrRoute(player, pmc)) return false;
        int first = targets.get(0);
        OrderStandDown.clearForNewOrder(pmc, "SignalOrders");
        if (FormationSubjects.routeOrder(player, pmc, OrderType.ATTACK_THAT_TARGET, null, first, false)) return true;
        pmc.setAttackTargetId(first);
        pmc.setOrder(OrderType.ATTACK_THAT_TARGET);
        if (pmc.getVehicle() instanceof VehicleEntity hull && hull.getFirstPassenger() == pmc) {
            CrewRadio.play(hull, CrewRadio.Line.ORDER_DISPATCH);
        }
        SignalQueue.set(pmc, targets.subList(1, targets.size()));
        return true;
    }

    public static Component clsName(RadarContact.Cls cls) {
        return Component.translatable("gui.tacz_sewv.fpas.cls." + cls.key());
    }

    private static void say(ServerPlayer player, Component msg, ChatFormatting color) {
        player.displayClientMessage(msg.copy().withStyle(color), true);
    }
}
