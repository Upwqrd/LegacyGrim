package ac.grim.grimac.modifications;

import ac.grim.grimac.player.GrimPlayer;

/**
 * 2b2t.org.ru fork: step allowed up to 1.0 block without jump; only &gt; 1.0 is blocked (see MovementLimits2b2t).
 */
public final class Step2b2tModifications {

    public static double getMaxLegitStepDeltaY() {
        return MovementLimits2b2tModifications.maxStepWithoutJump;
    }

    public enum StepVerdict {
        ALLOW,
        STEP_TOO_HIGH
    }

    private Step2b2tModifications() {
    }

    public static StepVerdict evaluateVerticalStep(GrimPlayer player, double deltaY, double horizPerTick, boolean packetOnGround) {
        if (deltaY <= 0 || hasJumpPacket(player)) {
            return StepVerdict.ALLOW;
        }
        if (deltaY > getMaxLegitStepDeltaY() + 0.02D) {
            return StepVerdict.STEP_TOO_HIGH;
        }
        return StepVerdict.ALLOW;
    }

    public static StepVerdict evaluateVerticalStep(GrimPlayer player, double deltaY, double horizPerTick) {
        return evaluateVerticalStep(player, deltaY, horizPerTick, player.onGround);
    }

    public static boolean isLegitStepTick(GrimPlayer player, double deltaY, double horizPerTick) {
        return deltaY > 0
                && deltaY <= getMaxLegitStepDeltaY() + 0.02D
                && evaluateVerticalStep(player, deltaY, horizPerTick, player.onGround) == StepVerdict.ALLOW;
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
