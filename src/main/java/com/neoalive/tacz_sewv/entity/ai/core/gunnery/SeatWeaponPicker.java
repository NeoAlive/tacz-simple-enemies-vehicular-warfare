package com.neoalive.tacz_sewv.entity.ai.core.gunnery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.data.gun.AmmoConsumer;
import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.gun.GunProp;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.compat.TripodCompat;
import com.neoalive.tacz_sewv.compat.lv.LvCompat;
import com.neoalive.tacz_sewv.entity.ai.core.MobToughness;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleWeapons;
import com.neoalive.tacz_sewv.entity.ai.core.gunnery.FireCommand.Option;
import com.neoalive.tacz_sewv.entity.ai.core.gunnery.FireCommand.Pick;
import com.neoalive.tacz_sewv.entity.ai.utility.Doctrine;

/**
 * Gathers a seat's options against one target and runs the fire command over them. The commit
 * (slot, ammo, radio) stays in {@link VehicleWeapons}.
 */
public final class SeatWeaponPicker {

    /** targetPriority at or beyond this indexes a round while the seat is idle. */
    public static final double IDLE_INDEX_AXIS = 0.34;

    /** What the walk saw and chose, kept per hull and seat for the debug readout. */
    public record Decision(TargetClass cls, double mgFraction, List<Option> options, @Nullable Pick pick,
                           long gameTime, String target) {
        public String stepName() {
            return pick == null ? "fallback" : switch (pick.step()) {
                case 1 -> "fire";
                case 2 -> "reload";
                default -> "last";
            };
        }
    }

    private static final Map<VehicleEntity, Decision[]> LAST = Collections.synchronizedMap(new WeakHashMap<>());

    private SeatWeaponPicker() {}

    public static Decision decide(VehicleEntity vehicle, int seat, LivingEntity target,
                                  @Nullable AbstractUnit shooter) {
        List<Round> loadout = SeatLoadout.of(vehicle, seat);
        TargetClass cls;
        double mgFraction = Double.NaN;
        PenetrationCard.Card card = null;
        if (target.getVehicle() instanceof VehicleEntity hull) {
            card = PenetrationCard.of(vehicle, seat, loadout, hull);
            cls = card == null ? TargetClass.UNSCORED : TargetClass.ofHull(card.mgFraction(), card.otherRoundHurts());
            if (card != null) mgFraction = card.mgFraction();
        } else if (LvCompat.isArmouredHull(target.getVehicle())) {
            cls = TargetClass.UNSCORED;
        } else {
            cls = TargetClass.ofLiving(TripodCompat.isTripod(target) || !MobToughness.isSoft(target));
        }

        List<Option> options = cls == TargetClass.UNSCORED ? List.of()
                : options(vehicle, seat, target, shooter, loadout, card);
        Pick pick = cls == TargetClass.UNSCORED ? null : FireCommand.decide(cls, options);
        Decision d = new Decision(cls, mgFraction, options, pick, vehicle.level().getGameTime(),
                target.getType().toShortString());
        remember(vehicle, seat, d);
        return d;
    }

    private static List<Option> options(VehicleEntity vehicle, int seat, LivingEntity target,
                                        @Nullable AbstractUnit shooter, List<Round> loadout,
                                        @Nullable PenetrationCard.Card card) {
        Entity supplier = vehicle.getAmmoSupplier();
        Entity source = supplier != null ? supplier : vehicle;
        Entity body = target.getVehicle() != null ? target.getVehicle() : target;
        double range = Math.sqrt(vehicle.distanceToSqr(target));
        List<Option> out = new ArrayList<>(loadout.size());
        for (int i = 0; i < loadout.size(); i++) {
            Round r = loadout.get(i);
            GunData gun = VehicleWeapons.gunData(vehicle, seat, r.slot());
            if (gun == null) continue;
            boolean chambered = r.consumerCount() <= 1 || gun.selectedAmmoType.get() == r.consumerIndex();
            double cardDamage = card == null ? 0 : card.damage()[i];
            String why = null;
            if (card != null && cardDamage <= 0) why = "off-card";
            else if (!hasStock(gun, r, chambered, source)) why = "dry";
            else if (r.role() == VehicleWeapons.WEAPON_SPECIAL
                    && !(VehicleWeapons.specialReady(vehicle, seat, r.slot())
                    && VehicleWeapons.specialLinedUp(vehicle, shooter, target))) why = "not-ready";
            else if (r.kind() == Round.Kind.GRAPESHOT
                    && !FireCommand.inCone(range, r.spreadDeg(), body.getBbWidth())) why = "out-of-cone";
            out.add(new Option(r, chambered, why == null, cardDamage, why));
        }
        return out;
    }

    private static boolean hasStock(GunData gun, Round r, boolean chambered, Entity source) {
        try {
            List<AmmoConsumer> consumers = gun.get(GunProp.AMMO_CONSUMER);
            if (consumers == null || consumers.isEmpty()) return true;
            if (chambered && gun.ammo.get() > 0) return true;
            AmmoConsumer c = consumers.get(Math.min(r.consumerIndex(), consumers.size() - 1));
            return c == null || c.count(gun, source) > 0;
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * The round to index while the seat has no target: a penetrator for an armour-minded crew, HE
     * for an infantry-minded one, nothing in between. Null when nothing should change.
     */
    @Nullable
    public static Round idleRound(VehicleEntity vehicle, int seat, AbstractUnit unit) {
        double axis = Doctrine.forCrew(unit).get(Doctrine.Axis.TARGET_PRIORITY);
        Round.Kind want = idleKind(axis);
        if (want == null) return null;
        Entity supplier = vehicle.getAmmoSupplier();
        Entity source = supplier != null ? supplier : vehicle;
        for (Round r : SeatLoadout.of(vehicle, seat)) {
            if (r.kind() != want || r.role() != VehicleWeapons.WEAPON_CANNON || r.consumerCount() < 2) continue;
            GunData gun = VehicleWeapons.gunData(vehicle, seat, r.slot());
            if (gun == null || gun.selectedAmmoType.get() == r.consumerIndex()) return null;
            if (hasStock(gun, r, false, source)) return r;
        }
        return null;
    }

    @Nullable
    public static Round.Kind idleKind(double targetPriority) {
        if (targetPriority >= IDLE_INDEX_AXIS) return Round.Kind.PENETRATOR;
        if (targetPriority <= -IDLE_INDEX_AXIS) return Round.Kind.HE;
        return null;
    }

    private static void remember(VehicleEntity vehicle, int seat, Decision d) {
        if (seat < 0 || seat > 31) return;
        synchronized (LAST) {
            Decision[] row = LAST.get(vehicle);
            if (row == null || row.length <= seat) {
                Decision[] grown = new Decision[seat + 1];
                if (row != null) System.arraycopy(row, 0, grown, 0, row.length);
                row = grown;
                LAST.put(vehicle, row);
            }
            row[seat] = d;
        }
    }

    @Nullable
    public static Decision last(VehicleEntity vehicle, int seat) {
        synchronized (LAST) {
            Decision[] row = LAST.get(vehicle);
            return row == null || seat < 0 || seat >= row.length ? null : row[seat];
        }
    }
}
