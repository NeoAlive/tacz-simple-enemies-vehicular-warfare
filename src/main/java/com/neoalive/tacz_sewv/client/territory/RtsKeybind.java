package com.neoalive.tacz_sewv.client.territory;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.compat.OpenPacCompat;

/**
 * The RTS panel's two keys. They only mean anything on the Xaero world map screen, which is where
 * {@code MixinGuiMap} reads them (the tick-poll in {@code TdtKeybind} cannot see a key while a screen is open).
 *
 * <p><b>Registered only when both Xaero World Map and OpenPAC are loaded</b> (spec section 2): with either
 * absent the feature does not exist, and that includes not offering two keys in the controls screen.
 *
 * <p>Defaults Y and U, unbound in vanilla and not claimed by SuperbWarfare (see {@code TdtKeybind}). They are
 * NOT verified against Xaero's own in-map keys; both are rebindable, and the GUI conflict context keeps them
 * from colliding with in-game keys.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class RtsKeybind {

    /**
     * The mappings live in a holder class so they are constructed only if it is ever touched. A {@code KeyMapping}
     * registers itself in vanilla's static key table on construction whether or not it is registered with Forge, so
     * building these when the feature is absent could steal a rebound key from another mod's binding.
     */
    public static final class Keys {
        private static final String CATEGORY = "key.categories." + TaczSewv.MODID;

        public static final KeyMapping TOGGLE_PANEL = new KeyMapping(
                "key." + TaczSewv.MODID + ".rts_panel",
                KeyConflictContext.GUI,
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_Y,
                CATEGORY);

        public static final KeyMapping FRONTLINE_TOOL = new KeyMapping(
                "key." + TaczSewv.MODID + ".rts_frontline",
                KeyConflictContext.GUI,
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_U,
                CATEGORY);

        private Keys() {}
    }

    private RtsKeybind() {}

    /** Both mods present at runtime: the whole feature's on/off switch on the client. */
    public static boolean available() {
        return ModList.get().isLoaded("xaeroworldmap") && OpenPacCompat.isLoaded();
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        if (!available()) return;
        event.register(Keys.TOGGLE_PANEL);
        event.register(Keys.FRONTLINE_TOOL);
    }
}
