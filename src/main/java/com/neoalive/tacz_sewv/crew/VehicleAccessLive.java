package com.neoalive.tacz_sewv.crew;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.PlayerTeam;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;

import com.neoalive.tacz_sewv.client.SideRelationsClient;
import com.neoalive.tacz_sewv.crew.VehicleAccess.Facts;
import com.neoalive.tacz_sewv.crew.VehicleAccess.Kind;
import com.neoalive.tacz_sewv.crew.VehicleAccess.Occupant;
import com.neoalive.tacz_sewv.crew.VehicleAccess.Stamp;
import com.neoalive.tacz_sewv.crew.VehicleAccess.Verdict;
import com.neoalive.tacz_sewv.crew.VehicleAccess.Viewer;
import com.neoalive.tacz_sewv.diplomacy.DiplomacyData;
import com.neoalive.tacz_sewv.diplomacy.SideRelations;
import com.neoalive.tacz_sewv.invasion.PmcOwnerSupport;

/**
 * Reads a live hull into {@link VehicleAccess} and writes the owner stamp. The decision itself
 * stays in {@link VehicleAccess}.
 */
public final class VehicleAccessLive {

    private VehicleAccessLive() {}

    public static boolean denies(Player player, Entity hull) {
        return verdict(player, hull) != Verdict.ALLOW;
    }

    public static Verdict verdict(Player player, Entity hull) {
        if (!player.level().isClientSide()) ensureStamp(hull);
        DiplomacyData.Relation ru;
        DiplomacyData.Relation us;
        if (player.level().isClientSide()) {
            ru = SideRelationsClient.ru();
            us = SideRelationsClient.us();
        } else if (player instanceof ServerPlayer sp) {
            ru = SideRelations.toward(sp, SideRelations.RU);
            us = SideRelations.toward(sp, SideRelations.US);
        } else {
            ru = null;
            us = null;
        }
        return VehicleAccess.verdict(viewer(player), facts(hull), ru, us);
    }

    /**
     * A mount the access rules reject. RU/US riders are never rejected here: scavenging an empty
     * stamped hull stays their existing behaviour. A PMC is judged as its owner.
     */
    public static boolean deniesMount(Entity rider, Entity hull) {
        if (rider instanceof RUunitEntity || rider instanceof USunitEntity) return false;
        if (rider instanceof Player player) return denies(player, hull);
        if (rider instanceof PmcUnitEntity pmc) return deniesPmc(pmc, hull);
        return false;
    }

    public static boolean deniesPmc(PmcUnitEntity pmc, Entity hull) {
        if (!pmc.level().isClientSide()) ensureStamp(hull);
        DiplomacyData.Relation ru = null;
        DiplomacyData.Relation us = null;
        if (pmc.level() instanceof ServerLevel level) {
            ru = SideRelations.towardOwner(level, pmc.getOwnerUUID(), SideRelations.RU);
            us = SideRelations.towardOwner(level, pmc.getOwnerUUID(), SideRelations.US);
        }
        Viewer viewer = new Viewer(pmc.getOwnerUUID(), PmcOwnerSupport.ownerTeamOf(pmc));
        return VehicleAccess.denies(viewer, facts(hull), ru, us);
    }

    public static void tell(Player player, Verdict verdict) {
        if (player.level().isClientSide() || verdict == Verdict.ALLOW) return;
        String key = verdict == Verdict.HOSTILE
                ? "message.tacz_sewv.interact.enemy_crew"
                : "message.tacz_sewv.interact.not_owner";
        player.displayClientMessage(Component.translatable(key).withStyle(ChatFormatting.GRAY), true);
    }

    /**
     * An empty hull with no stamp takes its owner from the last driver, once. A player stamps a
     * UUID only; a PMC also copies {@code sewv:pmc_owner_team}.
     */
    public static void ensureStamp(Entity hull) {
        if (!(hull instanceof VehicleEntity vehicle) || vehicle.level().isClientSide()) return;
        CompoundTag tag = vehicle.getPersistentData();
        if (tag.hasUUID(VehicleAccess.TAG_OWNER) || hasTeam(tag)) return;
        if (!vehicle.getPassengers().isEmpty()) return;
        Entity last = vehicle.getLastDriver();
        UUID owner = null;
        String team = null;
        if (last instanceof ServerPlayer sp) {
            owner = sp.getUUID();
        } else if (last instanceof PmcUnitEntity pmc) {
            owner = pmc.getOwnerUUID();
            team = PmcOwnerSupport.ownerTeamOf(pmc);
        }
        writeIfEmpty(tag, owner, team);
    }

    /** First authorized player or PMC to mount claims an unstamped hull. Later mounts do not overwrite. */
    public static void stampOnMount(Entity hull, Entity rider) {
        if (hull.level().isClientSide()) return;
        if (rider instanceof RUunitEntity || rider instanceof USunitEntity) return;
        UUID owner = null;
        String team = null;
        if (rider instanceof Player player) {
            owner = player.getUUID();
        } else if (rider instanceof PmcUnitEntity pmc) {
            owner = pmc.getOwnerUUID();
            team = PmcOwnerSupport.ownerTeamOf(pmc);
        } else {
            return;
        }
        writeIfEmpty(hull.getPersistentData(), owner, team);
    }

    /** True when the hull is unstamped, or the stamp authorizes {@code playerId}. */
    public static boolean stampAllows(Entity hull, UUID playerId) {
        Stamp stamp = readStamp(hull);
        if (stamp == null || !stamp.present()) return true;
        String team = null;
        if (hull.level() instanceof ServerLevel level && level.getServer() != null) {
            String name = SideRelations.profileName(level.getServer(), playerId);
            if (name != null) {
                PlayerTeam scoreboard = level.getScoreboard().getPlayersTeam(name);
                if (scoreboard != null) team = scoreboard.getName();
            }
        }
        return VehicleAccess.authorized(new Viewer(playerId, team), stamp.owner(), stamp.team());
    }

    public static Facts facts(Entity hull) {
        List<Occupant> occupants = new ArrayList<>();
        for (Entity passenger : hull.getPassengers()) {
            if (passenger instanceof Player player) {
                occupants.add(new Occupant(Kind.PLAYER, player.getUUID(), null));
            } else if (passenger instanceof PmcUnitEntity pmc) {
                occupants.add(new Occupant(Kind.PMC, pmc.getOwnerUUID(), PmcOwnerSupport.ownerTeamOf(pmc)));
            } else if (passenger instanceof RUunitEntity) {
                occupants.add(new Occupant(Kind.RU, null, null));
            } else if (passenger instanceof USunitEntity) {
                occupants.add(new Occupant(Kind.US, null, null));
            }
        }
        return new Facts(occupants, readStamp(hull));
    }

    @Nullable
    public static Stamp readStamp(Entity hull) {
        CompoundTag tag = hull.getPersistentData();
        UUID owner = tag.hasUUID(VehicleAccess.TAG_OWNER) ? tag.getUUID(VehicleAccess.TAG_OWNER) : null;
        String team = tag.getString(VehicleAccess.TAG_TEAM);
        if (team.isEmpty()) team = null;
        if (owner == null && team == null) return null;
        return new Stamp(owner, team);
    }

    private static Viewer viewer(Player player) {
        PlayerTeam team = player.level().getScoreboard().getPlayersTeam(player.getScoreboardName());
        return new Viewer(player.getUUID(), team == null ? null : team.getName());
    }

    private static boolean hasTeam(CompoundTag tag) {
        return !tag.getString(VehicleAccess.TAG_TEAM).isEmpty();
    }

    private static void writeIfEmpty(CompoundTag tag, @Nullable UUID owner, @Nullable String team) {
        if (owner == null && (team == null || team.isEmpty())) return;
        if (tag.hasUUID(VehicleAccess.TAG_OWNER) || hasTeam(tag)) return;
        if (owner != null) tag.putUUID(VehicleAccess.TAG_OWNER, owner);
        if (team != null && !team.isEmpty()) tag.putString(VehicleAccess.TAG_TEAM, team);
    }
}
