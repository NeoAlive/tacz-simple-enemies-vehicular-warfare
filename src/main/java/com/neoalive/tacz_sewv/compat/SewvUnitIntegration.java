package com.neoalive.tacz_sewv.compat;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.data.vehicle.VehicleData;
import com.atsuishio.superbwarfare.data.vehicle.subdata.OBBInfo;
import com.atsuishio.superbwarfare.data.vehicle.subdata.SeatInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.nekoyuni.SimpleEnemyMod.bridge.IFormationMember;
import net.nekoyuni.SimpleEnemyMod.bridge.IPmcDowned;
import net.nekoyuni.SimpleEnemyMod.compat.geckolib.GeckoCompat;
import net.nekoyuni.SimpleEnemyMod.compat.geckolib.GeckoCompatClient;
import net.nekoyuni.SimpleEnemyMod.entity.ai.goals.NoPlayerHurtByTargetGoal;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.util.SoldierState;
import net.nekoyuni.SimpleEnemyMod.integration.UnitHooks;
import net.nekoyuni.SimpleEnemyMod.procedural.events.system.DynamicEvent;
import org.joml.Vector3f;

import com.neoalive.tacz_sewv.client.ArmorModelSupport;
import com.neoalive.tacz_sewv.client.DownedUnitPose;
import com.neoalive.tacz_sewv.client.SandbagSeatPose;
import com.neoalive.tacz_sewv.client.skin.CrewSkinRegistry;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.crew.CrewRadio;
import com.neoalive.tacz_sewv.crew.NpcIdentity;
import com.neoalive.tacz_sewv.crew.OrderAuth;
import com.neoalive.tacz_sewv.entity.SandbagSeatEntity;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleWeapons;
import com.neoalive.tacz_sewv.entity.ai.goal.CasRelayGoal;
import com.neoalive.tacz_sewv.entity.ai.goal.DiplomacyEnemyTargetGoal;
import com.neoalive.tacz_sewv.entity.ai.goal.DownedGoal;
import com.neoalive.tacz_sewv.entity.ai.goal.EscortGoal;
import com.neoalive.tacz_sewv.entity.ai.goal.FobPatrolGoal;
import com.neoalive.tacz_sewv.entity.ai.goal.FobResupplyGoal;
import com.neoalive.tacz_sewv.entity.ai.goal.FobRouteArrivalGoal;
import com.neoalive.tacz_sewv.entity.ai.goal.FobScrambleGoal;
import com.neoalive.tacz_sewv.entity.ai.goal.FollowCommanderGoal;
import com.neoalive.tacz_sewv.entity.ai.goal.MedicGoal;
import com.neoalive.tacz_sewv.entity.ai.goal.MoveToPositionGoal;
import com.neoalive.tacz_sewv.entity.ai.goal.NoFriendlyHurtByTargetGoal;
import com.neoalive.tacz_sewv.entity.ai.goal.PathwayGoal;
import com.neoalive.tacz_sewv.entity.ai.goal.PathwayPassiveGoal;
import com.neoalive.tacz_sewv.entity.ai.goal.PlatoonCohesionGoal;
import com.neoalive.tacz_sewv.entity.ai.goal.PlayerReviveGoal;
import com.neoalive.tacz_sewv.entity.ai.goal.PmcAmmoWatchGoal;
import com.neoalive.tacz_sewv.entity.ai.goal.PmcCaptureMedicGoal;
import com.neoalive.tacz_sewv.entity.ai.goal.PmcCombatDebugGoal;
import com.neoalive.tacz_sewv.entity.ai.goal.PmcReviveGoal;
import com.neoalive.tacz_sewv.entity.ai.goal.RadioObserverGoal;
import com.neoalive.tacz_sewv.entity.ai.goal.RepairGoal;
import com.neoalive.tacz_sewv.entity.ai.goal.SweepInfantryGoal;
import com.neoalive.tacz_sewv.entity.ai.goal.VehicleAiGoals;
import com.neoalive.tacz_sewv.entity.ai.navigation.VehiclePathObstacles;
import com.neoalive.tacz_sewv.entity.ai.support.EntrenchSupport;
import com.neoalive.tacz_sewv.entity.ai.support.FollowLeash;
import com.neoalive.tacz_sewv.entity.ai.support.FormationComposition;
import com.neoalive.tacz_sewv.entity.ai.support.FormationShape;
import com.neoalive.tacz_sewv.entity.ai.support.GrenadeSupport;
import com.neoalive.tacz_sewv.entity.ai.support.GuardSupport;
import com.neoalive.tacz_sewv.entity.ai.support.PatrolSupport;
import com.neoalive.tacz_sewv.entity.ai.support.SmallArmsSupport;
import com.neoalive.tacz_sewv.entity.ai.support.SupportRole;
import com.neoalive.tacz_sewv.entity.ai.support.TowRecoverySupport;
import com.neoalive.tacz_sewv.entity.ai.support.UnitHolster;
import com.neoalive.tacz_sewv.entity.ai.support.VehicleCrew;
import com.neoalive.tacz_sewv.entity.ai.support.VehicleFormation;
import com.neoalive.tacz_sewv.fob.FobSupport;
import com.neoalive.tacz_sewv.init.ModGameRules;
import com.neoalive.tacz_sewv.notify.HudNotify;
import com.neoalive.tacz_sewv.order.OrderFailure;
import com.neoalive.tacz_sewv.order.OrderReport;
import com.neoalive.tacz_sewv.spawn.AmbientSpawnGate;
import com.neoalive.tacz_sewv.spawn.TankSpawner;

/**
 * Combined Arms listener for SEM {@link UnitHooks}. Replaces the former SEM-targeting mixins.
 */
public final class SewvUnitIntegration implements UnitHooks.Listener {

    private static final double FAR_SCALE = 2.5;
    private static final double GARRISON_RADIUS = 40.0;
    private static final int GARRISON_MIN_OFFSET = 6;
    private static final int GARRISON_OFFSET_RANGE = 7;

    private static final Map<Integer, Integer> DRIVER_GLOW = new HashMap<>();
    private static final Map<EntityType<?>, boolean[]> HIDDEN_SEATS = new IdentityHashMap<>();
    private static final DustParticleOptions SELECTION_PARTICLE =
            new DustParticleOptions(new Vector3f(0.0F, 1.0F, 0.3F), 1.0F);

    @Override
    public boolean shouldBlockSetTarget(AbstractUnit unit, LivingEntity target) {
        boolean diplEnemy = VehicleTargeting.isDiplomacyEnemy(unit, target);
        boolean sameClassFriendly = VehicleTargeting.isFriendly(unit, target);
        boolean medic = VehicleTargeting.isMedic(target);
        boolean supportRefuse = SupportRole.refusesTarget(unit, target);

        if (diplEnemy) {
            return !VehicleTargeting.categoryAllowed(unit, target);
        }
        if (sameClassFriendly || medic) {
            return true;
        }
        if (SmallArmsSupport.refusesTarget(unit, target)) {
            return true;
        }
        if (supportRefuse) {
            return true;
        }
        if (unit instanceof PmcUnitEntity pmc && PatrolSupport.refusesOutOfAreaTarget(pmc, target)) {
            return true;
        }
        return !VehicleTargeting.categoryAllowed(unit, target);
    }

    @Override
    public void afterSetTarget(AbstractUnit unit, @Nullable LivingEntity target) {
        if (target == null) return;
        if (!(unit instanceof PmcUnitEntity pmc)) return;
        if (unit.level().isClientSide()) return;
        if (unit.getTarget() != target) return;
        HudNotify.pmcEngaging(pmc, target);
    }

    @Override
    public @Nullable LivingEntity keepPlayerTarget(PmcUnitEntity unit, Player pending) {
        return VehicleTargeting.isDiplomacyEnemy(unit, pending) ? pending : null;
    }

    @Override
    public void onSetupPmcGoals(PmcUnitEntity unit) {
        Mob mob = unit;
        if (PlayerReviveCompat.isLoaded()) {
            mob.goalSelector.addGoal(0, new DownedGoal(unit));
        }
        mob.goalSelector.addGoal(1, new RadioObserverGoal(unit));
        mob.goalSelector.addGoal(0, new FobScrambleGoal(unit));
        mob.goalSelector.addGoal(1, new FobResupplyGoal(unit));
        mob.goalSelector.addGoal(1, new FobRouteArrivalGoal(unit));
        mob.goalSelector.addGoal(1, new FobPatrolGoal(unit));
        mob.goalSelector.addGoal(2, new MedicGoal(unit));
        if (PlayerReviveCompat.isLoaded()) {
            mob.goalSelector.addGoal(1, new PlayerReviveGoal(unit));
            mob.goalSelector.addGoal(1, new PmcReviveGoal(unit));
        }
        mob.goalSelector.addGoal(2, new RepairGoal(unit));
        mob.goalSelector.addGoal(2, new UnitHolster.HolsterGoal(unit));
        mob.goalSelector.addGoal(1, new FollowCommanderGoal(unit));
        mob.goalSelector.addGoal(1, new MoveToPositionGoal(unit));
        mob.goalSelector.addGoal(1, new EscortGoal(unit));
        mob.goalSelector.addGoal(1, new SweepInfantryGoal(unit));
        mob.goalSelector.addGoal(1, new PathwayGoal(unit));
        mob.goalSelector.addGoal(4, new PathwayPassiveGoal(unit));
        mob.goalSelector.addGoal(1, new PlatoonCohesionGoal(unit));
        mob.goalSelector.addGoal(1, new PmcCombatDebugGoal(unit));
        mob.goalSelector.addGoal(2, new PmcCaptureMedicGoal(unit));
        mob.goalSelector.addGoal(5, new PmcAmmoWatchGoal(unit));
        VehicleAiGoals.addDriveGoals(unit);

        mob.targetSelector.removeAllGoals(g -> g instanceof NoPlayerHurtByTargetGoal);
        mob.targetSelector.addGoal(1,
                new NoFriendlyHurtByTargetGoal(unit, true).setAlertOthers().setUnseenMemoryTicks(600));
        mob.targetSelector.addGoal(2, new DiplomacyEnemyTargetGoal<>(unit, Player.class));
        mob.targetSelector.addGoal(2, new DiplomacyEnemyTargetGoal<>(unit, PmcUnitEntity.class));
    }

    @Override
    public void onSetupFactionGoals(AbstractUnit unit) {
        VehicleAiGoals.addDriveGoals(unit);
        Mob mob = unit;
        mob.goalSelector.addGoal(1, new CasRelayGoal(unit));
        mob.targetSelector.removeAllGoals(g -> g.getClass() == HurtByTargetGoal.class);
        NoFriendlyHurtByTargetGoal retaliate = new NoFriendlyHurtByTargetGoal(unit, false);
        if (unit instanceof USunitEntity) {
            retaliate.setAlertOthers(USunitEntity.class);
        } else {
            retaliate.setAlertOthers();
        }
        retaliate.setUnseenMemoryTicks(600);
        mob.targetSelector.addGoal(1, retaliate);
    }

    @Override
    public void onPmcFormationIndexSet(PmcUnitEntity unit, int index) {
        if (!unit.isAddedToWorld()) return;
        unit.sewv$setFormationDirection(null);
        unit.getPersistentData().remove(IFormationMember.TAG_FORMATION_SHAPE);
        unit.getPersistentData().remove(IFormationMember.TAG_FORMATION_ROWSIZE);
        unit.getPersistentData().remove(IFormationMember.TAG_FORMATION_WIDTH);
        unit.getPersistentData().remove(IFormationMember.TAG_FORMATION_LENGTH);
    }

    @Override
    public void onPmcOwnerSet(PmcUnitEntity unit, UUID uuid) {
        if (uuid != null && unit.isAddedToWorld() && !unit.level().isClientSide) {
            NpcIdentity.reissue(unit);
        }
    }

    @Override
    public @Nullable SoundEvent hurtSound(AbstractUnit unit, SoundEvent original) {
        return radioMute(unit) ? null : original;
    }

    @Override
    public @Nullable SoundEvent deathSound(AbstractUnit unit, SoundEvent original) {
        return radioMute(unit) ? null : original;
    }

    @Override
    public boolean muteAlertSound(AbstractUnit unit) {
        return radioMute(unit);
    }

    @Override
    public void onTargetAcquired(AbstractUnit unit, LivingEntity target) {
        if (!(unit.getVehicle() instanceof VehicleEntity hull)) return;
        if (!CrewRadio.enabled()) return;
        if (VehicleTargeting.isFriendly(unit, target)) return;
        LivingEntity old = unit.getTarget();
        if (old == target) return;
        if (old != null && old.isAlive()) return;
        CrewRadio.play(hull, targetLine(target));
    }

    @Override
    public boolean suppressRifleFire(AbstractUnit unit) {
        if (VehicleWeapons.controlsVehicleWeapon(unit)) return true;
        return UnitHolster.isThrowingGrenade(unit) || GrenadeSupport.isGrenadeItem(unit.getMainHandItem());
    }

    @Override
    public boolean suppressChaseAdvance(AbstractUnit unit) {
        return FollowLeash.leashed(unit) || FobSupport.hasRoutePending(unit);
    }

    @Override
    public boolean suppressOnFootTactical(AbstractUnit unit) {
        return VehicleCrew.suppressOnFootAi(unit) || FollowLeash.ownsMove(unit);
    }

    @Override
    public boolean suppressTacticalManager(AbstractUnit unit) {
        if (!FollowLeash.ownsMove(unit)) return false;
        unit.releaseMovementLock();
        if (unit.getTarget() != null && unit.getTarget().isAlive()) {
            if (unit.getSoldierState() != SoldierState.ENGAGE && unit.getSoldierState() != SoldierState.IDLE) {
                unit.setSoldierState(SoldierState.ENGAGE);
            }
        } else if (unit.getSoldierState() != SoldierState.IDLE) {
            unit.setSoldierState(SoldierState.IDLE);
        }
        return true;
    }

    @Override
    public @Nullable LivingEntity commanderOrderVisibleTarget(AbstractUnit unit, @Nullable LivingEntity real) {
        return (real != null && FollowLeash.leashed(unit)) ? null : real;
    }

    @Override
    public @Nullable Vec3 formationSlotOverride(
            PathfinderMob mob, LivingEntity owner, OrderType order, int index) {
        Direction axis = formationAxis(mob);
        if (axis == null || index < 0) return null;
        IFormationMember member = (IFormationMember) mob;
        FormationShape shape = FormationShape.byId(member.sewv$getFormationShape());
        int rowSize = member.sewv$getFormationRowSize();
        if (rowSize < 1) rowSize = 4;
        return VehicleFormation.slotCenter(
                owner.position(), axis, shape, index, rowSize,
                FormationComposition.SPACING_INFANTRY,
                member.sewv$getFormationWidth(),
                member.sewv$getFormationLength());
    }

    @Override
    public boolean skipFormationPredecessor(PathfinderMob mob) {
        return formationAxis(mob) != null;
    }

    @Override
    public boolean gunLineOfSight(Mob mob, LivingEntity target, boolean semLos) {
        if (!semLos) return false;
        return !VehiclePathObstacles.occludesLos(mob, target);
    }

    @Override
    public boolean hideHeldGunLayer(LivingEntity entity) {
        return UnitHolster.hideHeldItems(entity);
    }

    @Override
    public boolean isFriendlyFire(AbstractUnit attacker, LivingEntity victim, boolean semAnswer) {
        if (VehicleTargeting.isDiplomacyEnemy(attacker, victim)) {
            return false;
        }
        return semAnswer;
    }

    @Override
    public boolean beforeIssueOrder(
            ServerPlayer sender, @Nullable PmcUnitEntity unit, OrderType order, int entityId) {
        if (unit == null) {
            OrderReport.fail(sender, OrderFailure.NOT_A_UNIT);
            return false;
        }
        if (!OrderAuth.check(sender, unit, "PacketIssueOrder")) {
            OrderReport.fail(sender, OrderFailure.NOT_OWNED);
            return false;
        }
        if (FobSupport.blocksOrders(unit)) {
            OrderReport.fail(sender, OrderFailure.FOB_COMMAND);
            return false;
        }
        if (FobSupport.hasRoutePending(unit)) {
            OrderReport.fail(sender, OrderFailure.ROUTE_ACTIVE);
            return false;
        }
        if (unit.sewv$isDowned()) {
            OrderReport.fail(sender, OrderFailure.UNIT_DOWNED);
            return false;
        }
        if (order == OrderType.FORM_WEDGE || order == OrderType.FORM_COLUMN) {
            sender.displayClientMessage(
                    Component.translatable("message.tacz_sewv.formation.use_quickwheel")
                            .withStyle(ChatFormatting.GRAY),
                    true);
            return false;
        }
        OrderReport.okEach(sender, "message.tacz_sewv.tdt.order", ChatFormatting.GREEN);

        if (unit.sewv$getPatrolOrigin() != null || unit.sewv$hasInfantrySweep()) {
            PatrolSupport.clearSweepMembership(unit, "PacketIssueOrder");
        }
        EntrenchSupport.clear(unit);
        GuardSupport.clearReach(unit);
        unit.tacz_sewv$setEscortTargetId(-1);
        TowRecoverySupport.clearIfTowering(unit);
        if (unit.sewv$hasCaptureOrder()) {
            unit.sewv$clearCaptureOrder();
        }
        unit.sewv$clearPathway();

        if (unit.getVehicle() instanceof VehicleEntity hull && hull.getFirstPassenger() == unit) {
            CrewRadio.play(hull, CrewRadio.Line.ORDER_DISPATCH);
        }
        return true;
    }

    @Override
    public void afterCombatEvent(ServerLevel level, ServerPlayer player, BlockPos centerPos, boolean success) {
        if (!success) return;
        int separation = 24;
        if (level.getGameRules().getBoolean(ModGameRules.TANKS_IN_EVENTS)) {
            if (level.random.nextDouble() < SewvConfig.TANK_SPAWN_CHANCE_RU.get()) {
                BlockPos posRu = TankSpawner.adjustHeight(level, centerPos.offset(separation, 0, 0));
                TankSpawner.spawnCombatVehicleWithCrew(level, posRu, TankSpawner.TankFaction.RU, null);
            }
            if (level.random.nextDouble() < SewvConfig.TANK_SPAWN_CHANCE_US.get()) {
                BlockPos posUs = TankSpawner.adjustHeight(level, centerPos.offset(-separation, 0, 0));
                TankSpawner.spawnCombatVehicleWithCrew(level, posUs, TankSpawner.TankFaction.US, null);
            }
        }
        if (SewvConfig.PLANES_IN_EVENTS.get()) {
            int airSep = 32;
            if (level.random.nextDouble() < SewvConfig.PLANE_SPAWN_CHANCE_RU.get()
                    && TankSpawner.hasSpawnablePlane(level, TankSpawner.TankFaction.RU)) {
                BlockPos posRu = TankSpawner.adjustHeight(level, centerPos.offset(airSep, 0, 0));
                TankSpawner.spawnPlaneWithCrew(level, posRu, TankSpawner.TankFaction.RU, null);
            }
            if (level.random.nextDouble() < SewvConfig.PLANE_SPAWN_CHANCE_US.get()
                    && TankSpawner.hasSpawnablePlane(level, TankSpawner.TankFaction.US)) {
                BlockPos posUs = TankSpawner.adjustHeight(level, centerPos.offset(-airSep, 0, 0));
                TankSpawner.spawnPlaneWithCrew(level, posUs, TankSpawner.TankFaction.US, null);
            }
        }
    }

    @Override
    public boolean allowAmbientEventTick(ServerLevel level) {
        return AmbientSpawnGate.allows(level);
    }

    @Override
    public int[] scaleEventDistance(int minDistance, int maxDistance) {
        if (SewvConfig.FAR_EVENT_SPAWNS.get()) {
            return new int[] {(int) (minDistance * FAR_SCALE), (int) (maxDistance * FAR_SCALE)};
        }
        return new int[] {minDistance, maxDistance};
    }

    @Override
    public void afterEventExecute(
            DynamicEvent event, ServerLevel level, ServerPlayer player, BlockPos pos, boolean success) {
        if (success) {
            HudNotify.eventNearby(player, event.getId(), pos);
        }
    }

    @Override
    public boolean allowSpawnGuard(ServerLevel level, BlockPos basePos, boolean isRu) {
        return AmbientSpawnGate.allows(level);
    }

    @Override
    public void onSpawnGuard(ServerLevel level, BlockPos basePos, boolean isRu) {
        if (!AmbientSpawnGate.allowsAt(level, basePos)) return;
        if (!SewvConfig.GARRISON_VEHICLES_ENABLED.get()) return;
        level.getServer().execute(() -> {
            if (!level.getEntitiesOfClass(VehicleEntity.class, new AABB(basePos).inflate(GARRISON_RADIUS)).isEmpty()) {
                return;
            }
            int chance = (int) Math.round(SewvConfig.GARRISON_VEHICLE_CHANCE.get() * 100.0);
            if (new Random(basePos.asLong()).nextInt(100) >= chance) return;
            BlockPos spot = TankSpawner.adjustHeight(level, garrisonOffset(level, basePos));
            if (!level.isLoaded(spot)) return;
            TankSpawner.TankFaction faction = isRu ? TankSpawner.TankFaction.RU : TankSpawner.TankFaction.US;
            TankSpawner.spawnTankWithCrew(level, spot, faction, null);
        });
    }

    // --- Client ---

    @Override
    public int remapGlowEntityId(int entityId, boolean adding) {
        if (!FMLEnvironment.dist.isClient()) return entityId;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return entityId;
        if (adding) {
            Entity entity = minecraft.level.getEntity(entityId);
            if (entity instanceof PmcUnitEntity pmc
                    && pmc.getVehicle() instanceof VehicleEntity vehicle
                    && vehicle.getFirstPassenger() == pmc) {
                int vehicleId = vehicle.getId();
                DRIVER_GLOW.put(entityId, vehicleId);
                return vehicleId;
            }
            return entityId;
        }
        Integer vehicleId = DRIVER_GLOW.remove(entityId);
        return vehicleId != null ? vehicleId : entityId;
    }

    @Override
    public void clearGlowMaps() {
        DRIVER_GLOW.clear();
    }

    @Override
    public boolean spawnCustomGlowOutline(Level level, Entity entity) {
        if (!(entity instanceof VehicleEntity vehicle)) return false;
        double halfWidth = Math.max(1.0, vehicle.getBbWidth() / 2.0);
        double halfLength = halfWidth;
        for (OBBInfo obb : vehicle.getObb()) {
            Vec3 size = obb.getSize();
            Vec3 position = obb.getPosition();
            halfWidth = Math.max(halfWidth, Math.abs(position.x) + Math.abs(size.x));
            halfLength = Math.max(halfLength, Math.abs(position.z) + Math.abs(size.z));
        }
        halfWidth += 0.35;
        halfLength += 0.35;
        double yaw = Math.toRadians(vehicle.getYRot());
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        int widthSteps = Math.max(2, (int) Math.ceil(halfWidth * 2.0 / 1.5));
        int lengthSteps = Math.max(2, (int) Math.ceil(halfLength * 2.0 / 1.5));
        for (int i = 0; i <= widthSteps; i++) {
            double x = -halfWidth + (halfWidth * 2.0 * i / widthSteps);
            spawnGlowParticle(level, vehicle, x, -halfLength, sin, cos);
            spawnGlowParticle(level, vehicle, x, halfLength, sin, cos);
        }
        for (int i = 1; i < lengthSteps; i++) {
            double z = -halfLength + (halfLength * 2.0 * i / lengthSteps);
            spawnGlowParticle(level, vehicle, -halfWidth, z, sin, cos);
            spawnGlowParticle(level, vehicle, halfWidth, z, sin, cos);
        }
        return true;
    }

    @Override
    public @Nullable ResourceLocation unitBodySkin(AbstractUnit entity) {
        return CrewSkinRegistry.bodySkin(entity);
    }

    @Override
    public boolean hideMountedUnit(AbstractUnit entity) {
        if (!(entity.getVehicle() instanceof VehicleEntity vehicle)) return false;
        int seat = vehicle.getTagSeatIndex(entity);
        boolean[] hidden = HIDDEN_SEATS.computeIfAbsent(vehicle.getType(), SewvUnitIntegration::hiddenSeats);
        return seat >= 0 && seat < hidden.length && hidden[seat];
    }

    @Override
    public boolean skipCustomModelArmor(
            AbstractUnit entity, ItemStack stack, EquipmentSlot slot, Object defaultModel) {
        if (!(defaultModel instanceof net.minecraft.client.model.HumanoidModel<?> humanoid)) {
            return false;
        }
        @SuppressWarnings("unchecked")
        net.minecraft.client.model.HumanoidModel<LivingEntity> typed =
                (net.minecraft.client.model.HumanoidModel<LivingEntity>) humanoid;
        return ArmorModelSupport.hasCustomModel(entity, stack, slot, typed);
    }

    @Override
    public boolean applySeatPose(Object rootObj, Entity entity) {
        if (!(rootObj instanceof ModelPart root) || !root.hasChild("unit")) return false;
        ModelPart unit = root.getChild("unit");
        if (entity.getVehicle() instanceof SandbagSeatEntity) {
            SandbagSeatPose.applyToUnit(unit);
            return true;
        }
        if (!(entity.getVehicle() instanceof VehicleEntity)) return false;
        setLeg(unit, "rightLeg", -1.4137167F, 0.31415927F, 0.07853982F);
        setLeg(unit, "leftLeg", -1.4137167F, -0.31415927F, -0.07853982F);
        return true;
    }

    @Override
    public boolean applyDownedPose(Object rootObj, Entity entity, float ageInTicks) {
        if (!(entity instanceof IPmcDowned downed) || !downed.sewv$isDownedSynced()) return false;
        if (!(rootObj instanceof ModelPart root)) return false;
        root.getAllParts().forEach(ModelPart::resetPose);
        DownedUnitPose.applyToUnit(root, ageInTicks);
        return true;
    }

    @Override
    public boolean applyCapturedPose(Object rootObj, Entity entity, float ageInTicks) {
        if (!(entity instanceof net.nekoyuni.SimpleEnemyMod.bridge.IMedicCaptured captured)) return false;
        if (!captured.sewv$isCapturedSynced()) return false;
        if (!(rootObj instanceof ModelPart root)) return false;
        root.getAllParts().forEach(ModelPart::resetPose);
        DownedUnitPose.applyToUnit(root, ageInTicks);
        return true;
    }

    @Override
    public boolean skipNonGeckoCuriosArmor(ItemStack stack) {
        if (!GeckoCompat.LOADED) return false;
        if (stack.isEmpty() || GeckoCompatClient.isGeckoArmor(stack)) return false;
        return true;
    }

    private static boolean radioMute(AbstractUnit unit) {
        return CrewRadio.enabled() && unit.getVehicle() instanceof VehicleEntity;
    }

    private static CrewRadio.Line targetLine(LivingEntity target) {
        if (target.getVehicle() instanceof VehicleEntity v) {
            if (HullFacts.isHelicopterHull(v)) return CrewRadio.Line.TARGET_HELICOPTER;
            if (HullFacts.isPlaneHull(v)) return CrewRadio.Line.TARGET_PLANE;
            if (HullFacts.isShipHull(v)) return CrewRadio.Line.TARGET_SHIP;
            if (HullFacts.isGroundMobileHull(v) && !HullFacts.isIfvHull(v)) {
                return CrewRadio.Line.TARGET_TANK;
            }
        }
        return CrewRadio.Line.TARGET_GENERIC;
    }

    @Nullable
    private static Direction formationAxis(PathfinderMob mob) {
        if (!(mob instanceof PmcUnitEntity pmc)) return null;
        OrderType order = pmc.getOrder();
        if (order != OrderType.FORM_WEDGE && order != OrderType.FORM_COLUMN) return null;
        return ((IFormationMember) pmc).sewv$getFormationDirection();
    }

    private static BlockPos garrisonOffset(ServerLevel level, BlockPos basePos) {
        int dx = GARRISON_MIN_OFFSET + level.random.nextInt(GARRISON_OFFSET_RANGE);
        int dz = GARRISON_MIN_OFFSET + level.random.nextInt(GARRISON_OFFSET_RANGE);
        if (level.random.nextBoolean()) dx = -dx;
        if (level.random.nextBoolean()) dz = -dz;
        return basePos.offset(dx, 0, dz);
    }

    private static void spawnGlowParticle(
            Level level, VehicleEntity vehicle, double localX, double localZ, double sin, double cos) {
        double x = vehicle.getX() + localX * cos - localZ * sin;
        double z = vehicle.getZ() + localX * sin + localZ * cos;
        level.addParticle(SELECTION_PARTICLE, x, vehicle.getY() + 0.05, z, 0.0, 0.0, 0.0);
    }

    private static boolean[] hiddenSeats(EntityType<?> type) {
        try {
            List<SeatInfo> seats = VehicleData.getDefault(type).seats();
            boolean[] hidden = new boolean[seats.size()];
            for (int i = 0; i < hidden.length; i++) hidden[i] = seats.get(i).getHidePassenger();
            return hidden;
        } catch (Throwable ignored) {
            return new boolean[0];
        }
    }

    private static void setLeg(ModelPart unit, String bone, float xRot, float yRot, float zRot) {
        if (!unit.hasChild(bone)) return;
        ModelPart leg = unit.getChild(bone);
        leg.xRot = xRot;
        leg.yRot = yRot;
        leg.zRot = zRot;
    }
}
