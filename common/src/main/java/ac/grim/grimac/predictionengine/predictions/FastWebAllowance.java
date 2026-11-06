package ac.grim.grimac.predictionengine.predictions;

import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.IndexedVector3d;
import ac.grim.grimac.utils.data.VectorData;
import ac.grim.grimac.utils.math.Vector3dm;
import ac.grim.grimac.utils.nmsutil.Collisions;
import ac.grim.grimac.utils.nmsutil.StuckSpeed;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;

import java.util.List;

public final class FastWebAllowance {
    private static boolean touchingWeb(GrimPlayer p, SimpleCollisionBox body) {
        return Collisions.hasMaterial(p, body, (block, x, y, z) -> block.getType() == StateTypes.COBWEB
                && new SimpleCollisionBox(x, y, z).isIntersected(body));
    }

    public static void addCandidates(GrimPlayer p, List<VectorData> candidates, boolean normal) {
        if (!normal || !eligible(p) || !Double.isFinite(p.actualMovement.lengthSquared())) return;
        boolean touching = touchingWeb(p, p.boundingBox), retained = cobweb(p.stuckSpeedMultiplier);
        if (!touching && !retained) return;
        for (var source : List.copyOf(candidates)) {
            var stuck = source.stuckSpeedMultiplier;
            if (source.vectorType == VectorData.VectorType.EntitySpeed || source.isZeroPointZeroThree()
                    || source.isTrident() || source.isSwimHop() || !Double.isFinite(source.vector.lengthSquared())) continue;
            if (source.vectorType == VectorData.VectorType.Strafe) candidates.remove(source);
            if (retained && stuck == StuckSpeed.NONE) {
                var slowed = source.returnNewModified(source.vector.clone().multiply(StuckSpeed.COBWEB), source.vectorType);
                slowed.stuckSpeedMultiplier = StuckSpeed.COBWEB;
                candidates.remove(source);
                if (source.vectorType != VectorData.VectorType.Strafe) candidates.add(slowed);
            }
            if (!cobweb(stuck) && (stuck != StuckSpeed.NONE || retained)) continue;
            var raw = source.vector.clone().multiply(1 / stuck.getX(), 1 / stuck.getY(), 1 / stuck.getZ());
            if (cobweb(stuck)) {
                double squared = raw.getX() * raw.getX() + raw.getZ() * raw.getZ();
                double factor = squared == 0 ? 2 : clamp((p.actualMovement.getX() * raw.getX()
                        + p.actualMovement.getZ() * raw.getZ()) / squared, 0.1, 2);
                double y = clamp(p.actualMovement.getY(), raw.getY() * 0.1, raw.getY() * 2);
                for (double horizontal : new double[]{factor, 2})
                    candidates.add(source.returnNewModified(new Vector3dm(raw.getX() * horizontal, y,
                            raw.getZ() * horizontal), VectorData.VectorType.FastWeb));
            }
            if (touching && !p.lastOnGround && !source.isJump())
                candidates.add(source.returnNewModified(new Vector3dm(source.vector.getX(),
                        (raw.getY() - 10) * stuck.getY(), source.vector.getZ()), VectorData.VectorType.FastWebNormal));
        }
    }

    public static void enforce(GrimPlayer p) {
        if (eligible(p) && !p.disableGrim && !p.noSetbackPermission && touchingWeb(p, p.boundingBox)
                && p.predictedVelocity.vector.distanceSquared(p.actualMovement) > 1e-6)
            p.getSetbackTeleportUtil().executeViolationSetback();
    }

    private static boolean eligible(GrimPlayer p) {
        return !p.inVehicle() && !p.isFlying && !p.wasFlying && !p.isGliding && !p.wasGliding
                && !p.isClimbing && !p.isSwimming && !p.wasTouchingWater && !p.wasTouchingLava
                && !p.packetStateData.tryingToRiptide && !p.packetStateData.lastPacketWasTeleport;
    }
    private static boolean cobweb(IndexedVector3d stuck) {
        return stuck == StuckSpeed.COBWEB || stuck == StuckSpeed.COBWEB_WEAVING;
    }
    private static double clamp(double value, double a, double b) {
        return Math.max(Math.min(a, b), Math.min(Math.max(a, b), value));
    }
}
