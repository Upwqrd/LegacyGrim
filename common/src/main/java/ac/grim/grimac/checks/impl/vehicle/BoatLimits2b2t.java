package ac.grim.grimac.checks.impl.vehicle;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PostPredictionCheck;
import ac.grim.grimac.modifications.Boat2b2tModifications;
import ac.grim.grimac.modifications.BoatMoveVerdict;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;

@CheckData(name = "BoatLimits2b2t", stableKey = "grim.vehicle.boat_limits_2b2t", description = "60 km/h boat cap on blocks and boat fly rollback")
public class BoatLimits2b2t extends Check implements PostPredictionCheck {

    public BoatLimits2b2t(GrimPlayer player) {
        super(player);
    }

    public void flagAndRollback(BoatMoveVerdict verdict, double horizSpeed, double deltaY, double fallbackX, double fallbackY, double fallbackZ) {
        if (flagAndAlert(Boat2b2tModifications.formatVerdict(verdict, horizSpeed, deltaY))) {
            if (!isNoSetbackPermission()) {
                Boat2b2tModifications.rollbackVehicle(player, fallbackX, fallbackY, fallbackZ);
            }
        }
    }

    @Override
    public void onPredictionComplete(PredictionComplete predictionComplete) {
        if (!predictionComplete.isChecked()) {
            return;
        }

        if (!Boat2b2tModifications.shouldFlagPostPrediction(player)) {
            reward();
            return;
        }

        double horiz = Math.hypot(player.actualMovement.getX(), player.actualMovement.getZ());
        BoatMoveVerdict verdict = horiz > Boat2b2tModifications.maxHorizontalBlocksPerTick
                ? BoatMoveVerdict.SPEED_EXCEEDED
                : BoatMoveVerdict.FLY_VIOLATION;

        flagAndRollback(verdict, horiz, player.actualMovement.getY(), player.lastX, player.lastY, player.lastZ);
    }
}
