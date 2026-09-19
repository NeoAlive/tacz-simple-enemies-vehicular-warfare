package com.neoalive.tacz_sewv.network;

import java.util.List;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkEvent;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.airport.AirportRegistry;
import com.neoalive.tacz_sewv.airport.HelipadTraffic;
import com.neoalive.tacz_sewv.airport.StaticCarrierAirports;
import com.neoalive.tacz_sewv.bridge.IHelicopterPilot;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.entity.ai.goal.DriveHelicopterGoal;
import com.neoalive.tacz_sewv.entity.ai.goal.DrivePlaneGoal;
import com.neoalive.tacz_sewv.entity.ai.plane.PlaneLeash;
import com.neoalive.tacz_sewv.entity.ai.support.FlightOrders;
import com.neoalive.tacz_sewv.order.OrderFailure;
import com.neoalive.tacz_sewv.order.OrderReport;

/**
 * Player → server flight command for owned aircraft crews: takeoff, land at the nearest airport, or
 * an emergency landing in whatever field is to hand. Sets the {@link IHelicopterPilot} command that
 * {@link com.neoalive.tacz_sewv.entity.ai.goal.DriveHelicopterGoal} and
 * {@link DrivePlaneGoal} consume — the command set is aircraft-generic despite the name.
 *
 * <p><b>The two landing orders differ only in how the pad is chosen, and that choice is made here.</b>
 * "Land at nearest airport" resolves a surveyed strip and <i>refuses the order</i> when there is
 * none, which is the point of naming it that: the old "land here" silently degraded to a field
 * arrival beside a runway the player thought they were sending the aircraft to. "Emergency land"
 * <i>is</i> that field arrival, asked for deliberately — it runs the same flat-ground search the
 * damaged-airframe path uses ({@link DrivePlaneGoal#findFieldPad}) and needs no airport, no
 * clicked block and no surveyed anything. Both are written down as {@code HELI_CMD_LANDING}, so the
 * flight goals are untouched by the split.
 */
public class PacketHelicopterCommand {

    /** The cruise-altitude band the takeoff order carries; mirrors DriveHelicopterGoal's flight band. */
    public static final int MIN_ALTITUDE = 30;
    public static final int MAX_ALTITUDE = 50;

    private final List<Integer> unitIds;
    private final int command;
    private final BlockPos landPos; // only meaningful for HELI_CMD_LANDING; may be null otherwise
    private final int altitude;     // only meaningful for HELI_CMD_TAKEOFF (the live cruise trim)

    public PacketHelicopterCommand(List<Integer> unitIds, int command, BlockPos landPos, int altitude) {
        this.unitIds = unitIds;
        this.command = command;
        this.landPos = landPos;
        this.altitude = altitude;
    }

    public PacketHelicopterCommand(FriendlyByteBuf buf) {
        this.unitIds = PacketLists.readUnitIds(buf);
        this.command = buf.readVarInt();
        this.landPos = buf.readBoolean() ? buf.readBlockPos() : null;
        this.altitude = buf.readVarInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeCollection(this.unitIds, FriendlyByteBuf::writeVarInt);
        buf.writeVarInt(this.command);
        buf.writeBoolean(this.landPos != null);
        if (this.landPos != null) buf.writeBlockPos(this.landPos);
        buf.writeVarInt(this.altitude);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            Player player = ctx.get().getSender();
            if (!(player instanceof net.minecraft.server.level.ServerPlayer sp)) return;

            boolean emergency = this.command == IHelicopterPilot.HELI_CMD_EMERGENCY_LAND;
            boolean helipadLand = this.command == IHelicopterPilot.HELI_CMD_LAND_HELIPAD;
            boolean landing = emergency || helipadLand || this.command == IHelicopterPilot.HELI_CMD_LANDING;
            // Emergency and helipad land never reach an entity as themselves — see IHelicopterPilot.
            int stored = emergency || helipadLand ? IHelicopterPilot.HELI_CMD_LANDING : this.command;

            // Pads given out during this order, so a batch of helicopters gets a pad each.
            java.util.Set<BlockPos> claimed = new java.util.HashSet<>();
            boolean noneInRange = false;
            boolean allOccupied = false;
            boolean helicopterRefused = false;

            int ordered = 0;
            for (int unitId : this.unitIds) {
                Entity e = player.level().getEntity(unitId);
                // Intentionally PMC-only: RU/US crews also implement IHelicopterPilot,
                // but they are hostile and unowned — they fly autonomously (TankSpawner
                // issues their takeoff on spawn) and take no player flight orders.
                // Only the unit at the stick (seat 0 of a helicopter) takes the order:
                // gunners/passengers/ground units are not flight crews, and counting
                // them reported one "helicopter" per crew member in the feedback.
                if (!(e instanceof PmcUnitEntity pmc)) {
                    OrderReport.fail(player, OrderFailure.NOT_A_UNIT);
                    continue;
                }
                if (!pmc.isOwnedBy(player)) {
                    OrderReport.fail(player, OrderFailure.NOT_OWNED);
                    continue;
                }
                if (!(pmc.getVehicle() instanceof VehicleEntity v)) {
                    OrderReport.fail(player, OrderFailure.NOT_MOUNTED, pmc);
                    continue;
                }
                if (v.getFirstPassenger() instanceof PmcUnitEntity driver && driver.isOwnedBy(player)) {
                    pmc = driver;
                }
                // Every crew member of one aircraft is in this list, so a gunner or passenger
                // reaching here is the normal case, not a failure worth a line of its own.
                if (v.getFirstPassenger() != pmc) continue;
                // Fixed-wing takes the same commands: IHelicopterPilot's NONE/TAKEOFF/LANDING/
                // LANDED is aircraft-generic despite the name, and DrivePlaneGoal consumes it.
                // This filter used to be rotary-wing only, which is why the TDT and map Land
                // button silently did nothing to a plane.
                boolean plane = HullFacts.isPlaneHull(v);
                if (!plane && !HullFacts.isHelicopterHull(v)) {
                    OrderReport.fail(player, OrderFailure.WRONG_HULL, pmc);
                    continue;
                }
                if (!withinCommandRange(player, v, plane)) {
                    OrderReport.fail(player, OrderFailure.OUT_OF_RANGE, pmc);
                    continue;
                }

                // Helipads are for rotary-wing only, runways for fixed-wing only.
                if (helipadLand && plane) {
                    OrderReport.fail(player, OrderFailure.WRONG_HULL, pmc);
                    continue;
                }
                if (landing && !emergency && !helipadLand && !plane) {
                    OrderReport.fail(player, OrderFailure.WRONG_HULL, pmc);
                    helicopterRefused = true;
                    continue;
                }
                if (helipadLand) {
                    HelipadTraffic.Pick pick = HelipadTraffic.nearest(sp.serverLevel(), v.blockPosition(),
                            SewvConfig.AIRPORT_LANDING_SEARCH_RADIUS.get(), claimed);
                    switch (pick.availability()) {
                        case NONE_IN_RANGE -> noneInRange = true;
                        case ALL_OCCUPIED -> allOccupied = true;
                        case FOUND -> {
                            claimed.add(pick.pad());
                            IHelicopterPilot heliPilot = (IHelicopterPilot) pmc;
                            heliPilot.sewv$setHeliCommand(IHelicopterPilot.HELI_CMD_LANDING);
                            heliPilot.sewv$setHeliLandPos(pick.pad());
                            DriveHelicopterGoal.setForcedLand(v, pick.pad());
                            ordered++;
                        }
                    }
                    continue;
                }

                // Resolve the pad BEFORE touching the pilot: a landing order with nowhere to go
                // is cleared again by the flight goal on its next tick, which reads as a
                // successful order that then quietly resumes the FOLLOW orbit. Refusing it here
                // is what lets the feedback say so.
                if (emergency) {
                    if (!FlightOrders.emergencyLand(pmc, v)) {
                        OrderReport.fail(player, OrderFailure.NO_PAD, pmc);
                        continue;
                    }
                    ordered++;
                    continue;
                }

                BlockPos pad = null;
                AirportRegistry.Airport airport = null;
                if (landing) {
                    airport = nearestAirport(sp.serverLevel(), v);
                    if (airport != null) pad = airport.touchdown();
                    if (pad == null) {
                        OrderReport.fail(player, OrderFailure.NO_AIRPORT, pmc);
                        continue;
                    }
                }

                IHelicopterPilot pilot = (IHelicopterPilot) pmc;
                if (this.command == IHelicopterPilot.HELI_CMD_TAKEOFF) {
                    FlightOrders.takeoff(pmc, v, this.altitude);
                    ordered++;
                    continue;
                }

                pilot.sewv$setHeliCommand(stored);
                if (landing) {
                    pilot.sewv$setHeliLandPos(pad);
                    if (plane) {
                        // A surveyed strip also fixes the approach AXIS, which is the whole
                        // difference between arriving on a runway and arriving across it. A
                        // field pad has no axis, so the goal fans for one itself.
                        if (airport != null) {
                            DrivePlaneGoal.setForcedLand(v, pad, airport.headingDeg());
                        } else {
                            DrivePlaneGoal.setForcedLand(v, pad);
                        }
                    } else {
                        DriveHelicopterGoal.setForcedLand(v, pad);
                    }
                } else {
                    pilot.sewv$setHeliLandPos(null);
                    if (plane) {
                        DrivePlaneGoal.clearForcedLand(v);
                    } else {
                        DriveHelicopterGoal.clearForcedLand(v);
                    }
                }
                ordered++;
            }

            if (helipadLand) {
                // Nothing is allowed to fail silently: say which of the two reasons it was. Occupied
                // wins when both happened, since that is the one the player can do something about.
                if (allOccupied) {
                    sp.displayClientMessage(Component.translatable("message.tacz_sewv.helipad.all_occupied")
                            .withStyle(ChatFormatting.RED), true);
                } else if (noneInRange) {
                    sp.displayClientMessage(Component.translatable("message.tacz_sewv.helipad.none_found")
                            .withStyle(ChatFormatting.RED), true);
                }
                if (ordered > 0) {
                    NetworkHandler.orderFeedback(player, "message.tacz_sewv.heli.land_helipad", ordered,
                            ChatFormatting.GREEN, ordered);
                }
                return;
            }
            if (helicopterRefused) {
                sp.displayClientMessage(Component.translatable("message.tacz_sewv.heli.land.helicopter")
                        .withStyle(ChatFormatting.RED), true);
            }
            String base = emergency ? "message.tacz_sewv.heli.emergency_land"
                    : landing ? "message.tacz_sewv.heli.land" : "message.tacz_sewv.heli.takeoff";
            NetworkHandler.orderFeedback(player, base, ordered, ChatFormatting.GREEN, ordered);
        });
        ctx.get().setPacketHandled(true);
    }

    /**
     * The strip an aircraft is being sent home to: nearest to the <b>aircraft</b>, not to whatever
     * the player clicked. A runway is a fixed place the player already surveyed — asking them to
     * also aim at it was the reason a plane put itself down in a field beside the strip.
     *
     * <p>The ordered point is still consulted as a fallback, so clicking near a distant strip on the
     * map sends the aircraft to <i>that</i> one rather than to the one nearest its current position.
     *
     * <p>Fixed-wing only. Helicopters have their own surveyed site, the helipad
     * ({@link HelipadTraffic}), reached by {@code HELI_CMD_LAND_HELIPAD}.
     */
    @Nullable
    private AirportRegistry.Airport nearestAirport(ServerLevel level, VehicleEntity v) {
        double radius = SewvConfig.AIRPORT_LANDING_SEARCH_RADIUS.get();
        AirportRegistry.Airport airport = StaticCarrierAirports.nearest(level, v.blockPosition(), radius);
        if (airport == null && this.landPos != null) {
            airport = StaticCarrierAirports.nearest(level, this.landPos, radius);
        }
        return airport;
    }

    /**
     * Server-side range gate. The client discovery radius is generous on purpose (a plane you
     * ordered up is soon a long way off), but the order still has to be refused past the distance
     * the flight AI itself treats as too far, or a command could retask an aircraft the player has
     * no business seeing. The bound is the <b>hard</b> leash ring, not the soft one: everything
     * between the two is an aircraft already on its way home, and refusing to talk to it there
     * would mean a recalled plane could not be told to land.
     * Helicopters keep the client discovery radius they always had.
     */
    private static boolean withinCommandRange(Player player, VehicleEntity v, boolean plane) {
        if (!plane) return true;
        double radius = com.neoalive.tacz_sewv.config.SewvConfig.PLANE_COMMAND_RADIUS.get()
                * PlaneLeash.HARD_MULTIPLIER;
        return v.distanceToSqr(player) <= radius * radius;
    }
}
