package ac.grim.grimac.modifications;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.impl.movement.SpiderLimits2b2t;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.util.Vector3d;

/**
 * 2b2t.org.ru fork: Meteor Spider — sustained small {@code dy} while pressed against a wall (survival fly).
 */
public final class Spider2b2tModifications {

    private static final String PREFIX = "Spider2b2t.";

    public static boolean enabled = true;
    public static double wallClimbMinDeltaY = 0.003D;
    /** Below vanilla jump takeoff (~0.42) so sprint-jump against a wall is not spider. */
    public static double wallClimbMaxDeltaY = 0.35D;
    /** Meteor default climb-speed band. */
    public static double meteorSpiderMinDeltaY = 0.12D;
    public static double meteorSpiderMaxDeltaY = 0.25D;
    public static int minConsecutiveTicks = 3;
    public static double maxCumulativeClimbBlocks = 1.0D;
    public static double stepEpsilon = 0.02D;

    public enum SpiderVerdict {
        ALLOW,
        SPIDER_CLIMB
    }

    private Spider2b2tModifications() {
    }

    public static void reload(ConfigManager config) {
        enabled = config.getBooleanElse(PREFIX + "enabled", true);
        wallClimbMinDeltaY = config.getDoubleElse(PREFIX + "wall-climb-min-delta-y", 0.003D);
        wallClimbMaxDeltaY = config.getDoubleElse(PREFIX + "wall-climb-max-delta-y", 0.35D);
        meteorSpiderMinDeltaY = config.getDoubleElse(PREFIX + "meteor-spider-min-delta-y", 0.12D);
        meteorSpiderMaxDeltaY = config.getDoubleElse(PREFIX + "meteor-spider-max-delta-y", 0.25D);
        minConsecutiveTicks = Math.max(2, config.getIntElse(PREFIX + "min-consecutive-ticks", 3));
        maxCumulativeClimbBlocks = config.getDoubleElse(PREFIX + "max-cumulative-climb-blocks", 1.0D);
        stepEpsilon = config.getDoubleElse(PREFIX + "step-epsilon", 0.02D);
    }

    public static void clearWallClimbAnchor(GrimPlayer player) {
        player.packetStateData.hasWallClimbAnchorY = false;
        player.packetStateData.consecutiveWallClimbTicks = 0;
        player.packetStateData.spiderBlockReasonThisPacket = null;
    }

    /**
     * Once per movement packet — updates climb counters and caches block reason for packet/post checks.
     */
    public static void trackWallClimbForPacket(
            GrimPlayer player,
            double deltaY,
            boolean packetOnGround,
            double fromY,
            double toY,
            double horiz
    ) {
        if (player.movementPackets == player.packetStateData.wallClimbLastTrackedMovementPacket) {
            return;
        }
        player.packetStateData.wallClimbLastTrackedMovementPacket = player.movementPackets;
        player.packetStateData.spiderBlockReasonThisPacket = evaluateWallClimbBlock(
                player, deltaY, packetOnGround, fromY, toY, horiz
        );
    }

    public static String getSpiderBlockReasonThisPacket(GrimPlayer player) {
        return player.packetStateData.spiderBlockReasonThisPacket;
    }

    public static boolean isPressingAgainstWall(GrimPlayer player) {
        return player.horizontalCollision || player.softHorizontalCollision;
    }

    /**
     * Slow sustained Y gain while pressed against a wall — Meteor Spider, not ladder / jump / step.
     */
    public static boolean isSlowWallClimbPattern(GrimPlayer player, double deltaY) {
        if (!enabled) {
            return false;
        }
        if (player.isClimbing || player.wasTouchingWater || player.wasTouchingLava) {
            return false;
        }
        if (deltaY <= wallClimbMinDeltaY || deltaY > wallClimbMaxDeltaY) {
            return false;
        }
        return isPressingAgainstWall(player);
    }

    public static boolean shouldExempt(
            GrimPlayer player,
            double deltaY,
            boolean packetOnGround,
            double horiz
    ) {
        if (player.inVehicle() || player.isFlying || player.isGliding || player.wasGliding) {
            return true;
        }
        if (player.gamemode == GameMode.CREATIVE || player.gamemode == GameMode.SPECTATOR) {
            return true;
        }
        if (player.isClimbing || player.wasTouchingWater || player.wasTouchingLava) {
            return true;
        }
        if (player.riptideSpinAttackTicks > 0) {
            return true;
        }
        if (player.likelyKB != null || player.likelyExplosions != null || player.firstBreadKB != null) {
            return true;
        }
        if (player.packetStateData.pearlPhaseGraceTicks > 0 || player.packetStateData.lastPacketWasTeleport) {
            return true;
        }
        if (Fly2b2tModifications.shouldExemptElytraFireworkMomentum(player, deltaY, horiz)) {
            return true;
        }
        if (MovementLimits2b2tModifications.isInJumpSpeedGrace(player, deltaY, packetOnGround)) {
            return true;
        }
        if (MovementLimits2b2tModifications.isJumpTakeoffMovement(player, deltaY, packetOnGround)) {
            return true;
        }
        if (MovementLimits2b2tModifications.hasJumpPacket(player)) {
            return true;
        }
        return false;
    }

    private static boolean isAirborneWallClimb(GrimPlayer player, boolean packetOnGround) {
        return !packetOnGround || player.packetStateData.ticksSinceOnGround >= 1;
    }

    /**
     * @return verbose block reason, or null if allowed
     */
    public static String evaluateWallClimbBlock(
            GrimPlayer player,
            double deltaY,
            boolean packetOnGround,
            double fromY,
            double toY,
            double horiz
    ) {
        if (!enabled || !Strafe2b2tModifications.shouldEvaluateOnFoot(player, true, false)) {
            return null;
        }

        if (shouldExempt(player, deltaY, packetOnGround, horiz)) {
            clearWallClimbAnchor(player);
            return null;
        }

        if (packetOnGround && !MovementLimits2b2tModifications.isJumpTakeoffMovement(player, deltaY, true)) {
            clearWallClimbAnchor(player);
            return null;
        }

        if (deltaY <= 0) {
            if (!isPressingAgainstWall(player)) {
                clearWallClimbAnchor(player);
            }
            return null;
        }

        if (!isSlowWallClimbPattern(player, deltaY)) {
            if (!isPressingAgainstWall(player)) {
                clearWallClimbAnchor(player);
            }
            return null;
        }

        player.packetStateData.consecutiveWallClimbTicks++;
        if (!player.packetStateData.hasWallClimbAnchorY) {
            player.packetStateData.hasWallClimbAnchorY = true;
            player.packetStateData.wallClimbAnchorY = fromY;
        }

        if (!isAirborneWallClimb(player, packetOnGround)) {
            return null;
        }

        double cumulative = toY - player.packetStateData.wallClimbAnchorY;
        int ticks = player.packetStateData.consecutiveWallClimbTicks;

        if (ticks >= minConsecutiveTicks
                && deltaY >= meteorSpiderMinDeltaY
                && deltaY <= meteorSpiderMaxDeltaY) {
            String verbose = formatVerdict(deltaY, ticks, cumulative);
            clearWallClimbAnchor(player);
            return verbose;
        }

        if (cumulative > maxCumulativeClimbBlocks + stepEpsilon) {
            String verbose = formatVerdict(deltaY, ticks, cumulative);
            clearWallClimbAnchor(player);
            return verbose;
        }

        if (ticks >= minConsecutiveTicks + 2
                && cumulative > maxCumulativeClimbBlocks * 0.5D
                && MovementLimits2b2tModifications.hasAirBelow(player)) {
            String verbose = formatVerdict(deltaY, ticks, cumulative);
            clearWallClimbAnchor(player);
            return verbose;
        }

        return null;
    }

    public static SpiderVerdict evaluateWallClimb(
            GrimPlayer player,
            double deltaY,
            boolean packetOnGround,
            double fromY,
            double toY
    ) {
        double horiz = Math.hypot(player.actualMovement.getX(), player.actualMovement.getZ());
        return evaluateWallClimbBlock(player, deltaY, packetOnGround, fromY, toY, horiz) != null
                ? SpiderVerdict.SPIDER_CLIMB
                : SpiderVerdict.ALLOW;
    }

    public static boolean shouldBlockSpiderPost(GrimPlayer player, double deltaY, boolean onGround) {
        double horiz = Math.hypot(player.actualMovement.getX(), player.actualMovement.getZ());
        return evaluateWallClimbBlock(player, deltaY, onGround, player.lastY, player.y, horiz) != null;
    }

    public static String formatVerdict(double deltaY, int ticks, double cumulative) {
        return "spider_climb dy=" + String.format("%.3f", deltaY)
                + " ticks=" + ticks
                + " cum=" + String.format("%.3f", cumulative);
    }

    /**
     * Packet-time block before position apply (hard cancel + rollback).
     */
    public static boolean tryBlockSpiderPacket(
            GrimPlayer player,
            Vector3d packetPosition,
            boolean packetOnGround,
            PacketReceiveEvent event
    ) {
        if (!enabled || !Strafe2b2tModifications.shouldEvaluateOnFoot(player, true, false)) {
            return false;
        }

        String verbose = getSpiderBlockReasonThisPacket(player);
        if (verbose == null) {
            return false;
        }

        SpiderLimits2b2t check = player.checkManager.getPostPredictionCheck(SpiderLimits2b2t.class);
        if (check != null) {
            if (check.flagAndAlert(verbose) && !check.isNoSetbackPermission()) {
                Fly2b2tModifications.rollbackFlight(player, player.lastX, player.lastY, player.lastZ);
            }
        } else {
            Fly2b2tModifications.rollbackFlight(player, player.lastX, player.lastY, player.lastZ);
        }
        event.setCancelled(true);
        player.onPacketCancel();
        return true;
    }
}
