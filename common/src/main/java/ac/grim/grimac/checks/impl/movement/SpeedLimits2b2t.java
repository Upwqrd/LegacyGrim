package ac.grim.grimac.checks.impl.movement;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PostPredictionCheck;
import ac.grim.grimac.modifications.MovementLimits2b2tModifications;
import ac.grim.grimac.modifications.Speed2b2tModifications;
import ac.grim.grimac.modifications.Strafe2b2tModifications;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;

@CheckData(
        name = "SpeedLimits2b2t",
        stableKey = "grim.movement.speed_limits_2b2t",
        description = "Blocks horizontal speed above 30 km/h (packet + sustained, anti-lag)",
        decay = 0.15,
        setback = 3
)
public class SpeedLimits2b2t extends Check implements PostPredictionCheck {

    public SpeedLimits2b2t(GrimPlayer player) {
        super(player);
    }

    public void flagAndRollback(double horiz, boolean onGround) {
        if (flagAndAlert(Speed2b2tModifications.formatVerdict(horiz, onGround)) && !isNoSetbackPermission()) {
            Speed2b2tModifications.rollbackSpeed(player);
        }
    }

    @Override
    public void onPredictionComplete(PredictionComplete predictionComplete) {
        if (!predictionComplete.isChecked()) {
            return;
        }

        if (!Speed2b2tModifications.enabled
                || !Strafe2b2tModifications.shouldEvaluateOnFoot(player, true, false)) {
            reward();
            return;
        }

        if (Speed2b2tModifications.hasSpeedExempt(player)) {
            Speed2b2tModifications.clearJumpArcAnchor(player);
            reward();
            return;
        }

        double horiz = MovementLimits2b2tModifications.resolvePacketHorizontalSpeed(player);
        double deltaY = player.actualMovement.getY();

        if (Speed2b2tModifications.shouldBlockLongJumpPost(player, horiz, deltaY, player.onGround)) {
            String reason = Speed2b2tModifications.evaluateLongJumpBlock(
                    player,
                    player.lastX,
                    player.lastZ,
                    player.x,
                    player.z,
                    horiz,
                    deltaY,
                    player.onGround
            );
            if (reason != null && flagAndAlert(reason) && !isNoSetbackPermission()) {
                Speed2b2tModifications.rollbackSpeed(player);
            }
            return;
        }

        if (!Speed2b2tModifications.shouldBlockSustainedSpeed(player, player.onGround, deltaY, horiz)) {
            reward();
            return;
        }

        flagAndRollback(horiz, player.onGround);
    }
}
