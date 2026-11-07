package ac.grim.grimac.predictionengine.predictions;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.data.VectorData;
import ac.grim.grimac.utils.math.Vector3dm;
import ac.grim.grimac.utils.nmsutil.StuckSpeed;

import java.util.List;

public final class ElytraControlAllowance {
    private record Limits(boolean enabled, double horizontal, double vertical, double rise) { }
    private volatile Limits limits = new Limits(true, 100 / 72.0, 10 / 72.0, 10);
    private double startY = Double.NaN;

    public void reload(ConfigManager config) {
        limits = new Limits(config.getBooleanElse("elytra-control.enabled", true),
                setting(config, "horizontal-speed-kmh", 100, 0) / 72,
                setting(config, "vertical-speed-kmh", 10, 0) / 72,
                setting(config, "max-rise-blocks", 10, -1));
    }

    private static double setting(ConfigManager config, String key, double fallback, double minimum) {
        double value = config.getDoubleElse("elytra-control." + key, fallback);
        return Double.isFinite(value) && (value >= 0 || value == minimum) ? value : fallback;
    }

    public void reset() { startY = Double.NaN; }

    public void update(boolean gliding, double y) {
        if (!gliding) reset();
        else if (Double.isNaN(startY)) startY = y;
    }

    public void addCandidates(GrimPlayer p, List<VectorData> candidates) {
        if (!p.isGliding || !p.canGlide() || p.inVehicle() || p.isFlying || p.wasTouchingWater || p.wasTouchingLava) return;
        Vector3dm movement = bound(p.actualMovement, p.lastY);
        if (movement == null) return;
        for (var source : List.copyOf(candidates)) {
            if (source.isKnockback() || source.isFirstBreadKb() || source.isExplosion() || source.isFirstBreadExplosion()
                    || source.isTrident() || source.isZeroPointZeroThree() || source.isJump() || source.isSwimHop()
                    || source.stuckSpeedMultiplier != StuckSpeed.NONE) continue;
            candidates.add(source.returnNewModified(movement.clone(), VectorData.VectorType.ElytraControl));
        }
    }

    public Vector3dm bound(Vector3dm requested, double y) {
        Limits cap = limits;
        if (!cap.enabled || !Double.isFinite(startY) || !Double.isFinite(y)
                || !Double.isFinite(requested.lengthSquared())) return null;
        double ceiling = cap.rise < 0 ? Double.POSITIVE_INFINITY : startY + cap.rise - y;
        if (ceiling < -1e-7) return null;
        double horizontal = Math.hypot(requested.getX(), requested.getZ());
        double scale = horizontal > cap.horizontal ? cap.horizontal / horizontal : 1;
        return new Vector3dm(requested.getX() * scale,
                Math.max(-cap.vertical, Math.min(Math.min(cap.vertical, Math.max(0, ceiling)), requested.getY())),
                requested.getZ() * scale);
    }
}
