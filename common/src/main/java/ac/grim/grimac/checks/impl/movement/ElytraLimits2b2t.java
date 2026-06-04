package ac.grim.grimac.checks.impl.movement;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PostPredictionCheck;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;

/** Disabled placeholder — vanilla elytra only (see Elytra2b2tModifications). */
@CheckData(name = "ElytraLimits2b2t", stableKey = "grim.movement.elytra_limits_2b2t",
        description = "Disabled — vanilla elytra handled by Grim prediction")
public class ElytraLimits2b2t extends Check implements PostPredictionCheck {

    public ElytraLimits2b2t(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPredictionComplete(PredictionComplete predictionComplete) {
        reward();
    }
}
