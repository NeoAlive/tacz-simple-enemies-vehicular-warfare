package com.neoalive.tacz_sewv.command.quick;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.sensor.ThreatBearing;
import com.neoalive.tacz_sewv.entity.ai.support.OrderStandDown;
import com.neoalive.tacz_sewv.entity.ai.support.RoleKit;
import com.neoalive.tacz_sewv.entity.ai.support.SupportRole;
import com.neoalive.tacz_sewv.entity.ai.support.mines.MineKinds;
import com.neoalive.tacz_sewv.entity.ai.support.mines.MineSites;
import com.neoalive.tacz_sewv.entity.ai.support.mines.MineSpawner;
import com.neoalive.tacz_sewv.entity.ai.support.mines.MinefieldPlanner;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;
import com.neoalive.tacz_sewv.util.WorldVehiclePools;

/**
 * Deploy Minefield: combat engineers (a shovel in hand) lay the mines they carry between the position
 * and the likely enemy approach. Everything about where is decided here, once, when the order fires:
 * the threat bearing, the shape, which slots can take a mine. The engineers then only walk and plant
 * ({@link MinefieldTracker}, {@code LayMineGoal}).
 *
 * <p>The wire id carries the wheel's choices: {@code quick_minefield:<shape>:<item id | auto>}.
 */
public final class QuickMinefieldPipeline implements QuickCommandPipeline {

    public static final String AUTO = "auto";

    private final MinefieldPlanner.ShapeChoice shape;
    @Nullable
    private final ResourceLocation item;

    public QuickMinefieldPipeline(MinefieldPlanner.ShapeChoice shape, @Nullable ResourceLocation item) {
        this.shape = shape;
        this.item = item;
    }

    public static String id(int shape, String item) {
        return QuickCommandRegistry.ID_QUICK_MINEFIELD + ":" + shape + ":" + item;
    }

    @Nullable
    public static QuickMinefieldPipeline parse(String id) {
        if (!id.startsWith(QuickCommandRegistry.ID_QUICK_MINEFIELD + ":")) return null;
        String[] parts = id.split(":", 3);
        if (parts.length < 3) return null;
        int shape;
        try {
            shape = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }
        ResourceLocation item = AUTO.equals(parts[2]) ? null : ResourceLocation.tryParse(parts[2]);
        return new QuickMinefieldPipeline(MinefieldPlanner.ShapeChoice.byId(shape), item);
    }

    @Override
    public void execute(ServerPlayer issuer, QuickCommandContext context) {
        ServerLevel level = issuer.serverLevel();
        double radius = context.unitRadius(SewvConfig.QUICK_LAND_RADIUS.get());
        List<PmcUnitEntity> engineers = new ArrayList<>();
        for (PmcUnitEntity pmc : QuickCommandUnits.onFootOwned(issuer, level, context.unitIds(), radius)) {
            if (SupportRole.of(pmc) == SupportRole.COMBAT_ENGINEER) engineers.add(pmc);
        }
        if (engineers.isEmpty()) {
            fail(issuer, "message.tacz_sewv.minefield.no_engineers");
            return;
        }
        MineKinds.Kind kind = pickKind(level, engineers);
        if (kind == null) {
            fail(issuer, "message.tacz_sewv.minefield.no_mines");
            return;
        }

        List<PmcUnitEntity> crew = new ArrayList<>();
        List<Integer> carried = new ArrayList<>();
        int total = 0;
        for (PmcUnitEntity pmc : engineers) {
            int n = MinefieldTracker.countMines(RoleKit.inventory(pmc), kind);
            if (n <= 0) continue;
            crew.add(pmc);
            carried.add(n);
            total += n;
        }
        int count = Math.min(total, SewvConfig.MINEFIELD_MAX_MINES.get());

        Vec3 anchor = anchor(issuer, context.aimBlock(), crew);
        Vec3 threat = ThreatBearing.nearest(level, crew.get(0), anchor, SewvConfig.MINEFIELD_THREAT_RADIUS.get());
        Vec3 look = issuer.getLookAngle();
        Vec3 fallback = new Vec3(look.x, 0.0, look.z);
        MinefieldPlanner.Plan plan = MinefieldPlanner.lay(anchor, threat, fallback, kind.placement(), this.shape,
                count, kind.spacing(), SewvConfig.MINEFIELD_STANDOFF.get());

        // Which slots can actually take a mine. A slot that cannot is dropped, not moved far.
        List<MinefieldTracker.Planned> sites = new ArrayList<>();
        List<Vec3> taken = new ArrayList<>();
        for (int i = 0; i < plan.slots().length; i++) {
            MinefieldTracker.Planned p = site(level, kind, plan, i, anchor.y, taken, crew.get(0), issuer);
            if (p == null) continue;
            sites.add(p);
            taken.add(Vec3.atBottomCenterOf(p.pos()));
        }
        if (sites.isEmpty()) {
            Component name = itemName(kind.item());
            NetworkHandler.orderFeedback(issuer, "minefield", "message.tacz_sewv.minefield.no_ground", 0,
                    ChatFormatting.GRAY, name);
            return;
        }

        Vec3[] slotPos = taken.toArray(new Vec3[0]);
        Vec3[] engPos = new Vec3[crew.size()];
        int[] carry = new int[crew.size()];
        for (int i = 0; i < crew.size(); i++) {
            engPos[i] = crew.get(i).position();
            carry[i] = carried.get(i);
        }
        List<List<Integer>> shares = MinefieldPlanner.split(plan.frame(), plan.shape(), slotPos, engPos, carry);

        MinefieldTracker.cancelPlayer(issuer.getUUID());
        MinefieldTracker.Run run = MinefieldTracker.newRun(issuer, kind);
        int working = 0;
        for (int i = 0; i < crew.size(); i++) {
            List<Integer> share = shares.get(i);
            if (share.isEmpty()) continue;
            PmcUnitEntity pmc = crew.get(i);
            OrderStandDown.clearForNewOrder(pmc, "Minefield");
            List<MinefieldTracker.Planned> legPlan = new ArrayList<>(share.size());
            for (int idx : share) legPlan.add(sites.get(idx));
            MinefieldTracker.addLeg(run, pmc, legPlan);
            working++;
        }
        MinefieldTracker.start(run, level.getGameTime(), count - sites.size());
        NetworkHandler.orderFeedback(issuer, "minefield", "message.tacz_sewv.minefield.started", working,
                ChatFormatting.GREEN, sites.size(), itemName(kind.item()));
    }

    /** The chosen pool item, or with AUTO the pooled kind the engineers carry most of. */
    @Nullable
    private MineKinds.Kind pickKind(ServerLevel level, List<PmcUnitEntity> engineers) {
        List<String> pool = WorldVehiclePools.get(level).list(TankFaction.PMC, WorldVehiclePools.Category.MINE);
        MineKinds.Kind best = null;
        int bestCount = 0;
        for (String id : pool) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl == null) continue;
            if (this.item != null && !this.item.equals(rl)) continue;
            MineKinds.Kind kind = MineKinds.of(rl);
            if (kind == null) continue;
            int n = 0;
            for (PmcUnitEntity pmc : engineers) n += MinefieldTracker.countMines(RoleKit.inventory(pmc), kind);
            if (n > bestCount) {
                bestCount = n;
                best = kind;
            }
        }
        return best;
    }

    private static Vec3 anchor(ServerPlayer issuer, @Nullable BlockPos aim, List<PmcUnitEntity> crew) {
        double range = SewvConfig.MINEFIELD_MAX_RANGE.get();
        if (aim != null && issuer.position().distanceToSqr(Vec3.atCenterOf(aim)) <= range * range) {
            return Vec3.atBottomCenterOf(aim.above());
        }
        double x = 0.0;
        double y = 0.0;
        double z = 0.0;
        for (PmcUnitEntity pmc : crew) {
            x += pmc.getX();
            y += pmc.getY();
            z += pmc.getZ();
        }
        return new Vec3(x / crew.size(), y / crew.size(), z / crew.size());
    }

    @Nullable
    private static MinefieldTracker.Planned site(ServerLevel level, MineKinds.Kind kind, MinefieldPlanner.Plan plan,
                                                 int i, double anchorY, List<Vec3> taken, PmcUnitEntity probe,
                                                 ServerPlayer issuer) {
        Vec3 slot = plan.slots()[i];
        if (kind.placement() == MineKinds.Placement.WALL) {
            MineSites.WallSpot spot = MineSites.wall(level, slot, anchorY, plan.frame(), taken, kind.spacing(),
                    issuer.getUUID());
            if (spot == null || !MineSpawner.wallSurvives(level, kind, spot.pos(), spot.face(), probe)) return null;
            return new MinefieldTracker.Planned(spot.pos(), spot.face(), plan.yaws()[i]);
        }
        BlockPos feet = MineSites.ground(level, slot, anchorY, taken, kind.spacing(), issuer.getUUID());
        return feet == null ? null : new MinefieldTracker.Planned(feet, null, plan.yaws()[i]);
    }

    private static Component itemName(ResourceLocation id) {
        Item item = ForgeRegistries.ITEMS.getValue(id);
        return item == null ? Component.literal(id.toString()) : item.getDescription();
    }

    private static void fail(ServerPlayer issuer, String key) {
        NetworkHandler.orderFeedback(issuer, "minefield", key, 0, ChatFormatting.GRAY);
    }
}
