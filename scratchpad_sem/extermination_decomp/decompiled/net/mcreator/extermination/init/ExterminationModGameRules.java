package net.mcreator.extermination.init;

import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameRules.BooleanValue;
import net.minecraft.world.level.GameRules.Category;
import net.minecraft.world.level.GameRules.Key;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;

@EventBusSubscriber(
   bus = Bus.MOD
)
public class ExterminationModGameRules {
   public static final Key<BooleanValue> STARTINVASIONS = GameRules.m_46189_("startInvasions", Category.MOBS, BooleanValue.m_46250_(false));
   public static final Key<BooleanValue> STARTHARVEST = GameRules.m_46189_("startHarvest", Category.MOBS, BooleanValue.m_46250_(false));
   public static final Key<BooleanValue> TRIPODGRIEFING = GameRules.m_46189_("tripodGriefing", Category.MOBS, BooleanValue.m_46250_(true));
   public static final Key<BooleanValue> ENABLE_BLOOD_HARVEST_GROW = GameRules.m_46189_("enableBloodHarvestGrow", Category.UPDATES, BooleanValue.m_46250_(true));
   public static final Key<BooleanValue> TRIPOD_DESPAWN = GameRules.m_46189_("tripodDespawn", Category.MOBS, BooleanValue.m_46250_(false));
   public static final Key<BooleanValue> START_DOMINATION = GameRules.m_46189_("startDomination", Category.MOBS, BooleanValue.m_46250_(false));
}
