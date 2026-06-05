package ac.grim.grimac.checks.impl.movement;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PostPredictionCheck;
import ac.grim.grimac.modifications.Step2b2tModifications;
import ac.grim.grimac.modifications.Strafe2b2tModifications;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;

@CheckData(name = "StepLimits2b2t", stableKey = "grim.movement.step_limits_2b2t", description = "Step up to 1 block with 28 km/h strafe; block phase step")
public class StepLimits2b2t extends Check implements PostPredictionCheck {

    public StepLimits2b2t(GrimPlayer player) {
        super(player);
    }

    public void flagStep(Step2b2tModifications.StepVerdict verdict, double horiz, double deltaY) {
        if (flagAndAlert(verdict.name().toLowerCase() + " h=" + String.format("%.3f", horiz) + " dy=" + String.format("%.3f", deltaY))) {
            if (!isNoSetbackPermission()) {
                Strafe2b2tModifications.rollbackOnFoot(player, player.lastX, player.lastY, player.lastZ);
            }
        }
    }

    @Override
    public void onPredictionComplete(PredictionComplete predictionComplete) {
        if (!predictionComplete.isChecked()) {
            return;
        }
        if (!Strafe2b2tModifications.shouldEvaluateOnFoot(player, true, false)) {
            reward();
            return;
        }

        double horiz = Math.hypot(player.actualMovement.getX(), player.actualMovement.getZ());
        double deltaY = player.actualMovement.getY();
        Step2b2tModifications.StepVerdict verdict =
                Step2b2tModifications.evaluateVerticalStep(player, deltaY, horiz, player.onGround);
        if (verdict == Step2b2tModifications.StepVerdict.ALLOW) {
            reward();
            return;
        }

        flagStep(verdict, horiz, deltaY);
    }
}
