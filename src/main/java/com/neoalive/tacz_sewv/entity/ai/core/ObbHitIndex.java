package com.neoalive.tacz_sewv.entity.ai.core;

import java.util.List;
import java.util.function.Predicate;

import com.atsuishio.superbwarfare.entity.OBBEntity;
import com.atsuishio.superbwarfare.tools.OBB;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.joml.Vector3d;

import com.neoalive.tacz_sewv.bridge.IObbHitIndex;

/**
 * Per-level index of SBW's OBB entities, so a projectile's hit query never has to walk the
 * whole level to find them.
 *
 * <p>SBW's own {@code LevelMixin} adds OBB-only hulls (those whose hitbox is not their AABB)
 * to {@code Level.getEntities(Entity, AABB, Predicate)} whenever the querying entity is a
 * projectile, and it does that by streaming {@code getEntities().getAll()} — every entity in
 * the level — through the caller's predicate before it even asks whether the entity is an
 * OBB hull. Every bullet, shell and missile pays that each tick, so the cost is projectiles
 * times entities: ~13.6% of the server thread in a 636-unit battle. {@code
 * MixinLevelObbHitIndex} answers those queries instead, and {@link #appendObbHits} adds exactly
 * the entities SBW would have added, tested cheapest first.
 *
 * <p>Membership follows {@code EntityJoinLevelEvent}/{@code EntityLeaveLevelEvent} on both
 * sides; joining at LOWEST priority means a join another mod cancels never gets indexed.
 * Entries are re-checked for {@code isRemoved()} at read time anyway, so a missed leave can
 * cost a stale entry but never a wrong hit.
 */
public final class ObbHitIndex {

    private static final Entity[] NONE = new Entity[0];

    private ObbHitIndex() {}

    /**
     * Adds every OBB-only entity whose OBB touches {@code box} and that {@code predicate}
     * accepts, skipping {@code except} and anything already in {@code out}. Same verdict as
     * SBW's handler.
     *
     * <p>Must never query the level with a projectile as the except entity: that is the call
     * {@code MixinLevelObbHitIndex} intercepts, and it would recurse.
     */
    public static void appendObbHits(
            Level level, List<Entity> out, Entity except, AABB box, Predicate<? super Entity> predicate) {
        for (Entity e : members(level)) {
            if (e == except || e.isRemoved() || !(e instanceof OBBEntity obbEntity)) continue;
            if (obbEntity.enableAABB() || !touches(obbEntity.getOBBs(), box)) continue;
            if (!predicate.test(e) || containsIdentity(out, e)) continue;
            out.add(e);
        }
    }

    /**
     * Broad phase is a bounding-sphere test on the OBB's centre and extents, not
     * {@code OBB.getWorldAABB}: that rebuilds the box from the rotation quaternion on every call,
     * and this runs per OBB per hull per projectile query (it cost 1.1% of the server thread on
     * its own). Both radii are L1 norms, which bound the true half-diagonals from above, so the
     * test can only let extra pairs through to the exact check, never drop one.
     */
    private static boolean touches(List<OBB> obbs, AABB box) {
        double cx = (box.minX + box.maxX) * 0.5;
        double cy = (box.minY + box.maxY) * 0.5;
        double cz = (box.minZ + box.maxZ) * 0.5;
        double boxRadius = (box.maxX - box.minX + box.maxY - box.minY + box.maxZ - box.minZ) * 0.5;
        for (int i = 0, n = obbs.size(); i < n; i++) {
            OBB obb = obbs.get(i);
            Vector3d c = obb.center;
            Vector3d ext = obb.extents();
            double reach = boxRadius + Math.abs(ext.x) + Math.abs(ext.y) + Math.abs(ext.z);
            double dx = c.x - cx;
            double dy = c.y - cy;
            double dz = c.z - cz;
            if (dx * dx + dy * dy + dz * dz > reach * reach) continue;
            if (OBB.isColliding(obb, box)) return true;
        }
        return false;
    }

    private static boolean containsIdentity(List<Entity> list, Entity e) {
        for (int i = 0, n = list.size(); i < n; i++) {
            if (list.get(i) == e) return true;
        }
        return false;
    }

    private static Entity[] members(Level level) {
        Entity[] members = ((IObbHitIndex) level).tacz_sewv$obbEntities();
        return members == null ? NONE : members;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onJoin(EntityJoinLevelEvent event) {
        Entity e = event.getEntity();
        if (!(e instanceof OBBEntity)) return;
        IObbHitIndex index = (IObbHitIndex) event.getLevel();
        Entity[] old = members(event.getLevel());
        for (Entity m : old) if (m == e) return;

        int live = 0;
        for (Entity m : old) if (!m.isRemoved()) live++;
        Entity[] next = new Entity[live + 1];
        int i = 0;
        for (Entity m : old) if (!m.isRemoved()) next[i++] = m;
        next[i] = e;
        index.tacz_sewv$setObbEntities(next);
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        Entity e = event.getEntity();
        if (!(e instanceof OBBEntity)) return;
        IObbHitIndex index = (IObbHitIndex) event.getLevel();
        Entity[] old = members(event.getLevel());
        int keep = 0;
        for (Entity m : old) if (m != e && !m.isRemoved()) keep++;
        if (keep == old.length) return;
        Entity[] next = new Entity[keep];
        int i = 0;
        for (Entity m : old) if (m != e && !m.isRemoved()) next[i++] = m;
        index.tacz_sewv$setObbEntities(next);
    }
}
