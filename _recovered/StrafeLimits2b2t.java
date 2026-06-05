package ac.grim.grimac.checks.impl.movement;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PostPredictionCheck;
import ac.grim.grimac.modifications.MovementLimits2b2tModifications;
import ac.grim.grimac.modifications.Strafe2b2tModifications;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import com.github.retrooper.packetevents.util.Vector3d;

@CheckData(
        name = "StrafeLimits2b2t",
        stableKey = "grim.movement.strafe_limits_2b2t",
        description = "PostPrediction movement limits (30 km/h, step, fly) with VL buffer",
        decay = 0.15,
        setback = 3
)
public class StrafeLimits2b2t extends Check implements PostPredictionCheck {

    public StrafeLimits2b2t(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(ac.grim.grimac.api.config.ConfigManager config) {
        MovementLimits2b2tModifications.setbackViolationLevel = config.getDoubleElse(
                "MovementLimits2b2t.setback-violation-level",
                MovementLimits2b2tModifications.setbackViolationLevel
        );
        setbackVL = config.getDoubleElse(
                "MovementLimits2b2t.setback-violation-level",
                MovementLimits2b2tModifications.setbackViolationLevel
        );
    }

    @Override
    public void onPredictionComplete(PredictionComplete predictionComplete) {
        if (!predictionComplete.isChecked()) {
            return;
        }

        if (!MovementLimits2b2tModifications.enabled
                || !Strafe2b2tModifications.shouldEvaluateOnFoot(player, true, false)) {
            reward();
            return;
        }

        double horiz = Math.hypot(player.actualMovement.getX(), player.actualMovement.getZ());
        double deltaY = player.actualMovement.getY();
        MovementLimits2b2tModifications.PacketMoveVerdict verdict =
                MovementLimits2b2tModifications.evaluatePostPrediction(player, horiz, deltaY, player.onGround);

        if (verdict != MovementLimits2b2tModifications.PacketMoveVerdict.ALLOW) {
            violations += 1;
            if (violations >= setbackVL) {
                String verbose = MovementLimits2b2tModifications.formatVerdict(verdict, horiz, deltaY)
                        + " vl=" + String.format("%.1f", violations);
                if (flagAndAlert(verbose) && !isNoSetbackPermission()) {
                    MovementLimits2b2tModifications.rollback(player, player.lastX, player.lastY, player.lastZ);
                }
                violations = setbackVL * 0.5;
            }
        } else {
            reward();
            Strafe2b2tModifications.saveLastLegitOnFootPosition(
                    player,
                    new Vector3d(player.x, player.y, player.z),
                    player.yaw,
                    player.pitch
            );
        }
    }
}
