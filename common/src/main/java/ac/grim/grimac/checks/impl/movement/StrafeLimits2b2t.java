package ac.grim.grimac.checks.impl.movement;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PostPredictionCheck;
import ac.grim.grimac.modifications.MovementLimits2b2tModifications;
import ac.grim.grimac.modifications.Speed2b2tModifications;
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
    public void onPredictionComplete(PredictionComplete predictionComplete) {
        if (!predictionComplete.isChecked()) {
            return;
        }

        if (!MovementLimits2b2tModifications.enabled
                || !Strafe2b2tModifications.shouldEvaluateOnFoot(player, true, false)) {
            reward();
            return;
        }

        double horiz = MovementLimits2b2tModifications.resolveHorizontalSpeed(player);
        double deltaY = player.actualMovement.getY();

        MovementLimits2b2tModifications.PacketMoveVerdict verdict =
                MovementLimits2b2tModifications.evaluatePostPrediction(player, horiz, deltaY, player.onGround);

        if (verdict != MovementLimits2b2tModifications.PacketMoveVerdict.ALLOW) {
            if (verdict == MovementLimits2b2tModifications.PacketMoveVerdict.SPEED_EXCEEDED
                    && Speed2b2tModifications.enabled) {
                reward();
                return;
            }
            if (MovementLimits2b2tModifications.isLegitOnFootAirMovement(player, deltaY, player.onGround)
                    && verdict == MovementLimits2b2tModifications.PacketMoveVerdict.SURVIVAL_FLY) {
                reward();
                Strafe2b2tModifications.saveLastLegitOnFootPosition(
                        player,
                        new Vector3d(player.x, player.y, player.z),
                        player.yaw,
                        player.pitch
                );
                return;
            }
            if (!MovementLimits2b2tModifications.shouldPostPredictionSetback(
                    player, verdict, horiz, deltaY, player.onGround)) {
                reward();
                return;
            }
            double setbackVl = MovementLimits2b2tModifications.setbackViolationLevel;
            if (verdict == MovementLimits2b2tModifications.PacketMoveVerdict.SPEED_EXCEEDED
                    || MovementLimits2b2tModifications.requiresInstantSpeedSetback(
                            player, horiz, player.onGround, deltaY)
                    || verdict == MovementLimits2b2tModifications.PacketMoveVerdict.HIGH_JUMP) {
                violations = setbackVl;
            } else {
                violations += 1;
            }
            if (violations >= setbackVl) {
                String verbose = MovementLimits2b2tModifications.formatVerdict(verdict, horiz, deltaY)
                        + " vl=" + String.format("%.1f", violations);
                if (flagAndAlert(verbose) && !isNoSetbackPermission()) {
                    MovementLimits2b2tModifications.rollback(player, player.lastX, player.lastY, player.lastZ);
                }
                violations = setbackVl * 0.5;
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
