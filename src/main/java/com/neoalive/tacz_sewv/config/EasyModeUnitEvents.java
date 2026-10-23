package com.neoalive.tacz_sewv.config;

import java.util.UUID;

import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.TaczSewv;

/**
 * Easy Mode unit health: hostile units are frailer, the player's own PMC sturdier. Transient
 * modifiers re-applied on every join (spawn and chunk load), so they are never saved and turning
 * Easy off — or removing the mod — leaves every unit on its own stats at the next load.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID)
public final class EasyModeUnitEvents {

    private static final UUID HOSTILE_UUID = UUID.fromString("9b2d61e4-3f0a-4c8e-b5d7-2a6e81c4f913");
    private static final UUID PMC_UUID = UUID.fromString("c47a0e95-1d6b-4f23-8e9a-6b5f2d3c7a10");
    private static final AttributeModifier HOSTILE = new AttributeModifier(HOSTILE_UUID,
            "tacz_sewv easy mode frail", EasyTuning.HOSTILE_HEALTH_MULT - 1.0,
            AttributeModifier.Operation.MULTIPLY_TOTAL);
    private static final AttributeModifier PMC = new AttributeModifier(PMC_UUID,
            "tacz_sewv easy mode sturdy", EasyTuning.PMC_HEALTH_MULT - 1.0,
            AttributeModifier.Operation.MULTIPLY_TOTAL);

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
        boolean has = max.getModifier(mod.getId()) != null;
        if (want == has) return;
        float before = unit.getMaxHealth();
        boolean wasFull = unit.getHealth() >= before - 0.01f;
        if (want) max.addTransientModifier(mod);
        else max.removeModifier(mod.getId());
        float after = unit.getMaxHealth();
        // A fresh or fully healed unit stays full; anyone else keeps their wounds, clamped.
        if (wasFull && after > before) unit.setHealth(after);
        else if (unit.getHealth() > after) unit.setHealth(after);
    }
}
