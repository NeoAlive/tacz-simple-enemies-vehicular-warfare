package com.neoalive.tacz_sewv.entity.ai.command;

/**
 * Allocentric picture of one battle group — gathers, never decides (same ethos as {@code Facts}).
 *
 * <p>Filled by {@link InfluenceMap#derive}; public mutable fields on purpose. Stage 4 plays read
 * these; Stage 2 centrality reads {@link #friendlyCentroidX}/{@link #friendlyCentroidZ}.
 */
public final class BattleField {

    /** Max primary enemy pockets retained (strongest first). */
    public static final int MAX_POCKETS = 4;

    public double friendlyCentroidX;
    public double friendlyCentroidZ;
    public double enemyCentroidX;
    public double enemyCentroidZ;

    /** Enemy → us axis, unit length in XZ. Zeroed when either side is empty. */
    public double axisX;
    public double axisZ;

    /** Open flank relative to looking along the enemy→us axis. */
    public boolean openFlankLeft;
    public boolean openFlankRight;

    /** Rough force balance: friendlyCount / max(enemyCount, 1). */
    public double forceBalance;

    public int friendlyCount;
    public int enemyCount;

    public int pocketCount;
    public final double[] pocketX = new double[MAX_POCKETS];
    public final double[] pocketZ = new double[MAX_POCKETS];

    /** True after a successful derive this scan; false if cleared / never built. */
    public boolean populated;

    public void clear() {
        this.friendlyCentroidX = 0.0;
        this.friendlyCentroidZ = 0.0;
        this.enemyCentroidX = 0.0;
        this.enemyCentroidZ = 0.0;
        this.axisX = 0.0;
        this.axisZ = 0.0;
        this.openFlankLeft = false;
        this.openFlankRight = false;
        this.forceBalance = 1.0;
        this.friendlyCount = 0;
        this.enemyCount = 0;
        this.pocketCount = 0;
        this.populated = false;
    }
}
