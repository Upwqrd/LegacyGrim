package ac.grim.grimac.predictionengine.predictions;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.CollisionData;
import ac.grim.grimac.utils.data.VectorData;
import ac.grim.grimac.utils.math.GrimMath;
import ac.grim.grimac.utils.math.Vector3dm;
import ac.grim.grimac.utils.nmsutil.StuckSpeed;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.util.Vector3i;
import java.util.List;
public final class ScaffoldTowerAllowance {
    private static final double JUMP = 0.41999998688698;
    private int supportY = Integer.MIN_VALUE, resets;
    private boolean matches(GrimPlayer p, double x, double y, double z, double nextX, double nextY, double nextZ) {
        if (!Double.isFinite(x + y + z + nextX + nextY + nextZ) || p.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2) && !p.packetStateData.knownInput.jump()
                || p.packetStateData.lastPacketWasTeleport || p.packetStateData.lastPacketWasOnePointSeventeenDuplicate
                || p.inVehicle() || p.isFlying || p.wasFlying || p.isGliding || p.wasGliding
                || p.isClimbing || p.isSwimming || p.wasTouchingWater || p.wasTouchingLava
                || p.packetStateData.tryingToRiptide || p.stuckSpeedMultiplier != StuckSpeed.NONE
                || Math.abs(nextX - x) > 1e-7 || Math.abs(nextZ - z) > 1e-7) return false;
        double dy = nextY - y, velocity = JUMP;
        boolean profile = false;
        for (int tick = 0; tick < 3; tick++, velocity = (velocity - 0.08) * (double) 0.98F)
            profile |= Math.abs(dy - velocity) < 1e-7;
        if (!profile) return false;
        var pos = new Vector3i(GrimMath.floor(x), GrimMath.floor(y) - 1, GrimMath.floor(z));
        boolean confirmed = full(p, pos, true);
        return (confirmed || full(p, pos, false) && full(p, pos.add(0, -1, 0), true))
                && (pos.getY() > supportY || Math.abs(y - pos.getY() - 1) < 1e-7 && confirmed || resets < 3);
    }
    private boolean full(GrimPlayer p, Vector3i pos, boolean confirmed) {
        var block = confirmed ? p.compensatedWorld.getServerBlock(pos) : p.compensatedWorld.getBlock(pos);
        return CollisionData.getData(block.getType()).getMovementCollisionBox(p, p.getClientVersion(), block).isFullBlock();
    }
    public void addCandidates(GrimPlayer p, List<VectorData> candidates) {
        if (!matches(p, p.lastX, p.lastY, p.lastZ, p.x, p.y, p.z)) return;
        int support = GrimMath.floor(p.lastY) - 1;
        if (support > supportY || Math.abs(p.lastY - support - 1) < 1e-7
                && full(p, new Vector3i(GrimMath.floor(p.lastX), support, GrimMath.floor(p.lastZ)), true)) {
            supportY = support;
            resets = 0;
        }
        if (Math.abs(p.actualMovement.getY() - JUMP) < 1e-7) resets++;
        candidates.add(new VectorData(new Vector3dm(0, JUMP, 0), VectorData.VectorType.ScaffoldTower));
    }
}
