package com.neoalive.tacz_sewv.config;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

import com.neoalive.tacz_sewv.TaczSewv;

/**
 * Easy Mode unit health: hostile units are frailer, the player's own PMC sturdier. Transient
 * modifiers re-applied on every join (spawn and chunk load), so they are never saved and turning
 * Easy off — or removing the mod — leaves every unit on its own stats at the next load.
 */
@EventBusSubscriber(modid = TaczSewv.MODID)
public final class EasyModeUnitEvents {

    // NeoForge port: 1.21 modifiers are keyed by id, not UUID + name.
    private static final AttributeModifier HOSTILE = new AttributeModifier(
            ResourceLocation.fromNamespaceAndPath(TaczSewv.MODID, "easy_mode_frail"),
            EasyTuning.HOSTILE_HEALTH_MULT - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
    private static final AttributeModifier PMC = new AttributeModifier(
            ResourceLocation.fromNamespaceAndPath(TaczSewv.MODID, "easy_mode_sturdy"),
            EasyTuning.PMC_HEALTH_MULT - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);

    private EasyModeUnitEvents() {}

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (event.getEntity() instanceof AbstractUnit unit) refresh(unit);
    }

    /** Re-evaluate one unit; also called when a PMC is recruited (its owner changes its side). */
    public static void refresh(AbstractUnit unit) {
        AttributeInstance max = unit.getAttribute(Attributes.MAX_HEALTH);
        if (max == null) return;
        apply(unit, max, HOSTILE, EasyMode.softInfantry(unit));
        apply(unit, max, PMC, EasyMode.buffPmc() && EasyMode.isFriendlySide(unit));
    }

    private static void apply(AbstractUnit unit, AttributeInstance max, AttributeModifier mod, boolean want) {
        boolean has = max.getModifier(mod.id()) != null;
        if (want == has) return;
        float before = unit.getMaxHealth();
        boolean wasFull = unit.getHealth() >= before - 0.01f;
        if (want) max.addTransientModifier(mod);
        else max.removeModifier(mod.id());
        float after = unit.getMaxHealth();
        // A fresh or fully healed unit stays full; anyone else keeps their wounds, clamped.
        if (wasFull && after > before) unit.setHealth(after);
        else if (unit.getHealth() > after) unit.setHealth(after);
    }
}
