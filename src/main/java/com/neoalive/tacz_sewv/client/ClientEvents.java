package com.neoalive.tacz_sewv.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.nekoyuni.SimpleEnemyMod.client.util.CommanderRayTrace;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.jetbrains.annotations.Nullable;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.client.invasion.InvasionHudClient;
import com.neoalive.tacz_sewv.entity.unit.PmcCommanderEntity;
import com.neoalive.tacz_sewv.init.ModItems;
import com.neoalive.tacz_sewv.item.HandheldRadioItem;
import com.neoalive.tacz_sewv.item.RadioSettings;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketEntrench;
import com.neoalive.tacz_sewv.network.PacketEscort;
import com.neoalive.tacz_sewv.network.PacketJoinPlatoon;
import com.neoalive.tacz_sewv.network.PacketRadioCommand;
import com.neoalive.tacz_sewv.network.PacketSetGuardPosition;

// FORGE bus, client dist: opens the Tactical Data Terminal, and runs Escort / Guard selection modes.
@Mod.EventBusSubscriber(modid = TaczSewv.MODID, value = Dist.CLIENT)
public class ClientEvents {

    /** Reach for picking the vehicle to escort — SEM's own selection raytrace distance. */
    private static final double ESCORT_PICK_RANGE = 50.0;
    /** Live Selection / escort entity pick — same as SEM commander reach. */
    private static final double LIVE_PICK_RANGE = 50.0;
    private static final double CLIENT_DISCOVERY_RADIUS = 512.0;
    /** Re-show the selection prompt this often (goal-agnostic client ticks) so it doesn't fade mid-mode. */
    private static final int PROMPT_REFRESH_TICKS = 40;

    // Escort selection mode: after the player picks Escort in the TDT, the next in-world left-click
    // designates the vehicle and a right-click cancels. The units to order are captured up front
    // (SEM's menu selection, or nearby owned units), so the click only has to supply the target.
    private static boolean pendingEscort = false;
    private static List<Integer> pendingEscortUnits = List.of();

    // GUARD_POSITION pick: same arm pattern as escort, but left-click captures a block via pick().
    private static boolean pendingGuard = false;
    private static List<Integer> pendingGuardUnits = List.of();

    // ENTRENCHED pick: left-click a trench / foxhole / emplacement / sandbag; server resolves.
    private static boolean pendingEntrench = false;
    private static List<Integer> pendingEntrenchUnits = List.of();

    // Live Selection: left-click toggles owned PMC / vehicle crew into the TDT ribbon; right-click
    // confirms (≥1) or cancels (empty). Armed from the ribbon — no units needed up front.
    private static boolean pendingLiveSelection = false;

    // Handheld radio designation: same arm→click→confirm shape as Live Selection / Guard.
    // Entity mode keeps at most one designatable id; position mode left-clicks a block.
    private static boolean pendingRadioEntity = false;
    private static boolean pendingRadioPosition = false;
    @Nullable
    private static RadioSettings.State pendingRadioSettings = null;
    private static int pendingRadioEntityId = -1;
    private static final double RADIO_PICK_RANGE = 256.0;

    // Join Platoon pick: same arm pattern as escort, but the target is a PmcCommanderEntity —
    // SEM's own CommanderRayTrace deliberately skips PMCs, so this needs its own raycast.
    private static boolean pendingJoinPlatoon = false;
    private static List<Integer> pendingJoinPlatoonUnits = List.of();

    private static int promptCooldown = 0;

    /**
     * Arm escort selection mode. Called by the TDT's Escort button, which then closes the screen.
     * Units are SEM's commander-menu selection if there is one (the flow the feature is built
     * around), otherwise the owned units near the player, so it still does something without a
     * selection. Refuses to arm with no units.
     */
    public static void armEscort() {
        clearGuard();
        clearEntrench();
        clearLiveSelection();
        clearRadioPick();
        List<Integer> units = snapshotOwnedUnits();
        if (units.isEmpty()) {
            hint("message.tacz_sewv.escort.no_units");
            return;
        }
        pendingEscort = true;
        pendingEscortUnits = units;
        promptCooldown = 0;
    }

    /**
     * Arm GUARD_POSITION block pick (TDT, Xaero-free). Left-click a block to set; right-click cancels.
     */
    public static void armGuardPosition() {
        clearEscort();
        clearEntrench();
        clearLiveSelection();
        clearRadioPick();
        List<Integer> units = snapshotOwnedUnits();
        if (units.isEmpty()) {
            hint("message.tacz_sewv.guard.no_units");
            return;
        }
        pendingGuard = true;
        pendingGuardUnits = units;
        promptCooldown = 0;
    }

    /**
     * Arm ENTRENCHED block pick. Left-click a trench/emplacement/sandbag; server resolves.
     */
    public static void armEntrench() {
        clearEscort();
        clearGuard();
        clearLiveSelection();
        clearRadioPick();
        List<Integer> units = snapshotOwnedUnits();
        if (units.isEmpty()) {
            hint("message.tacz_sewv.entrench.no_units");
            return;
        }
        pendingEntrench = true;
        pendingEntrenchUnits = units;
        promptCooldown = 0;
    }

    /** Arm in-world Live Selection (TDT ribbon). Screen closes; left-click toggles, right-click finishes. */
    public static void armLiveSelection() {
        clearEscort();
        clearGuard();
        clearEntrench();
        clearRadioPick();
        pendingLiveSelection = true;
        promptCooldown = 0;
    }

    /**
     * Arm radio ENTITY designation. Left-click selects exactly one designatable living entity
     * (replaces any prior pick); right-click confirms the call or cancels if none selected.
     */
    public static void armRadioEntity(RadioSettings.State settings) {
        clearEscort();
        clearGuard();
        clearEntrench();
        clearLiveSelection();
        clearRadioPick();
        pendingRadioEntity = true;
        pendingRadioSettings = settings;
        pendingRadioEntityId = -1;
        promptCooldown = 0;
    }

    /**
     * Arm radio POSITION designation. Left-click a block to call; right-click cancels.
     */
    public static void armRadioPosition(RadioSettings.State settings) {
        clearEscort();
        clearGuard();
        clearEntrench();
        clearLiveSelection();
        clearRadioPick();
        pendingRadioPosition = true;
        pendingRadioSettings = settings;
        promptCooldown = 0;
    }

    public static void clearRadioPick() {
        pendingRadioEntity = false;
        pendingRadioPosition = false;
        pendingRadioSettings = null;
        pendingRadioEntityId = -1;
        promptCooldown = 0;
    }

    /**
     * Arm Join Platoon selection mode. Called by the TDT's Join Platoon button, which then closes
     * the screen. The next left-click on an owned Commander sends the selected units to join its
     * platoon; the server re-validates ownership, type match and capacity per unit and reports
     * whichever ones did not take.
     */
    public static void armJoinPlatoon() {
        clearEscort();
        clearGuard();
        clearEntrench();
        clearLiveSelection();
        clearRadioPick();
        List<Integer> units = snapshotOwnedUnits();
        if (units.isEmpty()) {
            hint("message.tacz_sewv.platoon.no_units");
            return;
        }
        pendingJoinPlatoon = true;
        pendingJoinPlatoonUnits = units;
        promptCooldown = 0;
    }

    private static List<Integer> snapshotOwnedUnits() {
        return TdtSelection.resolve(CLIENT_DISCOVERY_RADIUS);
    }

    /**
     * Left click (attack) drives selection modes first, then opens the TDT with the terminal in hand.
     */
    @SubscribeEvent
    public static void onClickInput(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        if (pendingRadioEntity || pendingRadioPosition) {
            if (event.isAttack()) {
                radioPickClick(mc);
                event.setCanceled(true);
            } else if (event.isUseItem()) {
                finishRadioPick();
                event.setCanceled(true);
            }
            return;
        }

        if (pendingLiveSelection) {
            if (event.isAttack()) {
                liveSelectClick(mc);
                event.setCanceled(true);
            } else if (event.isUseItem()) {
                finishLiveSelection();
                event.setCanceled(true);
            }
            return;
        }

        if (pendingEscort) {
            if (event.isAttack()) {
                Entity target = CommanderRayTrace.rayTraceEntity(mc.player, ESCORT_PICK_RANGE);
                if (target instanceof VehicleEntity vehicle) {
                    NetworkHandler.CHANNEL.sendToServer(new PacketEscort(pendingEscortUnits, vehicle.getId()));
                    clearEscort();
                } else {
                    hint("message.tacz_sewv.escort.no_vehicle");
                }
                event.setCanceled(true);
            } else if (event.isUseItem()) {
                clearEscort();
                hint("message.tacz_sewv.escort.cancelled");
                event.setCanceled(true);
            }
            return;
        }

        if (pendingGuard) {
            if (event.isAttack()) {
                HitResult hit = mc.player.pick(HelicopterKeybind.LAND_PICK_RANGE, 0.0F, false);
                if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
                    BlockPos pos = bhr.getBlockPos();
                    NetworkHandler.CHANNEL.sendToServer(new PacketSetGuardPosition(pendingGuardUnits, pos));
                    clearGuard();
                } else {
                    hint("message.tacz_sewv.guard.no_block");
                }
                event.setCanceled(true);
            } else if (event.isUseItem()) {
                clearGuard();
                hint("message.tacz_sewv.guard.cancelled");
                event.setCanceled(true);
            }
            return;
        }

        if (pendingEntrench) {
            if (event.isAttack()) {
                HitResult hit = mc.player.pick(HelicopterKeybind.LAND_PICK_RANGE, 0.0F, false);
                if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
                    NetworkHandler.CHANNEL.sendToServer(new PacketEntrench(
                            pendingEntrenchUnits, PacketEntrench.MODE_ASSIGN, bhr.getBlockPos()));
                    clearEntrench();
                } else {
                    hint("message.tacz_sewv.entrench.no_block");
                }
                event.setCanceled(true);
            } else if (event.isUseItem()) {
                clearEntrench();
                hint("message.tacz_sewv.entrench.cancelled");
                event.setCanceled(true);
            }
            return;
        }

        if (pendingJoinPlatoon) {
            if (event.isAttack()) {
                joinPlatoonClick(mc);
                event.setCanceled(true);
            } else if (event.isUseItem()) {
                clearJoinPlatoon();
                hint("message.tacz_sewv.platoon.join.cancelled");
                event.setCanceled(true);
            }
            return;
        }

        if (!event.isAttack()) return;
        if (!holdingTerminal(mc.player)) return;
        TdtScreen.open();
        event.setCanceled(true);
    }

    /** Holding the TDT must not break blocks — the left-click is reserved for opening / selecting. */
    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (holdingTerminal(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    /** Same for punching entities while the terminal is in the main hand. */
    @SubscribeEvent
    public static void onAttackEntity(AttackEntityEvent event) {
        if (holdingTerminal(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    private static boolean holdingTerminal(Player player) {
        return player.getMainHandItem().is(ModItems.TACTICAL_DATA_TERMINAL.get());
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!pendingEscort && !pendingGuard && !pendingEntrench && !pendingLiveSelection
                && !pendingRadioEntity && !pendingRadioPosition && !pendingJoinPlatoon) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) {
            if (mc.player == null) {
                clearEscort();
                clearGuard();
                clearEntrench();
                clearLiveSelection();
                clearRadioPick();
                clearJoinPlatoon();
            }
            return;
        }

        // SuperbWarfare cancels mouse Pre events in armed seats — poll attack/use keys like TdtKeybind.
        if (mc.player.getVehicle() != null) {
            while (mc.options.keyAttack.consumeClick()) {
                confirmPendingAttack(mc);
            }
            while (mc.options.keyUse.consumeClick()) {
                cancelPending();
            }
        }

        if (--promptCooldown <= 0) {
            promptCooldown = PROMPT_REFRESH_TICKS;
            if (pendingRadioEntity) {
                hintRadioEntity();
            } else if (pendingRadioPosition) {
                hint("message.tacz_sewv.radio.pick_position");
            } else if (pendingLiveSelection) {
                hintLive();
            } else if (pendingEscort) {
                hint("message.tacz_sewv.escort.select");
            } else if (pendingGuard) {
                hint("message.tacz_sewv.guard.select");
            } else if (pendingJoinPlatoon) {
                hint("message.tacz_sewv.platoon.join.select");
            } else {
                hint("message.tacz_sewv.entrench.select");
            }
        }
    }

    private static void confirmPendingAttack(Minecraft mc) {
        if (mc.player == null) return;
        if (pendingRadioEntity || pendingRadioPosition) {
            radioPickClick(mc);
            return;
        }
        if (pendingLiveSelection) {
            liveSelectClick(mc);
            return;
        }
        if (pendingEscort) {
            Entity target = CommanderRayTrace.rayTraceEntity(mc.player, ESCORT_PICK_RANGE);
            if (target instanceof VehicleEntity vehicle) {
                NetworkHandler.CHANNEL.sendToServer(new PacketEscort(pendingEscortUnits, vehicle.getId()));
                clearEscort();
            } else {
                hint("message.tacz_sewv.escort.no_vehicle");
            }
            return;
        }
        if (pendingGuard) {
            HitResult hit = mc.player.pick(HelicopterKeybind.LAND_PICK_RANGE, 0.0F, false);
            if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
                NetworkHandler.CHANNEL.sendToServer(new PacketSetGuardPosition(pendingGuardUnits, bhr.getBlockPos()));
                clearGuard();
            } else {
                hint("message.tacz_sewv.guard.no_block");
            }
            return;
        }
        if (pendingEntrench) {
            HitResult hit = mc.player.pick(HelicopterKeybind.LAND_PICK_RANGE, 0.0F, false);
            if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
                NetworkHandler.CHANNEL.sendToServer(new PacketEntrench(
                        pendingEntrenchUnits, PacketEntrench.MODE_ASSIGN, bhr.getBlockPos()));
                clearEntrench();
            } else {
                hint("message.tacz_sewv.entrench.no_block");
            }
            return;
        }
        if (pendingJoinPlatoon) {
            joinPlatoonClick(mc);
        }
    }

    private static void cancelPending() {
        if (pendingRadioEntity || pendingRadioPosition) {
            finishRadioPick();
        } else if (pendingLiveSelection) {
            finishLiveSelection();
        } else if (pendingEscort) {
            clearEscort();
            hint("message.tacz_sewv.escort.cancelled");
        } else if (pendingGuard) {
            clearGuard();
            hint("message.tacz_sewv.guard.cancelled");
        } else if (pendingEntrench) {
            clearEntrench();
            hint("message.tacz_sewv.entrench.cancelled");
        } else if (pendingJoinPlatoon) {
            clearJoinPlatoon();
            hint("message.tacz_sewv.platoon.join.cancelled");
        }
    }

    private static void joinPlatoonClick(Minecraft mc) {
        if (mc.player == null) return;
        Entity target = rayTraceCommander(mc.player, ESCORT_PICK_RANGE);
        if (target instanceof PmcCommanderEntity commander) {
            NetworkHandler.CHANNEL.sendToServer(new PacketJoinPlatoon(pendingJoinPlatoonUnits, commander.getId()));
            clearJoinPlatoon();
        } else {
            hint("message.tacz_sewv.platoon.join.no_commander");
        }
    }

    /** SEM's own {@code CommanderRayTrace} deliberately skips PMCs, so joining needs its own pick. */
    @Nullable
    private static Entity rayTraceCommander(Player player, double distance) {
        Vec3 eye = player.getEyePosition(1.0F);
        Vec3 look = player.getViewVector(1.0F);
        Vec3 reach = eye.add(look.scale(distance));
        AABB box = player.getBoundingBox().expandTowards(look.scale(distance)).inflate(1.0D);

        Entity best = null;
        double closest = distance;
        for (Entity entity : player.level().getEntities(player, box)) {
            if (!(entity instanceof PmcCommanderEntity commander) || !commander.isOwnedBy(player)) continue;
            AABB bb = entity.getBoundingBox().inflate(entity.getPickRadius());
            Optional<Vec3> hit = bb.clip(eye, reach);
            if (hit.isEmpty()) continue;
            double dist = eye.distanceTo(hit.get());
            if (dist < closest) {
                best = entity;
                closest = dist;
            }
        }
        return best;
    }

    private static void radioPickClick(Minecraft mc) {
        if (mc.player == null || pendingRadioSettings == null) return;
        if (pendingRadioPosition) {
            HitResult hit = mc.player.pick(RADIO_PICK_RANGE, 0.0F, false);
            if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
                RadioSettings.State settings = pendingRadioSettings;
                clearRadioPick();
                NetworkHandler.CHANNEL.sendToServer(new PacketRadioCommand(settings, -1, bhr.getBlockPos()));
            } else {
                hint("message.tacz_sewv.radio.no_position");
            }
            return;
        }

        LivingEntity target = pickRadioEntity(mc.player);
        if (target == null) {
            hint("message.tacz_sewv.radio.no_target");
            return;
        }
        // Single-select: replace any prior pick (never accumulate).
        pendingRadioEntityId = target.getId();
        promptCooldown = 0;
        Player player = mc.player;
        player.displayClientMessage(
                Component.translatable("message.tacz_sewv.radio.entity_selected", target.getDisplayName())
                        .withStyle(ChatFormatting.GREEN), true);
    }

    private static void finishRadioPick() {
        if (pendingRadioPosition) {
            clearRadioPick();
            hint("message.tacz_sewv.radio.pick_cancelled");
            return;
        }
        if (!pendingRadioEntity) return;
        if (pendingRadioEntityId < 0 || pendingRadioSettings == null) {
            clearRadioPick();
            hint("message.tacz_sewv.radio.pick_cancelled");
            return;
        }
        RadioSettings.State settings = pendingRadioSettings;
        int entityId = pendingRadioEntityId;
        clearRadioPick();
        NetworkHandler.CHANNEL.sendToServer(new PacketRadioCommand(settings, entityId, null));
    }

    /**
     * A hit on a bare hull resolves to one of its living crew — see
     * {@link HandheldRadioItem#representativeCrew} — so aiming at the vehicle itself designates it,
     * not just whichever crew hitbox happens to be reachable (often none, behind a hidden seat).
     */
    @Nullable
    private static LivingEntity pickRadioEntity(Player player) {
        Vec3 eye = player.getEyePosition();
        Vec3 reach = player.getViewVector(1.0F).scale(RADIO_PICK_RANGE);
        Vec3 end = eye.add(reach);
        AABB search = player.getBoundingBox().expandTowards(reach).inflate(1.0);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(
                player, eye, end, search, HandheldRadioItem::isDesignatable, RADIO_PICK_RANGE * RADIO_PICK_RANGE);
        if (hit == null) return null;
        Entity entity = hit.getEntity();
        if (entity instanceof VehicleEntity hull) return HandheldRadioItem.representativeCrew(hull);
        return entity instanceof LivingEntity living ? living : null;
    }

    private static void hintRadioEntity() {
        Player player = Minecraft.getInstance().player;
        if (player == null) return;
        if (pendingRadioEntityId < 0) {
            hint("message.tacz_sewv.radio.pick_entity");
            return;
        }
        Entity entity = player.level().getEntity(pendingRadioEntityId);
        Component name = entity != null ? entity.getDisplayName() : Component.literal("?");
        player.displayClientMessage(
                Component.translatable("message.tacz_sewv.radio.entity_armed", name)
                        .withStyle(ChatFormatting.GRAY), true);
    }

    private static void liveSelectClick(Minecraft mc) {
        if (mc.player == null) return;
        Entity hit = rayTraceSelectable(mc.player, LIVE_PICK_RANGE);
        if (hit == null) {
            hint("message.tacz_sewv.tdt.live_sel.miss");
            return;
        }
        if (!toggleLiveHit(mc.player, hit)) {
            hint("message.tacz_sewv.tdt.live_sel.miss");
            return;
        }
        promptCooldown = 0; // refresh the count on the next tick
    }

    /**
     * Right-click finish: keep the selection and reopen the TDT when at least one unit is
     * selected; otherwise cancel. Selection is already live in {@link TdtSelection}.
     */
    private static void finishLiveSelection() {
        int n = TdtSelection.selectedCount();
        clearLiveSelection();
        if (n < 1) {
            hint("message.tacz_sewv.tdt.live_sel.cancelled");
            return;
        }
        TdtSelection.writeSnapshot();
        TdtScreen.open();
    }

    /** Toggle an owned PMC, or the owned crew of a vehicle (group select / deselect). */
    private static boolean toggleLiveHit(Player player, Entity hit) {
        if (hit instanceof PmcUnitEntity pmc && pmc.isOwnedBy(player)) {
            TdtSelection.toggle(pmc.getId());
            return true;
        }
        if (hit instanceof VehicleEntity vehicle) {
            List<Integer> crew = new ArrayList<>();
            for (Entity p : vehicle.getPassengers()) {
                if (p instanceof PmcUnitEntity pmc && pmc.isOwnedBy(player)) {
                    crew.add(pmc.getId());
                }
            }
            if (crew.isEmpty()) return false;
            boolean any = false;
            for (int id : crew) {
                if (TdtSelection.isSelected(id)) {
                    any = true;
                    break;
                }
            }
            if (any) {
                for (int id : crew) TdtSelection.deselect(id);
            } else {
                for (int id : crew) TdtSelection.select(id);
            }
            return true;
        }
        return false;
    }

    /**
     * Entity pick that includes owned PMCs — SEM's {@link CommanderRayTrace#rayTraceEntity}
     * deliberately skips them (escort wants the hull).
     */
    private static Entity rayTraceSelectable(Player player, double distance) {
        Vec3 eye = player.getEyePosition(1.0F);
        Vec3 look = player.getViewVector(1.0F);
        Vec3 reach = eye.add(look.scale(distance));
        AABB box = player.getBoundingBox().expandTowards(look.scale(distance)).inflate(1.0D);

        Entity best = null;
        double closest = distance;
        for (Entity entity : player.level().getEntities(player, box)) {
            if (!(entity instanceof PmcUnitEntity) && !(entity instanceof VehicleEntity)) continue;
            AABB bb = entity.getBoundingBox().inflate(entity.getPickRadius());
            Optional<Vec3> hit = bb.clip(eye, reach);
            if (hit.isEmpty()) continue;
            double dist = eye.distanceTo(hit.get());
            if (dist < closest) {
                best = entity;
                closest = dist;
            }
        }
        return best;
    }

    private static void clearEscort() {
        pendingEscort = false;
        pendingEscortUnits = List.of();
        promptCooldown = 0;
    }

    private static void clearGuard() {
        pendingGuard = false;
        pendingGuardUnits = List.of();
        promptCooldown = 0;
    }

    private static void clearEntrench() {
        pendingEntrench = false;
        pendingEntrenchUnits = List.of();
        promptCooldown = 0;
    }

    private static void clearLiveSelection() {
        pendingLiveSelection = false;
        promptCooldown = 0;
    }

    private static void clearJoinPlatoon() {
        pendingJoinPlatoon = false;
        pendingJoinPlatoonUnits = List.of();
        promptCooldown = 0;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        MapMarkers.clear();
        MapTrenchMarkers.clear();
        AirportPlots.clear();
        InvasionHudClient.clear();
        clearEscort();
        clearGuard();
        clearEntrench();
        clearLiveSelection();
        clearRadioPick();
        clearJoinPlatoon();
    }

    private static void hint(String key) {
        Player player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(Component.translatable(key).withStyle(ChatFormatting.GRAY), true);
        }
    }

    private static void hintLive() {
        Player player = Minecraft.getInstance().player;
        if (player == null) return;
        player.displayClientMessage(
                Component.translatable("message.tacz_sewv.tdt.live_sel.select",
                        TdtSelection.selectedCount()).withStyle(ChatFormatting.GRAY),
                true);
    }
}
