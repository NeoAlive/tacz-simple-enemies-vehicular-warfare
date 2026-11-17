package com.neoalive.tacz_sewv.entity.ai.support;

import java.util.Map;
import java.util.WeakHashMap;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.ai.tactical.TacticalCoverFinder;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.util.WarnOnce;

/**
 * The infantry half of Cover and Hold: SEM's own cover definition, asked through its public finder.
 *
 * <p>The finder wants a living threat. Before contact there is none, so it gets a proxy: an armor
 * stand that is never added to the level, standing at the order's threat point. Checked against the
 * 0.1.6 jar, the finder reads only {@code getEyePosition}, {@code position}, {@code blockPosition} and
 * {@code isAlive} off it. A live target, when the unit has one, is used instead.
 */
public final class CoverHoldSearch {

    /** One proxy per level, never spawned. Server thread only. */
    private static final Map<Level, ArmorStand> PROXIES = new WeakHashMap<>();

    /** A search answer: where to go, and whether SEM called it cover (false = holding in the open). */
    public record Result(BlockPos spot, boolean cover) {}

    private CoverHoldSearch() {}

    /**
     * Runs one SEM cover search for {@code unit} from {@code origin}, or answers null when SEM's per-tick
     * search budget is spent (the caller asks again next tick). Never answers SEM's "evasion" fallback:
     * running from the threat is not what the order asked for, so no cover means hold at the origin.
     */
    @Nullable
    public static Result search(AbstractUnit unit, BlockPos origin, Vec3 threatPoint) {
        if (!TacticalCoverFinder.tryAcquireCoverSearchSlot(unit)) return null;
        LivingEntity live = unit.getTarget();
        LivingEntity threat = live != null && live.isAlive() ? live : proxy(unit.level(), threatPoint);
        BlockPos before = unit.getCoverSearchOrigin();
        try {
            unit.setCoverSearchOrigin(origin);
            TacticalCoverFinder.CoverSearchResult r =
                    TacticalCoverFinder.findCoverOrEvasion(unit, threat, CoverHoldOrders.FOOT_SEARCH_RADIUS);
            if (r != null && r.hasPosition() && r.isCover()) return new Result(r.position().immutable(), true);
        } catch (Throwable t) {
            // A future SEM finder reading something the proxy lacks: hold in the open, say so once.
            WarnOnce.warn(TaczSewv.LOGGER, "cover_hold_search",
                    "Cover and Hold: SEM cover search failed, units will hold in the open", t);
        } finally {
            unit.setCoverSearchOrigin(before);
        }
        return new Result(origin, false);
    }

    private static LivingEntity proxy(Level level, Vec3 at) {
        ArmorStand stand = PROXIES.computeIfAbsent(level, l -> new ArmorStand(EntityType.ARMOR_STAND, l));
        // The threat point is eye height; the stand's eye sits above its feet.
        stand.setPos(at.x, at.y - stand.getEyeHeight(), at.z);
        return stand;
    }
}
