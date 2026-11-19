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
    private Vector3dm stoppingVelocity;
    private int stoppingTicks;

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

    public void reset() {
        startY = Double.NaN;
        stoppingVelocity = null;
        stoppingTicks = 0;
    }

    public void update(boolean gliding, double y) {
        if (!gliding) reset();
        else if (Double.isNaN(startY)) startY = y;
    }

    public void addCandidates(GrimPlayer p, List<VectorData> candidates) {
        if (!limits.enabled || !p.isGliding || p.inVehicle() || p.isFlying || p.wasTouchingWater || p.wasTouchingLava) {
            stoppingVelocity = null;
            stoppingTicks = 0;
            return;
        }
        if (!p.canGlide()) {
            addStoppingCandidates(p, candidates);
            return;
        }
        stoppingVelocity = null;
        stoppingTicks = 0;
        Vector3dm movement = bound(p.actualMovement, p.lastY);
        if (movement == null) return;
        for (var source : List.copyOf(candidates)) {
            if (source.isKnockback() || source.isFirstBreadKb() || source.isExplosion() || source.isFirstBreadExplosion()
                    || source.isTrident() || source.isZeroPointZeroThree() || source.isJump() || source.isSwimHop()
                    || source.stuckSpeedMultiplier != StuckSpeed.NONE) continue;
            candidates.add(source.returnNewModified(movement.clone(), VectorData.VectorType.ElytraControl));
        }
    }

    private void addStoppingCandidates(GrimPlayer p, List<VectorData> candidates) {
        if (p.lastOnGround || p.onGround) {
            stoppingVelocity = null;
            stoppingTicks = 0;
            return;
        }
        if (stoppingVelocity == null && p.isGliding && p.wasGliding && hasControl(p.predictedVelocity)
                && p.clientVelocity != null && Double.isFinite(p.clientVelocity.lengthSquared())) {
            stoppingVelocity = p.clientVelocity.clone();
            stoppingTicks = Math.min(20, 5 + Math.max(0, p.getTransactionPing()) / 50);
        }
        if (stoppingVelocity == null || stoppingTicks <= 0) return;
        stoppingTicks--;
        Vector3dm movement = bound(new Vector3dm(p.actualMovement.getX(), stoppingVelocity.getY(),
                p.actualMovement.getZ()), p.lastY);
        if (movement == null) return;
        double previousSpeed = Math.hypot(stoppingVelocity.getX(), stoppingVelocity.getZ());
        double requestedSpeed = Math.hypot(movement.getX(), movement.getZ());
        if (requestedSpeed > previousSpeed) {
            double scale = previousSpeed / requestedSpeed;
            movement.setX(movement.getX() * scale);
            movement.setZ(movement.getZ() * scale);
        }
        for (var source : List.copyOf(candidates)) {
            if (source.isKnockback() || source.isFirstBreadKb() || source.isExplosion() || source.isFirstBreadExplosion()
                    || source.isTrident() || source.isZeroPointZeroThree() || source.isJump() || source.isSwimHop()
                    || source.stuckSpeedMultiplier != StuckSpeed.NONE) continue;
            candidates.add(source.returnNewModified(movement.clone(), VectorData.VectorType.ElytraControlStop));
        }
    }

    private static boolean hasControl(VectorData prediction) {
        for (var data = prediction; data != null; data = data.lastVector) {
            if (data.vectorType == VectorData.VectorType.ElytraControlStop) return false;
            if (data.vectorType == VectorData.VectorType.ElytraControl) return true;
        }
        return false;
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
