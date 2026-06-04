package ac.grim.grimac.utils.collisions;

import ac.grim.grimac.player.GrimPlayer;

/**
 * Strider lava collision helpers — kept separate from {@code MovementTickerStrider}
 * so block-update paths do not load the full movement-ticker class graph.
 */
public final class StriderCollisionUtil {

    private StriderCollisionUtil() {
    }

    /** Client strider head above the lava surface block center. */
    public static boolean isHeadAboveMountHeight(GrimPlayer player) {
        return player.y > Math.floor(player.y) + 0.5 - 1.0E-5F;
    }
}
