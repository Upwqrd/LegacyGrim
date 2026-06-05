package ac.grim.grimac.modifications;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.impl.movement.StepLimits2b2t;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.util.Vector3d;

/**
 * 2b2t.org.ru fork: step allowed up to 1.0 block without jump; block jump-during-step bypass (&gt;1 block).
 */
public final class Step2b2tModifications {

    private static final String PREFIX = "Step2b2t.";

    public static boolean enabled = true;
    private static double stepEpsilon = 0.02D;
    /** Vanilla jump takeoff vertical — not a step. */
    private static double jumpTakeoffMaxDeltaY = 0.42D;

    public static void reload(ConfigManager config) {
        enabled = config.getBooleanElse(PREFIX + "enabled", true);
        stepEpsilon = config.getDoubleElse(PREFIX + "step-epsilon", 0.02D);
        jumpTakeoffMaxDeltaY = config.getDoubleElse(PREFIX + "jump-takeoff-delta-y", 0.42D);
    }

    public static double getMaxLegitStepDeltaY() {
        return MovementLimits2b2tModifications.maxStepWithoutJump;
    }

    public enum StepVerdict {
        ALLOW,
        STEP_TOO_HIGH,
        STEP_JUMP_DURING_ASCENT
    }

    private Step2b2tModifications() {
    }

    public static StepVerdict evaluateVerticalStep(
            GrimPlayer player,
            double deltaY,
            double horizPerTick,
            boolean packetOnGround,
            double fromY,
            double toY
    ) {
        if (deltaY <= 0) {
            clearStepAscendAnchor(player);
            return StepVerdict.ALLOW;
        }

        if (deltaY > getMaxLegitStepDeltaY() + stepEpsilon) {
            clearStepAscendAnchor(player);
            return StepVerdict.STEP_TOO_HIGH;
        }

        if (hasJumpPacket(player)) {
            clearStepAscendAnchor(player);
            if (isLegitJumpAscent(player, deltaY, packetOnGround)) {
                return StepVerdict.ALLOW;
            }
            // Only flag jump-during-step when actively ascending a multi-block step sequence.
            if (player.packetStateData.hasStepAscendAnchorY
                    && deltaY > 0
                    && deltaY <= getMaxLegitStepDeltaY() + stepEpsilon) {
                return StepVerdict.STEP_JUMP_DURING_ASCENT;
            }
            return StepVerdict.ALLOW;
        }

        double cumulativeAscend = trackStepAscend(player, fromY, toY);
        if (cumulativeAscend > getMaxLegitStepDeltaY() + stepEpsilon) {
            clearStepAscendAnchor(player);
            return StepVerdict.STEP_TOO_HIGH;
        }

        return StepVerdict.ALLOW;
    }

    public static StepVerdict evaluateVerticalStep(GrimPlayer player, double deltaY, double horizPerTick, boolean packetOnGround) {
        return evaluateVerticalStep(player, deltaY, horizPerTick, packetOnGround, player.y, player.y + deltaY);
    }

    public static StepVerdict evaluateVerticalStep(GrimPlayer player, double deltaY, double horizPerTick) {
        return evaluateVerticalStep(player, deltaY, horizPerTick, player.onGround);
    }

    private static void clearStepAscendAnchor(GrimPlayer player) {
        player.packetStateData.hasStepAscendAnchorY = false;
    }

    private static double trackStepAscend(GrimPlayer player, double fromY, double toY) {
        if (!player.packetStateData.hasStepAscendAnchorY) {
            player.packetStateData.hasStepAscendAnchorY = true;
            player.packetStateData.stepAscendAnchorY = fromY;
        }
        return toY - player.packetStateData.stepAscendAnchorY;
    }

    /**
     * Vanilla sprint-jump arc — not step-up with jump held during a 2×1 step sequence.
     */
    private static boolean isLegitJumpAscent(GrimPlayer player, double deltaY, boolean packetOnGround) {
        if (MovementLimits2b2tModifications.isInJumpSpeedGrace(player, deltaY, packetOnGround)) {
            return true;
        }
        if (deltaY > jumpTakeoffMaxDeltaY + stepEpsilon) {
            return false;
        }
        if (packetOnGround) {
            return true;
        }
        return player.packetStateData.wasOnGroundLastStrafeTick
                || player.packetStateData.ticksSinceOnGround <= Strafe2b2tModifications.bunnyHopGroundGraceTicks;
    }

    public static boolean isLegitStepTick(GrimPlayer player, double deltaY, double horizPerTick) {
        return deltaY > 0
                && evaluateVerticalStep(player, deltaY, horizPerTick, player.onGround) == StepVerdict.ALLOW;
    }

    /**
     * Packet-time: block illegal step / jump-during-step before position apply.
     */
    public static boolean tryBlockStepPacket(
            GrimPlayer player,
            Vector3d packetPosition,
            boolean packetOnGround,
            PacketReceiveEvent event
    ) {
        if (!MovementLimits2b2tModifications.enabled
                || !Strafe2b2tModifications.shouldEvaluateOnFoot(player, true, false)) {
            return false;
        }

        double fromY = player.y;
        double deltaY = packetPosition.getY() - fromY;
        double horiz = Math.hypot(packetPosition.getX() - player.x, packetPosition.getZ() - player.z);
        if (MovementLimits2b2tModifications.isInJumpSpeedGrace(player, deltaY, packetOnGround)) {
            return false;
        }
        StepVerdict verdict = evaluateVerticalStep(
                player, deltaY, horiz, packetOnGround, fromY, packetPosition.getY());
        if (verdict == StepVerdict.ALLOW) {
            return false;
        }

        StepLimits2b2t check = player.checkManager.getPostPredictionCheck(StepLimits2b2t.class);
        if (check != null) {
            check.flagAndAlert(verdict.name().toLowerCase() + " dy=" + String.format("%.3f", deltaY)
                    + " h=" + String.format("%.3f", horiz));
        }
        event.setCancelled(true);
        player.onPacketCancel();
        return true;
    }

    public static double reduceStepOffset(GrimPlayer player, double offset) {
        if (!Strafe2b2tModifications.shouldEvaluateOnFoot(player, true, false)) {
            return offset;
        }
        double horiz = Math.hypot(player.actualMovement.getX(), player.actualMovement.getZ());
        double deltaY = player.actualMovement.getY();
        if (isLegitStepTick(player, deltaY, horiz)) {
            return 0;
        }
        return offset;
    }

    private static boolean hasJumpPacket(GrimPlayer player) {
        return MovementLimits2b2tModifications.hasJumpPacket(player);
    }
}
