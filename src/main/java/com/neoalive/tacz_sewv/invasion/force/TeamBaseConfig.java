package com.neoalive.tacz_sewv.invasion.force;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.FriendlyByteBuf;

import com.neoalive.tacz_sewv.block.TeamBaseBlockEntity;
import com.neoalive.tacz_sewv.invasion.PmcOwnerKind;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;

/**
 * Every editable team_base setting in one value: the open packet sends it, the editor mutates a
 * copy, the save packet sends it back and {@link #applyTo} writes it. One field list instead of
 * the three hand-synchronised ones (open packet, save packet, screen constructor) it replaced.
 */
public final class TeamBaseConfig {

    private static final int MAX_TEAMS = 512;

    public String assignedTeam = "";
    public boolean playerOwned;
    public TankFaction crewFaction = TankFaction.US;
    public int timeToCaptureSeconds = 30;
    public int radiusInBlocks = 16;
    public String ownedTeam = "";
    public boolean invisible;
    public boolean endInvasionOnCapture;
    public int spawnDelaySeconds;
    public boolean pointsHaveToBeConquered = true;
    public PmcOwnerKind pmcOwnerKind = PmcOwnerKind.NONE;
    public String pmcOwnerValue = "";
    public final List<String> enemyTeams = new ArrayList<>();
    public BaseForce force = new BaseForce();

    public static TeamBaseConfig of(TeamBaseBlockEntity be) {
        TeamBaseConfig c = new TeamBaseConfig();
        c.assignedTeam = be.getAssignedTeam();
        c.playerOwned = be.isPlayerOwned();
        c.crewFaction = be.getCrewFaction();
        c.timeToCaptureSeconds = be.getTimeToCaptureSeconds();
        c.radiusInBlocks = be.getRadiusInBlocks();
        c.ownedTeam = be.getOwnedTeam();
        c.invisible = be.isInvisible();
        c.endInvasionOnCapture = be.isEndInvasionOnCapture();
        c.spawnDelaySeconds = be.getSpawnDelaySeconds();
        c.pointsHaveToBeConquered = be.isPointsHaveToBeConquered();
        c.pmcOwnerKind = be.getPmcOwnerKind();
        c.pmcOwnerValue = be.getPmcOwnerValue();
        c.enemyTeams.addAll(be.getEnemyTeams());
        c.force = be.getForce().copy();
        return c;
    }

    /** Server side: sanitise (the client is not trusted) and write onto the block entity. */
    public void applyTo(TeamBaseBlockEntity be) {
        boolean pmc = crewFaction == TankFaction.PMC;
        be.setAssignedTeam(assignedTeam);
        be.setPlayerOwned(playerOwned);
        be.setCrewFaction(crewFaction);
        be.setTimeToCaptureSeconds(Math.max(1, timeToCaptureSeconds));
        be.setRadiusInBlocks(Math.max(1, radiusInBlocks));
        be.setOwnedTeam(ownedTeam);
        be.setInvisible(invisible);
        be.setEndInvasionOnCapture(endInvasionOnCapture);
        be.setSpawnDelaySeconds(spawnDelaySeconds);
        be.setPointsHaveToBeConquered(pointsHaveToBeConquered);
        be.setPmcOwner(pmc ? pmcOwnerKind : PmcOwnerKind.NONE, pmc ? pmcOwnerValue : "");
        be.setEnemyTeams(enemyTeams);
        BaseForce cleaned = force.copy();
        cleaned.sanitize();
        be.setForce(cleaned);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(assignedTeam);
        buf.writeBoolean(playerOwned);
        buf.writeVarInt(crewFaction.ordinal());
        buf.writeVarInt(timeToCaptureSeconds);
        buf.writeVarInt(radiusInBlocks);
        buf.writeUtf(ownedTeam);
        buf.writeBoolean(invisible);
        buf.writeBoolean(endInvasionOnCapture);
        buf.writeVarInt(spawnDelaySeconds);
        buf.writeBoolean(pointsHaveToBeConquered);
        buf.writeVarInt(pmcOwnerKind.ordinal());
        buf.writeUtf(pmcOwnerValue);
        buf.writeVarInt(enemyTeams.size());
        for (String team : enemyTeams) buf.writeUtf(team);
        force.write(buf);
    }

    public static TeamBaseConfig read(FriendlyByteBuf buf) {
        TeamBaseConfig c = new TeamBaseConfig();
        c.assignedTeam = buf.readUtf();
        c.playerOwned = buf.readBoolean();
        TankFaction[] factions = TankFaction.values();
        c.crewFaction = factions[Math.floorMod(buf.readVarInt(), factions.length)];
        c.timeToCaptureSeconds = buf.readVarInt();
        c.radiusInBlocks = buf.readVarInt();
        c.ownedTeam = buf.readUtf();
        c.invisible = buf.readBoolean();
        c.endInvasionOnCapture = buf.readBoolean();
        c.spawnDelaySeconds = Math.max(0, Math.min(3600, buf.readVarInt()));
        c.pointsHaveToBeConquered = buf.readBoolean();
        c.pmcOwnerKind = PmcOwnerKind.fromOrdinal(buf.readVarInt());
        c.pmcOwnerValue = buf.readUtf();
        int n = buf.readVarInt();
        if (n < 0 || n > MAX_TEAMS) throw new IllegalArgumentException("enemy team count out of range: " + n);
        for (int i = 0; i < n; i++) {
            String team = buf.readUtf();
            if (!team.isEmpty() && !c.enemyTeams.contains(team)) c.enemyTeams.add(team);
        }
        c.force = BaseForce.read(buf);
        return c;
    }
}
