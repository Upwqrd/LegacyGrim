package ac.grim.grimac.predictionengine.predictions;

import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.data.VectorData;
import ac.grim.grimac.utils.math.Vector3dm;
import ac.grim.grimac.utils.nmsutil.BlockProperties;
import ac.grim.grimac.utils.nmsutil.Collisions;
import ac.grim.grimac.utils.nmsutil.JumpPower;
import com.github.retrooper.packetevents.protocol.potion.PotionTypes;
import com.github.retrooper.packetevents.util.Vector3d;

import java.util.List;

public final class StrafeAllowance {
    private boolean previousHop, enabled, hopping, onIce, landed;
    private double previousSpeed, limit, iceSpeed;

    public void reset() {
        previousHop = enabled = landed = false;
        previousSpeed = iceSpeed = 0;
    }

    public void addCandidates(GrimPlayer player, List<VectorData> candidates, boolean normalMovement) {
        enabled = normalMovement && !player.inVehicle() && !player.isFlying && !player.isGliding
                && !player.wasFlying && !player.wasGliding && !player.isClimbing
                && (!player.isSwimming || player.wasTouchingWater) && !player.isSneaking
                && player.hasGravity
                && !player.uncertaintyHandler.influencedByBouncyBlock()
                && !player.packetStateData.tryingToRiptide
                && !player.compensatedEntities.hasPotionEffect(PotionTypes.LEVITATION)
                && finite(player.actualMovement);
        if (!enabled) {
            reset();
            return;
        }
        double base = 0.2873;
        var speed = player.compensatedEntities.getPotionLevelForPlayer(PotionTypes.SPEED);
        var slowness = player.compensatedEntities.getPotionLevelForPlayer(PotionTypes.SLOWNESS);
        if (speed.isPresent()) base *= 1 + 0.2 * (speed.getAsInt() + 1);
        if (slowness.isPresent()) base *= Math.max(0, 1 - 0.15 * (slowness.getAsInt() + 1));
        onIce = player.lastOnGround && BlockProperties.getFriction(player, player.mainSupportingBlockData,
                new Vector3d(player.lastX, player.lastY, player.lastZ)) >= 0.98F;
        hopping = !player.wasTouchingWater && player.lastOnGround && !player.onGround && player.actualMovement.getY() > 0;
        if (hopping) iceSpeed = 0;
        else if (iceSpeed > 0) iceSpeed = previousHop ? airSpeed(base, iceSpeed, true) : iceSpeed * (158.0 / 159.0);
        if (hopping && (Math.abs(JumpPower.getJumpPower(player) - 0.42F) > 1e-6
                || !landed && Collisions.isEmpty(player, player.boundingBox.copy().offset(0, -0.03, 0)))) {
            reset();
            return;
        }
        limit = Math.max(iceSpeed, player.lastOnGround ? groundSpeed(base, hopping) : airSpeed(base, previousSpeed, previousHop));
        if (player.lastOnGround)
            limit = Math.max(limit, hopping ? Math.min(previousSpeed * 1.535, base * (0.615615 / 0.2873)) : airSpeed(base, previousSpeed, previousHop));
        int count = candidates.size();
        for (int i = 0; i < count; i++) {
            VectorData vanilla = candidates.get(i);
            if (vanilla.isTrident() || vanilla.isZeroPointZeroThree() || vanilla.isSwimHop()
                    || vanilla.isFlipSneaking() || !finite(vanilla.vector)
                    || vanilla.input == null
                    || vanilla.input.lengthSquared() == 0 && !player.supportsEndTickPreVia()) continue;
            if (onIce && !vanilla.isKnockback() && !vanilla.isExplosion())
                limit = Math.max(limit, Math.hypot(vanilla.vector.getX(), vanilla.vector.getZ()));
            Vector3dm movement = vanilla.vector.clone();
            VectorData source = vanilla;
            if (hopping && !vanilla.isKnockback() && !vanilla.isExplosion()) {
                var jump = player.compensatedEntities.getPotionLevelForPlayer(PotionTypes.JUMP_BOOST);
                double boost = jump.isPresent() ? (jump.getAsInt() + 1) * 0.1F : 0;
                double stuckY = vanilla.stuckSpeedMultiplier.getY();
                movement.setY(Math.max((0.4 + boost) * stuckY,
                        Math.min((0.42F + boost) * stuckY, player.actualMovement.getY())));
                source = vanilla.returnNewModified(movement, VectorData.VectorType.Jump);
                if (vanilla.isJump()) candidates.add(source);
            }
            candidates.add(source.returnNewModified(movement, VectorData.VectorType.Strafe));
        }
    }

    public Vector3dm movement(VectorData candidate, Vector3dm physics, Vector3dm requested) {
        double cap = limit * Math.max(0, Math.min(1,
                Math.min(candidate.stuckSpeedMultiplier.getX(), candidate.stuckSpeedMultiplier.getZ())));
        if (!candidate.isKnockback() && !candidate.isExplosion())
            cap = Math.max(cap, Math.hypot(physics.getX(), physics.getZ()));
        return boundedMovement(physics, requested, cap);
    }

    public void finish(GrimPlayer player) {
        if (!enabled) return;
        landed = player.onGround && player.verticalCollision && player.predictedVelocity.lastVector != null
                && player.predictedVelocity.lastVector.vector.getY() < 0;
        double predictedSpeed = Math.hypot(player.predictedVelocity.vector.getX(), player.predictedVelocity.vector.getZ());
        iceSpeed = Math.min(onIce ? limit : iceSpeed, predictedSpeed);
        previousSpeed = Math.min(limit, predictedSpeed);
        previousHop = hopping;
    }

    static double groundSpeed(double base, boolean hopping) {
        return Math.max(base, (1.18F * base - 0.01) * (hopping ? 1.63 : 1));
    }

    static double airSpeed(double base, double previous, boolean afterHop) {
        return Math.max(base, afterHop ? previous - 0.66 * (previous - base) : previous * (158.0 / 159.0));
    }

    static Vector3dm boundedMovement(Vector3dm physics, Vector3dm requested, double cap) {
        if (!finite(physics) || !finite(requested) || !Double.isFinite(cap) || cap < 0) return physics.clone();
        double length = Math.hypot(requested.getX(), requested.getZ());
        double scale = length > cap ? cap / length : 1;
        return new Vector3dm(requested.getX() * scale, physics.getY(), requested.getZ() * scale);
    }

    private static boolean finite(Vector3dm vector) {
        return Double.isFinite(vector.getX()) && Double.isFinite(vector.getY()) && Double.isFinite(vector.getZ());
    }
}
