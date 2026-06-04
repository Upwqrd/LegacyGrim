package ac.grim.grimac.modifications;

import ac.grim.grimac.checks.impl.movement.StepLimits2b2t;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.util.Vector3d;

/**
 * 2b2t.org.ru fork: step allowed up to 1.0 block without jump; block jump-during-step bypass (&gt;1 block).
 */
public final class Step2b2tModifications {

    private static final double STEP_EPSILON = 0.02D;
    /** Vanilla jump takeoff vertical — not a step. */
    private static final double JUMP_TAKEOFF_MAX_DELTA_Y = 0.42D;

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

        if (deltaY > getMaxLegitStepDeltaY() + STEP_EPSILON) {
            clearStepAscendAnchor(player);
            return StepVerdict.STEP_TOO_HIGH;
        }

        if (hasJumpPacket(player)) {
            clearStepAscendAnchor(player);
            if (isVanillaJumpTakeoffOnly(player, deltaY, packetOnGround)) {
                return StepVerdict.ALLOW;
            }
            return StepVerdict.STEP_JUMP_DURING_ASCENT;
        }

        double cumulativeAscend = trackStepAscend(player, fromY, toY);
        if (cumulativeAscend > getMaxLegitStepDeltaY() + STEP_EPSILON) {
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
     * True only for first-tick jump impulse (~0.42), not step-up with jump held.
     */
    private static boolean isVanillaJumpTakeoffOnly(GrimPlayer player, double deltaY, boolean packetOnGround) {
        if (deltaY > JUMP_TAKEOFF_MAX_DELTA_Y + STEP_EPSILON) {
            return false;
        }
        if (packetOnGround) {
            return true;
        }
        return player.packetStateData.wasOnGroundLastStrafeTick
                || player.packetStateData.consecutiveAirTicks <= 2;
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
