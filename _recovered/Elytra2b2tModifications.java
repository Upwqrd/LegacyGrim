package ac.grim.grimac.modifications;

import ac.grim.grimac.player.GrimPlayer;

/**
 * 2b2t.org.ru fork: vanilla elytra only — fireworks + steep dive simulation lenience.
 * No Meteor ElytraFly caps or bypass (Grim prediction handles gliding).
 */
public final class Elytra2b2tModifications {

    private static final float STEEP_DIVE_PITCH = 32f;
    private static final double STEEP_DIVE_DELTA_Y = -0.1D;

    private Elytra2b2tModifications() {
    }

    public static boolean hasActiveFireworkBoost(GrimPlayer player) {
        if (!player.isGliding && !player.wasGliding) {
            return false;
        }
        return player.fireworks.getMaxFireworksAppliedPossible() > 0;
    }

    public static boolean isSteepVanillaDive(GrimPlayer player, double deltaY) {
        return player.pitch >= STEEP_DIVE_PITCH && deltaY <= STEEP_DIVE_DELTA_Y;
    }

    /** Disabled — vanilla elytra only. */
    public static boolean shouldEvaluate(GrimPlayer player, boolean hasPosition, boolean isTeleport) {
        return false;
    }

    public static boolean exceedsCheatElytraLimits(GrimPlayer player, double horiz, double deltaY) {
        return false;
    }

    public static boolean isVanillaElytraMovement(GrimPlayer player, double horiz, double deltaY) {
        return hasActiveFireworkBoost(player) || isSteepVanillaDive(player, deltaY);
    }

    public static boolean shouldBypassVanillaSimulation(GrimPlayer player, double horiz, double deltaY) {
        if (!player.isGliding) {
            return false;
        }
        return isVanillaElytraMovement(player, horiz, deltaY);
    }

    public static double reduceElytraOffset(GrimPlayer player, double offset) {
        if (!player.isGliding) {
            return offset;
        }
        double horiz = Math.hypot(player.actualMovement.getX(), player.actualMovement.getZ());
        double deltaY = player.actualMovement.getY();
        if (shouldBypassVanillaSimulation(player, horiz, deltaY)) {
            return 0;
        }
        return offset;
    }

    public static double horizontalBlocksPerTick(double dx, double dz) {
        return Math.hypot(dx, dz);
    }
}
