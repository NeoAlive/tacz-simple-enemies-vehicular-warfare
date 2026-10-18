package com.neoalive.tacz_sewv.invasion.force;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * Everything a team_base fields: a pool and AI count per vehicle {@link ForceKind}, the
 * {@link InfantrySpec}, and what its players may deploy as. Owns both serialisations, so the
 * block entity, the open/save packets and the editor never list these fields themselves.
 */
public final class BaseForce {

    public static final int MAX_AI_VEHICLES = 32;
    private static final int MAX_POOL = 512;
    private static final int MAX_ID_LEN = 256;

    /** One vehicle kind's pool and the number of AI hulls kept fielded from it. */
    public static final class VehicleSlot {
        public final List<String> pool = new ArrayList<>();
        public int aiCount;

        VehicleSlot copy() {
            VehicleSlot out = new VehicleSlot();
            out.pool.addAll(pool);
            out.aiCount = aiCount;
            return out;
        }
    }

    private final Map<ForceKind, VehicleSlot> vehicles = new EnumMap<>(ForceKind.class);
    private InfantrySpec infantry = new InfantrySpec();
    /** Options a player of this base may pick at (re)spawn. {@link ForceKind#INFANTRY} = on foot. */
    private final EnumSet<ForceKind> playerDeploy = EnumSet.of(ForceKind.GROUND);
    /** Fill the free seats of a player's vehicle with PMC crew. */
    private boolean npcCrewForPlayers;

    public BaseForce() {
        for (ForceKind kind : ForceKind.VEHICLES) {
            vehicles.put(kind, new VehicleSlot());
        }
        vehicles.get(ForceKind.GROUND).aiCount = 1;
    }

    public BaseForce copy() {
        BaseForce out = new BaseForce();
        for (ForceKind kind : ForceKind.VEHICLES) {
            out.vehicles.put(kind, vehicles.get(kind).copy());
        }
        out.infantry = infantry.copy();
        out.playerDeploy.clear();
        out.playerDeploy.addAll(playerDeploy);
        out.npcCrewForPlayers = npcCrewForPlayers;
        return out;
    }

    public VehicleSlot slot(ForceKind kind) {
        if (!kind.isVehicle()) throw new IllegalArgumentException("not a vehicle kind: " + kind);
        return vehicles.get(kind);
    }

    public List<String> pool(ForceKind kind) {
        return slot(kind).pool;
    }

    public int aiCount(ForceKind kind) {
        return slot(kind).aiCount;
    }

    public void setAiCount(ForceKind kind, int count) {
        slot(kind).aiCount = Math.max(0, Math.min(MAX_AI_VEHICLES, count));
    }

    public InfantrySpec infantry() {
        return infantry;
    }

    public Set<ForceKind> playerDeploy() {
        return playerDeploy;
    }

    public boolean npcCrewForPlayers() {
        return npcCrewForPlayers;
    }

    public void setNpcCrewForPlayers(boolean on) {
        this.npcCrewForPlayers = on;
    }

    /**
     * Options a player can actually be given: allowed by {@link #playerDeploy} and, for a
     * vehicle, backed by a non-empty pool. Ordinal order, so the first entry is the timeout pick.
     */
    public List<ForceKind> deployOptions() {
        List<ForceKind> out = new ArrayList<>();
        for (ForceKind kind : ForceKind.values()) {
            if (!playerDeploy.contains(kind)) continue;
            if (kind.isVehicle() && pool(kind).isEmpty()) continue;
            out.add(kind);
        }
        return out;
    }

    /** True when the AI side fields at least one unit of any kind. */
    public boolean fieldsAnyAi() {
        for (ForceKind kind : ForceKind.VEHICLES) {
            if (aiCount(kind) > 0) return true;
        }
        return infantry.minimumCount() > 0;
    }

    /** De-duplicate pools, drop unparsable ids, clamp counts. Server applies this to anything a client sent. */
    public void sanitize() {
        for (ForceKind kind : ForceKind.VEHICLES) {
            VehicleSlot slot = vehicles.get(kind);
            List<String> cleaned = new ArrayList<>();
            for (String id : slot.pool) {
                if (id == null || ResourceLocation.tryParse(id) == null) continue;
                if (!cleaned.contains(id)) cleaned.add(id);
            }
            slot.pool.clear();
            slot.pool.addAll(cleaned);
            setAiCount(kind, slot.aiCount);
        }
        infantry.clamp();
    }

    // ---- NBT ---------------------------------------------------------------------------------

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        for (ForceKind kind : ForceKind.VEHICLES) {
            VehicleSlot slot = vehicles.get(kind);
            CompoundTag v = new CompoundTag();
            ListTag pool = new ListTag();
            for (String id : slot.pool) pool.add(StringTag.valueOf(id));
            v.put("Pool", pool);
            v.putInt("Count", slot.aiCount);
            tag.put(kind.key(), v);
        }
        CompoundTag inf = new CompoundTag();
        infantry.save(inf);
        tag.put(ForceKind.INFANTRY.key(), inf);
        ListTag deploy = new ListTag();
        for (ForceKind kind : playerDeploy) deploy.add(StringTag.valueOf(kind.key()));
        tag.put("Deploy", deploy);
        tag.putBoolean("NpcCrew", npcCrewForPlayers);
        return tag;
    }

    public static BaseForce load(CompoundTag tag) {
        BaseForce out = new BaseForce();
        for (ForceKind kind : ForceKind.VEHICLES) {
            VehicleSlot slot = out.vehicles.get(kind);
            slot.aiCount = 0;
            if (!tag.contains(kind.key(), Tag.TAG_COMPOUND)) continue;
            CompoundTag v = tag.getCompound(kind.key());
            ListTag pool = v.getList("Pool", Tag.TAG_STRING);
            for (int i = 0; i < pool.size(); i++) slot.pool.add(pool.getString(i));
            slot.aiCount = v.getInt("Count");
        }
        if (tag.contains(ForceKind.INFANTRY.key(), Tag.TAG_COMPOUND)) {
            out.infantry.load(tag.getCompound(ForceKind.INFANTRY.key()));
        }
        out.playerDeploy.clear();
        ListTag deploy = tag.getList("Deploy", Tag.TAG_STRING);
        for (int i = 0; i < deploy.size(); i++) {
            ForceKind kind = ForceKind.byKey(deploy.getString(i));
            if (kind != null) out.playerDeploy.add(kind);
        }
        out.npcCrewForPlayers = tag.getBoolean("NpcCrew");
        out.sanitize();
        return out;
    }

    /**
     * Pre-rework team_base: one untyped pool and AI count, players always in a pool hull. Loads as
     * exactly that match — everything in GROUND, players deploy GROUND only.
     */
    public static BaseForce fromLegacy(List<String> vehiclePool, int aiVehicleCount, boolean npcCrew) {
        BaseForce out = new BaseForce();
        out.pool(ForceKind.GROUND).addAll(vehiclePool);
        out.setAiCount(ForceKind.GROUND, aiVehicleCount);
        out.playerDeploy.clear();
        out.playerDeploy.add(ForceKind.GROUND);
        out.npcCrewForPlayers = npcCrew;
        out.sanitize();
        return out;
    }

    // ---- wire --------------------------------------------------------------------------------

    public void write(FriendlyByteBuf buf) {
        for (ForceKind kind : ForceKind.VEHICLES) {
            VehicleSlot slot = vehicles.get(kind);
            buf.writeVarInt(slot.aiCount);
            buf.writeVarInt(slot.pool.size());
            for (String id : slot.pool) buf.writeUtf(id, MAX_ID_LEN);
        }
        infantry.write(buf);
        int mask = 0;
        for (ForceKind kind : playerDeploy) mask |= 1 << kind.ordinal();
        buf.writeVarInt(mask);
        buf.writeBoolean(npcCrewForPlayers);
    }

    public static BaseForce read(FriendlyByteBuf buf) {
        BaseForce out = new BaseForce();
        for (ForceKind kind : ForceKind.VEHICLES) {
            VehicleSlot slot = out.vehicles.get(kind);
            slot.aiCount = buf.readVarInt();
            int n = buf.readVarInt();
            if (n < 0 || n > MAX_POOL) {
                throw new IllegalArgumentException("pool size out of range: " + n);
            }
            for (int i = 0; i < n; i++) slot.pool.add(buf.readUtf(MAX_ID_LEN));
        }
        out.infantry.read(buf);
        int mask = buf.readVarInt();
        out.playerDeploy.clear();
        for (ForceKind kind : ForceKind.values()) {
            if ((mask & (1 << kind.ordinal())) != 0) out.playerDeploy.add(kind);
        }
        out.npcCrewForPlayers = buf.readBoolean();
        out.sanitize();
        return out;
    }
}
