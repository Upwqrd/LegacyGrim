package ac.grim.grimac.checks.impl.movement;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PostPredictionCheck;
import ac.grim.grimac.modifications.Fly2b2tModifications;
import ac.grim.grimac.modifications.Spider2b2tModifications;
import ac.grim.grimac.modifications.Strafe2b2tModifications;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;

@CheckData(
        name = "SpiderLimits2b2t",
        stableKey = "grim.movement.spider_limits_2b2t",
        description = "Blocks Meteor Spider wall climb (sustained dy against horizontalCollision)",
        decay = 0.15,
        setback = 3
)
public class SpiderLimits2b2t extends Check implements PostPredictionCheck {

    public SpiderLimits2b2t(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPredictionComplete(PredictionComplete predictionComplete) {
        if (!predictionComplete.isChecked()) {
            return;
        }

        if (!Spider2b2tModifications.enabled
                || !Strafe2b2tModifications.shouldEvaluateOnFoot(player, true, false)) {
            reward();
            return;
        }

        double horiz = Math.hypot(player.actualMovement.getX(), player.actualMovement.getZ());
        double deltaY = player.actualMovement.getY();

        String verbose = Spider2b2tModifications.getSpiderBlockReasonThisPacket(player);
        if (verbose == null) {
            reward();
            return;
        }

        if (flagAndAlert(verbose) && !isNoSetbackPermission()) {
            Fly2b2tModifications.rollbackFlight(player, player.lastX, player.lastY, player.lastZ);
        }
    }
}
