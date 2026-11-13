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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.block.ZoneBeaconBlockEntity;
import com.neoalive.tacz_sewv.bridge.IPmcDowned;
import com.neoalive.tacz_sewv.command.quick.QuickCommandUnits;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.crew.CrewRadio;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleWeapons;
import com.neoalive.tacz_sewv.entity.ai.sensor.CombatantIndex;
import com.neoalive.tacz_sewv.entity.ai.support.FormationSubjects;
import com.neoalive.tacz_sewv.entity.ai.support.MortarSupport;
import com.neoalive.tacz_sewv.entity.ai.support.OrderStandDown;
import com.neoalive.tacz_sewv.entity.ai.support.SmallArmsSupport;
import com.neoalive.tacz_sewv.entity.ai.utility.Facts;

/**
 * "Attack every contact of this class": the player's PMCs near the contacts are paired with them by
 * {@link SignalAssign}, only where the unit can hurt the target, and each is sent to a standoff
 * point short of its contact. {@link SignalTasks} then hands it back to its own AI on FREE_FIRE.
 * Shared by the FPAS dashboard and the quickwheel Signal leaves.
 */
public final class SignalOrders {

    private SignalOrders() {}

    public static void attack(ServerPlayer player, List<ZoneBeaconBlockEntity> beacons, RadarContact.Cls cls) {
        List<RadarContact> enemies = new ArrayList<>();
        List<ZoneBeaconBlockEntity> live = new ArrayList<>();
        Map<Integer, PmcUnitEntity> allies = new LinkedHashMap<>();
        for (ZoneBeaconBlockEntity be : beacons) {
            if (!be.radarOnline() || !be.mayEdit(player) || !(be.getLevel() instanceof ServerLevel level)) continue;
            live.add(be);
            List<RadarContact> mine = new ArrayList<>();
            for (RadarContact c : be.contacts()) {
                if (c.cls() != cls || enemies.stream().anyMatch(e -> e.targetId() == c.targetId())) continue;
                enemies.add(c);
                mine.add(c);
            }
            collectAllies(player, level, mine, allies);
        }
        if (live.isEmpty()) {
            say(player, Component.translatable("message.tacz_sewv.signal.no_radar"), ChatFormatting.GRAY);
            return;
        }
        if (enemies.isEmpty()) {
            say(player, Component.translatable("message.tacz_sewv.signal.no_contacts", clsName(cls)),
                    ChatFormatting.GRAY);
            return;
        }
        List<PmcUnitEntity> units = new ArrayList<>();
        for (PmcUnitEntity pmc : allies.values()) {
            if (!QuickCommandUnits.refuseFobOrRoute(player, pmc)) units.add(pmc);
        }
        double[][] a = new double[units.size()][];
        boolean[][] able = new boolean[units.size()][enemies.size()];
        int capable = 0;
        for (int i = 0; i < a.length; i++) {
            Vec3 p = units.get(i).getRootVehicle().position();
            a[i] = new double[] {p.x, p.z};
            boolean can = canHurt(units.get(i), cls);
            java.util.Arrays.fill(able[i], can);
            if (can) capable++;
        }
        if (capable == 0) {
            say(player, Component.translatable(units.isEmpty() ? "message.tacz_sewv.signal.no_units"
                    : "message.tacz_sewv.signal.none_capable", clsName(cls)), ChatFormatting.GRAY);
            return;
        }
        double[][] e = new double[enemies.size()][];
        for (int i = 0; i < e.length; i++) e[i] = new double[] {enemies.get(i).x(), enemies.get(i).z()};
        List<List<Integer>> plan = SignalAssign.assign(a, e, able);

        Map<PmcUnitEntity, Vec3> tasks = new LinkedHashMap<>();
        for (int i = 0; i < units.size(); i++) {
            if (plan.get(i).isEmpty()) continue;
            RadarContact c = enemies.get(plan.get(i).get(0));
            PmcUnitEntity pmc = units.get(i);
            Vec3 at = pmc.getRootVehicle().position();
            double[] p = SignalAssign.standoff(at.x, at.z, c.x(), c.z(), standoffRange(pmc, cls));
            OrderStandDown.clearForNewOrder(pmc, "SignalOrders");
            // A split engagement has no formation: each unit goes its own way.
            FormationSubjects.release(pmc);
            if (pmc.getVehicle() instanceof VehicleEntity hull && hull.getFirstPassenger() == pmc) {
                CrewRadio.play(hull, CrewRadio.Line.ORDER_DISPATCH);
            }
            tasks.put(pmc, new Vec3(p[0], groundY(pmc.level(), p[0], p[1], c.y()), p[1]));
        }
        SignalTasks.begin(player.getUUID(), tasks);
        say(player, Component.translatable("message.tacz_sewv.signal.done", tasks.size(), enemies.size(),
                clsName(cls), units.size() - capable), ChatFormatting.GREEN);
    }

    /** How far short of the contact a unit stops. Aircraft fly onto it and fight from their own runs. */
    static double standoffRange(PmcUnitEntity pmc, RadarContact.Cls cls) {
        if (SignalTasks.aircrew(pmc)) return 0.0;
        if (pmc.getVehicle() instanceof VehicleEntity) {
            return Facts.preferredRange(cls == RadarContact.Cls.INFANTRY
                    ? VehicleWeapons.TargetCategory.FACTION_UNIT : VehicleWeapons.TargetCategory.VEHICLE);
        }
        double r = SewvConfig.SIGNAL_INFANTRY_RANGE.get();
        return SmallArmsSupport.holdsAntiArmor(pmc) ? r * 1.5 : r;
    }

    private static double groundY(Level level, double x, double z, double fallback) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        if (!level.hasChunk(bx >> 4, bz >> 4)) return fallback;
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
    }

    /**
     * Rifles cannot scratch armour, so by default an on-foot unit only takes vehicles with an
     * anti-armour launcher in hand. A ground crew needs an AA hull for fixed-wing targets.
     */
    static boolean canHurt(PmcUnitEntity pmc, RadarContact.Cls cls) {
        if (pmc.getVehicle() instanceof VehicleEntity hull) {
            if (cls != RadarContact.Cls.PLANE) return true;
            return SignalTasks.aircrew(pmc) || HullFacts.isAntiAirHull(hull);
        }
        if (cls == RadarContact.Cls.INFANTRY) return true;
        return SewvConfig.SIGNAL_INFANTRY_ENGAGES_VEHICLES.get() || SmallArmsSupport.holdsAntiArmor(pmc);
    }

    /** Owned PMCs within the ally radius of any contact: drivers for crewed hulls, everyone else on foot. */
    private static void collectAllies(ServerPlayer player, ServerLevel level, List<RadarContact> contacts,
                                      Map<Integer, PmcUnitEntity> out) {
        CombatantIndex.Snapshot snap = CombatantIndex.snapshot(level);
        if (snap == null) return;
        double r = SewvConfig.SIGNAL_ALLY_RADIUS.get();
        for (RadarContact c : contacts) {
            for (CombatantIndex.Entry entry : snap.query(c.x(), c.z(), r, level.getMinBuildHeight(),
                    level.getMaxBuildHeight())) {
                if (!(entry.living instanceof PmcUnitEntity pmc) || !pmc.isAlive() || out.containsKey(pmc.getId())) {
                    continue;
                }
                if (!player.getUUID().equals(pmc.getOwnerUUID())) continue;
                if (pmc.getVehicle() != null && !(pmc.getVehicle() instanceof VehicleEntity hull
                        && hull.getFirstPassenger() == pmc)) continue;
                double dx = entry.x - c.x(), dz = entry.z - c.z();
                if (dx * dx + dz * dz > r * r) continue;
                if (pmc instanceof IPmcDowned d && d.sewv$isDowned()) continue;
                if (MortarSupport.hasMortarClaim(pmc)) continue;
                out.put(pmc.getId(), pmc);
            }
        }
    }

    public static Component clsName(RadarContact.Cls cls) {
        return Component.translatable("gui.tacz_sewv.fpas.cls." + cls.key());
    }

    private static void say(ServerPlayer player, Component msg, ChatFormatting color) {
        player.displayClientMessage(msg.copy().withStyle(color), true);
    }
}
