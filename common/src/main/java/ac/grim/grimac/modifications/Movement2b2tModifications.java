package ac.grim.grimac.modifications;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.data.KnownInput;

/**
 * 2b2t.org.ru fork: Meteor strafe ground lenience and post-elytra / firework coast.
 */
public final class Movement2b2tModifications {

    private static final String PREFIX = "Movement2b2t.";

    public static int fireworkBoostGraceTicks = 55;
    public static int glideEndCoastTicks = 55;
    private static double steepFallDeltaY = -0.1D;
    private static double hardHorizontalFallLimit = 1.75D;

    private Movement2b2tModifications() {
    }

    public static void reload(ConfigManager config) {
        fireworkBoostGraceTicks = Math.max(1, config.getIntElse(PREFIX + "firework-boost-grace-ticks", 55));
        glideEndCoastTicks = Math.max(1, config.getIntElse(PREFIX + "glide-end-coast-ticks", 55));
        steepFallDeltaY = config.getDoubleElse(PREFIX + "steep-fall-delta-y", -0.1D);
        hardHorizontalFallLimit = config.getDoubleElse(PREFIX + "hard-horizontal-fall-limit", 1.75D);
    }

    public static boolean hasActiveFireworkBoost(GrimPlayer player) {
        return player.fireworks != null && player.fireworks.getMaxFireworksAppliedPossible() > 0;
    }

    public static void onElytraGlideEnded(GrimPlayer player) {
        player.packetStateData.elytraGlideEndCoastTicks = glideEndCoastTicks;
        if (hasActiveFireworkBoost(player)) {
            player.packetStateData.elytraFireworkBoostTicks = Math.max(
                    player.packetStateData.elytraFireworkBoostTicks,
                    fireworkBoostGraceTicks
            );
        }
    }

    public static void tickElytraGlideEndCoast(GrimPlayer player) {
        if (player.wasGliding && !player.isGliding) {
            onElytraGlideEnded(player);
        } else if (player.packetStateData.elytraGlideEndCoastTicks > 0 && !player.isGliding) {
            player.packetStateData.elytraGlideEndCoastTicks--;
        }
    }

    public static void tickFireworkBoostGrace(GrimPlayer player) {
        tickElytraGlideEndCoast(player);
        if (hasActiveFireworkBoost(player)) {
            player.packetStateData.elytraFireworkBoostTicks = fireworkBoostGraceTicks;
        } else if (player.packetStateData.elytraFireworkBoostTicks > 0) {
            player.packetStateData.elytraFireworkBoostTicks--;
        }
    }

    public static boolean hasGlideEndCoast(GrimPlayer player) {
        return player.packetStateData.elytraGlideEndCoastTicks > 0;
    }

    public static boolean hasFireworkBoostGrace(GrimPlayer player) {
        return player.packetStateData.elytraFireworkBoostTicks > 0;
    }

    /**
     * Simulation offset lenience only for real elytra/firework momentum — not entire coast window on foot.
     */
    public static boolean shouldBypassVanillaSimulation(GrimPlayer player, double deltaY, double horiz) {
        if (player.isGliding) {
            return false;
        }
        return Fly2b2tModifications.shouldExemptElytraFireworkMomentum(player, deltaY, horiz);
    }

    public static boolean shouldBypassVanillaSimulation(GrimPlayer player) {
        return shouldBypassVanillaSimulation(
                player,
                player.actualMovement.getY(),
                Math.hypot(player.actualMovement.getX(), player.actualMovement.getZ())
        );
    }

    public static boolean shouldExemptGroundSpoof(GrimPlayer player) {
        if (player.isGliding || hasActiveFireworkBoost(player)) {
            return true;
        }
        if (hasGlideEndCoast(player) && hasFireworkBoostGrace(player)) {
            return true;
        }
        KnownInput input = player.packetStateData.knownInput;
        if (input != null && input != KnownInput.DEFAULT && input.moving()) {
            return true;
        }
        if (player.packetStateData.consecutiveAirTicks > 0) {
            return true;
        }
        if (player.packetStateData.fallBufferTicks > 0) {
            return true;
        }
        return false;
    }

    public static boolean isHighSpeedFallContext(GrimPlayer player, double deltaY) {
        if (deltaY < steepFallDeltaY) {
            return true;
        }
        if (player.packetStateData.fallBufferTicks > 0) {
            return true;
        }
        if (hasActiveFireworkBoost(player)) {
            return true;
        }
        if (hasGlideEndCoast(player) && (deltaY < -0.03D || Math.hypot(player.actualMovement.getX(), player.actualMovement.getZ()) > 0.15D)) {
            return true;
        }
        return hasFireworkBoostGrace(player) && hasActiveFireworkBoost(player);
    }

    public static double getHardHorizontalLimit(GrimPlayer player, double deltaY) {
        if (isHighSpeedFallContext(player, deltaY)) {
            return hardHorizontalFallLimit;
        }
        return Strafe2b2tModifications.getStrictStrafeCap();
    }
}
