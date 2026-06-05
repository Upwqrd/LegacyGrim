package ac.grim.grimac.checks.impl.movement;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PostPredictionCheck;
import ac.grim.grimac.modifications.Fly2b2tModifications;
import ac.grim.grimac.modifications.MovementLimits2b2tModifications;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;

@CheckData(name = "FlyLimits2b2t", stableKey = "grim.movement.fly_limits_2b2t",
        description = "Blocks survival Flight hack; packet cancel + rollback")
public class FlyLimits2b2t extends Check implements PostPredictionCheck {

    public FlyLimits2b2t(GrimPlayer player) {
        super(player);
    }

        public void flagAndRollback(double horiz, double deltaY) {
        // Exempt FlightA check for this packet as we are handling the violation here
        // Retrieve FlightA check instance and set its exemption flag
        ac.grim.grimac.checks.impl.flight.FlightA flightCheck = (ac.grim.grimac.checks.impl.flight.FlightA) player.checkManager.getCheck(ac.grim.grimac.checks.impl.flight.FlightA.class);
        if (flightCheck != null) {
            flightCheck.setFlightExempt(true);
        }
        // Perform rollback similar to speedlimits2b2t behavior
        if (!isNoSetbackPermission()) {
            Fly2b2tModifications.rollbackFlight(player, player.lastX, player.lastY, player.lastZ);
        }
        // Reset exemption flag to avoid affecting subsequent packets
        if (flightCheck != null) {
            flightCheck.setFlightExempt(false);
        }
    }

    @Override
    public void onPredictionComplete(PredictionComplete predictionComplete) {
        if (!predictionComplete.isChecked()) {
            return;
        }

        if (!Fly2b2tModifications.enabled) {
            reward();
            return;
        }

        double horiz = MovementLimits2b2tModifications.resolvePacketHorizontalSpeed(player);
        double deltaY = player.actualMovement.getY();
        if (MovementLimits2b2tModifications.isInJumpSpeedGrace(player, deltaY, player.onGround)) {
            reward();
            return;
        }
        if (!Fly2b2tModifications.shouldBlockSurvivalFlight(player, player.onGround, deltaY, horiz)) {
            reward();
            return;
        }

        flagAndRollback(horiz, deltaY);
    }
}
