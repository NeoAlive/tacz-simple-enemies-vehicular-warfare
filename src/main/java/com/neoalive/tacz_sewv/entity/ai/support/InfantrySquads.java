package com.neoalive.tacz_sewv.entity.ai.support;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.debug.SewvDiag;
import com.neoalive.tacz_sewv.entity.ai.formation.FormationFrame;
import com.neoalive.tacz_sewv.entity.ai.formation.FormationGeometry;
import com.neoalive.tacz_sewv.entity.ai.formation.FormationSpec;

/**
 * RU/US event infantry as squads that move together: a leader who keeps SEM's own wander and investigate,
 * and members holding a wedge behind it ({@code InfantrySquadGoal}).
 *
 * <p><b>Membership is decided where the units are made</b> — {@code EventSpawns.squad} stamps it at spawn,
 * the same "doctrine belongs where the crew is made" rule as issued ammunition — and stored as the leader's
 * <b>UUID</b> in every member's persistent NBT. A UUID is stable across a reload, so unlike a network id it
 * may persist. Nothing cleans it up: a dead or unloaded leader is replaced by the lowest-id member still in
 * reach, who is re-stamped onto everyone it can see — the same self-healing shape as a mortar claim.
 *
 * <p>SEM has squad goals of its own ({@code SquadLeaderHandshakeGoal}) and they are deliberately not used:
 * they are wired by spawn role, and SEM moves its internals between versions (see the friendly-flag gotcha).
 */
public final class InfantrySquads {

    public static final String TAG_SQUAD = "tacz_sewv:squad";

    /** Largest squad; a bigger spawn is split into as many even squads as it takes. */
    public static final int MAX_SQUAD = 8;
    /** Wedge spacing — a touch wider than SEM's 2.0/2.5, so a grenade does not take the whole squad. */
    public static final double SPACING = 3.0;
    /** Leader faster than this (blocks/tick) is on the move: the squad files into a column behind it. */
    public static final double COLUMN_SPEED = 0.12;
    /** How far from the leader a member is still "in the squad" for ranking and succession. */
    public static final double REACH = 48.0;

    private InfantrySquads() {}

    /** Sizes of the squads {@code n} units split into: as few as fit {@link #MAX_SQUAD}, as even as possible. */
    public static int[] squadSizes(int n) {
        if (n <= 0) return new int[0];
        int squads = (n + MAX_SQUAD - 1) / MAX_SQUAD;
        int[] sizes = new int[squads];
        for (int i = 0; i < squads; i++) sizes[i] = n / squads + (i < n % squads ? 1 : 0);
        return sizes;
    }

    /** Stamp {@code units} into squads; the lowest id of each leads it. PMC units are left alone. */
    public static void stamp(List<? extends AbstractUnit> units) {
        List<AbstractUnit> eligible = new ArrayList<>();
        for (AbstractUnit u : units) {
            if (u != null && !(u instanceof PmcUnitEntity)) eligible.add(u);
        }
        eligible.sort(Comparator.comparingInt(Entity::getId));
        int next = 0;
        for (int size : squadSizes(eligible.size())) {
            if (size < 2) {
                next += size; // a squad of one is just a rifleman
                continue;
            }
            UUID leader = eligible.get(next).getUUID();
            for (int i = 0; i < size; i++) {
                eligible.get(next + i).getPersistentData().putUUID(TAG_SQUAD, leader);
            }
            SewvDiag.command("squad stamped leader={} members={}", eligible.get(next).getId(),
                    eligible.subList(next, next + size).stream().map(Entity::getId).toList());
            next += size;
        }
    }

    @Nullable
    public static UUID squadOf(Entity unit) {
        var tag = unit.getPersistentData();
        return tag.hasUUID(TAG_SQUAD) ? tag.getUUID(TAG_SQUAD) : null;
    }

    /** One member's view of its squad: who leads, and which wedge slot it holds. */
    public record View(AbstractUnit leader, int slot, int members) {}

    /**
     * Resolve {@code self}'s squad: the leader, and its slot among the members in reach of that leader (by id,
     * leader excluded). Null when {@code self} is the leader or the squad has dissolved. A missing leader is
     * succeeded by the lowest-id member in reach of {@code self}, and everyone found is re-stamped onto it.
     */
    @Nullable
    public static View resolve(ServerLevel level, AbstractUnit self) {
        UUID squad = squadOf(self);
        if (squad == null) return null;
        AbstractUnit leader = level.getEntity(squad) instanceof AbstractUnit u && u.isAlive() ? u : null;
        if (leader == null) {
            List<AbstractUnit> near = members(level, self.position(), squad);
            if (near.isEmpty()) return null;
            leader = near.get(0);
            for (AbstractUnit m : near) m.getPersistentData().putUUID(TAG_SQUAD, leader.getUUID());
            squad = leader.getUUID();
        }
        if (leader == self) return null;
        List<AbstractUnit> ranks = members(level, leader.position(), squad);
        ranks.remove(leader);
        int slot = ranks.indexOf(self);
        if (slot < 0) return null; // out of reach: not in the squad's shape until it walks back
        return new View(leader, slot, ranks.size());
    }

    private static List<AbstractUnit> members(ServerLevel level, Vec3 around, UUID squad) {
        List<AbstractUnit> out = level.getEntitiesOfClass(AbstractUnit.class, new AABB(around, around).inflate(REACH, 16.0, REACH),
                u -> u.isAlive() && squad.equals(squadOf(u)));
        out.sort(Comparator.comparingInt(Entity::getId));
        return out;
    }

    /**
     * Slot {@code index} of {@code members} behind a leader at {@code leader} heading {@code (fx, fz)}: a wedge
     * at a walk, a column when the leader is {@code moving}. Pure — the self-check pins it.
     */
    public static Vec3 slot(Vec3 leader, double fx, double fz, int index, int members, boolean moving) {
        FormationSpec spec = new FormationSpec(moving ? FormationShape.COLUMN : FormationShape.WEDGE, SPACING,
                1.0, 1.0, 1, FormationSpec.Placement.TRAIL);
        return FormationGeometry.slot(FormationFrame.facing(leader.x, leader.y, leader.z, fx, fz), spec, index,
                members);
    }
}
