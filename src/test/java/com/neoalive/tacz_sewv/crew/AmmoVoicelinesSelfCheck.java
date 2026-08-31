package com.neoalive.tacz_sewv.crew;

/**
 * Headless checks for {@link AmmoVoicelines} ammo-id classification.
 */
public final class AmmoVoicelinesSelfCheck {

    public static void main(String[] args) {
        boolean assertionsOn = false;
        assert assertionsOn = true;
        if (!assertionsOn) throw new IllegalStateException("run with -ea, or this checks nothing");

        assert AmmoVoicelines.classifyCategory("superbwarfare:large_shell_ap")
                == AmmoVoicelines.Category.SABOT;
        assert AmmoVoicelines.classifyCategory("superbwarfare:tank_heatfs")
                == AmmoVoicelines.Category.HEAT;
        assert AmmoVoicelines.classifyCategory("superbwarfare:large_shell_he")
                == AmmoVoicelines.Category.GENERAL;

        System.out.println("ammo voicelines self-check: OK");
    }

    private AmmoVoicelinesSelfCheck() {}
}
