package com.neoalive.tacz_sewv.entity.ai.command;

/**
 * Fixed play menu — ordinal matches {@link PlayId}.
 */
public final class Plays {

    private static final Play[] MENU = {
            FrontalFixAndFlank.INSTANCE,
            DoubleEnvelopment.INSTANCE,
            BoundingOverwatchAdvance.INSTANCE,
            FightingWithdrawal.INSTANCE,
            HoldDefend.INSTANCE
    };

    private Plays() {}

    public static Play[] menu() {
        return MENU;
    }

    public static Play of(PlayId id) {
        return MENU[id.ordinal()];
    }
}
