package ac.grim.grimac.modifications;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.CollisionData;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.TrackerData;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import ac.grim.grimac.utils.enums.BoatEntityStatus;
import ac.grim.grimac.utils.math.GrimMath;
import ac.grim.grimac.utils.nmsutil.GetBoundingBox;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;

/**
 * 2b2t.org.ru fork: enforce 60 km/h boat cap on blocks, block boat fly, sync legit moves for vanilla.
 */
public final class Boat2b2tModifications {

    private static final String PREFIX = "Boat2b2t.";

    public static boolean enabled = true;
    /** ~60 km/h at 20 TPS (16.6 m/s) — hard cap, no lenience on violations. */
    public static double maxHorizontalBlocksPerTick = 0.83D;
    /** Vanilla-ish boat speed in air — above this without ground is fly. */
    public static double maxAirHorizontalBlocksPerTick = 0.25D;
    public static double highSpeedVehicleDesyncLenienceSq = 9.0D;
    public static double airScanDepth = 2.0D;

    private Boat2b2tModifications() {
    }

    public static void reload(ConfigManager config) {
        enabled = config.getBooleanElse(PREFIX + "enabled", true);
        maxHorizontalBlocksPerTick = config.getDoubleElse(PREFIX + "max-horizontal-blocks-per-tick", 0.83D);
        maxAirHorizontalBlocksPerTick = config.getDoubleElse(PREFIX + "max-air-horizontal-blocks-per-tick", 0.25D);
        highSpeedVehicleDesyncLenienceSq = config.getDoubleElse(PREFIX + "high-speed-desync-lenience-sq", 9.0D);
        airScanDepth = Math.max(0.5D, config.getDoubleElse(PREFIX + "air-scan-depth", 2.0D));
    }

    public static boolean isControlledBoat(GrimPlayer player) {
        return player.inVehicle()
                && player.compensatedEntities.self.getRiding() != null
                && player.compensatedEntities.self.getRiding().isBoat;
    }

    public static double horizontalBlocksPerTick(double dx, double dz) {
        return Math.hypot(dx, dz);
    }

    public static boolean isWithinAllowedBoatSpeed(double horizPerTick) {
        return horizPerTick <= maxHorizontalBlocksPerTick;
    }

    public static boolean hasSolidGroundBelow(GrimPlayer player) {
        return hasSolidGroundBelowAt(player, player.x, player.y, player.z);
    }

    public static boolean hasSolidGroundBelowAt(GrimPlayer player, double x, double y, double z) {
        SimpleCollisionBox playerBox = player.boundingBox;
        double width = playerBox.maxX - playerBox.minX;
        double height = playerBox.maxY - playerBox.minY;
        SimpleCollisionBox atPos = new SimpleCollisionBox(
                x - width / 2, y, z - width / 2,
                x + width / 2, y + height, z + width / 2,
                false
        );
        SimpleCollisionBox groundBox = new SimpleCollisionBox(
                atPos.minX, atPos.minY - 0.001D, atPos.minZ,
                atPos.maxX, atPos.minY, atPos.maxZ,
                false
        );

        int minX = (int) (Math.floor(groundBox.minX) - 1);
        int maxX = (int) (Math.ceil(groundBox.maxX) + 1);
        int minY = (int) (Math.floor(groundBox.minY) - 1);
        int maxY = (int) (Math.ceil(groundBox.maxY) + 1);
        int minZ = (int) (Math.floor(groundBox.minZ) - 1);
        int maxZ = (int) (Math.ceil(groundBox.maxZ) + 1);

        int blocks = 0;
        float friction = 0;

        for (int bx = minX; bx < maxX; ++bx) {
            for (int bz = minZ; bz < maxZ; ++bz) {
                int j2 = (bx != minX && bx != maxX - 1 ? 0 : 1) + (bz != minZ && bz != maxZ - 1 ? 0 : 1);
                if (j2 == 2) continue;
                for (int by = minY; by < maxY; ++by) {
                    if (j2 == 1 && (by == minY || by == maxY - 1)) continue;

                    WrappedBlockState blockData = player.compensatedWorld.getBlock(bx, by, bz);
                    if (blockData.getType() != StateTypes.LILY_PAD
                            && CollisionData.getData(blockData.getType())
                            .getMovementCollisionBox(player, player.getClientVersion(), blockData, bx, by, bz)
                            .isIntersected(groundBox)) {
                        friction += ac.grim.grimac.utils.nmsutil.BlockProperties.getMaterialFriction(player, blockData.getType());
                        blocks++;
                    }
                }
            }
        }

        return blocks > 0 && friction > 0;
    }

    public static BoatMoveVerdict evaluateVehicleMove(
            GrimPlayer player,
            Vector3d packetPosition,
            double lastX,
            double lastY,
            double lastZ,
            float packetYaw,
            float packetPitch
    ) {
        if (!isControlledBoat(player)) {
            player.vehicleData.highSpeedBoatOnBlocks = false;
            return BoatMoveVerdict.ALLOW;
        }

        if (player.vehicleData.lastVehiclePacketY == 0) {
            player.vehicleData.lastVehiclePacketY = lastY;
        }

        double dx = packetPosition.getX() - lastX;
        double dy = packetPosition.getY() - lastY;
        double dz = packetPosition.getZ() - lastZ;
        double horizPerTick = horizontalBlocksPerTick(dx, dz);

        boolean onBlocks = hasSolidGroundBelowAt(player, packetPosition.getX(), packetPosition.getY(), packetPosition.getZ());
        boolean airBelow = hasOnlyAirBelowAt(player, packetPosition.getX(), packetPosition.getY(), packetPosition.getZ());

        if (horizPerTick > maxHorizontalBlocksPerTick) {
            return BoatMoveVerdict.SPEED_EXCEEDED;
        }

        if (airBelow && (dy > 0.0D || horizPerTick > maxAirHorizontalBlocksPerTick)) {
            return BoatMoveVerdict.FLY_VIOLATION;
        }

        if (!onBlocks && horizPerTick > maxAirHorizontalBlocksPerTick) {
            return BoatMoveVerdict.FLY_VIOLATION;
        }

        if (!onBlocks && dy > 0.0D) {
            return BoatMoveVerdict.FLY_VIOLATION;
        }

        if (onBlocks && isWithinAllowedBoatSpeed(horizPerTick)) {
            player.vehicleData.highSpeedBoatOnBlocks = true;
            saveLastLegitPosition(player, packetPosition, packetYaw, packetPitch);
            syncBoatToClientPacket(player, packetPosition);
            player.vehicleData.lastVehiclePacketY = packetPosition.getY();
            return BoatMoveVerdict.ALLOW;
        }

        player.vehicleData.highSpeedBoatOnBlocks = false;
        player.vehicleData.lastVehiclePacketY = packetPosition.getY();
        return BoatMoveVerdict.FLY_VIOLATION;
    }

    public static void saveLastLegitPosition(GrimPlayer player, Vector3d position, float yaw, float pitch) {
        player.vehicleData.hasLastLegitBoatPosition = true;
        player.vehicleData.lastLegitX = position.getX();
        player.vehicleData.lastLegitY = position.getY();
        player.vehicleData.lastLegitZ = position.getZ();
        player.vehicleData.lastLegitYaw = yaw;
        player.vehicleData.lastLegitPitch = pitch;
    }

    public static void rollbackVehicle(GrimPlayer player, double fallbackX, double fallbackY, double fallbackZ) {
        player.vehicleData.highSpeedBoatOnBlocks = false;

        double x = fallbackX;
        double y = fallbackY;
        double z = fallbackZ;
        float yaw = player.yaw;
        float pitch = player.pitch;

        if (player.vehicleData.hasLastLegitBoatPosition) {
            x = player.vehicleData.lastLegitX;
            y = player.vehicleData.lastLegitY;
            z = player.vehicleData.lastLegitZ;
            yaw = player.vehicleData.lastLegitYaw;
            pitch = player.vehicleData.lastLegitPitch;
        }

        player.clientVelocity.setX(0);
        player.clientVelocity.setY(0);
        player.clientVelocity.setZ(0);

        player.lastX = x;
        player.lastY = y;
        player.lastZ = z;
        player.x = x;
        player.y = y;
        player.z = z;
        player.yaw = yaw;
        player.pitch = pitch;

        player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, x, y, z);
        syncBoatToClientPacket(player, new Vector3d(x, y, z));

        int vehicleId = player.getRidingVehicleId();
        if (vehicleId != Integer.MIN_VALUE) {
            player.user.writePacket(new WrapperPlayServerEntityTeleport(
                    vehicleId,
                    new Vector3d(x, y, z),
                    yaw,
                    pitch,
                    false
            ));
        }

        player.getSetbackTeleportUtil().executeNonSimulatingForceResync();
    }

    public static void syncBoatToClientPacket(GrimPlayer player, Vector3d position) {
        PacketEntity boat = player.compensatedEntities.self.getRiding();
        if (boat == null) {
            return;
        }

        double x = position.getX();
        double y = position.getY();
        double z = position.getZ();

        SimpleCollisionBox atClient = new SimpleCollisionBox(x, y, z, x, y, z, false);
        boat.setPositionRaw(player, atClient);

        int vehicleId = player.getRidingVehicleId();
        TrackerData tracked = player.compensatedEntities.serverPositionsMap.get(vehicleId);
        if (tracked != null) {
            tracked.setX(x);
            tracked.setY(y);
            tracked.setZ(z);
        }
    }

    public static boolean hasOnlyAirBelow(GrimPlayer player, double entityY) {
        return hasOnlyAirBelowAt(player, player.x, entityY, player.z);
    }

    public static boolean hasOnlyAirBelowAt(GrimPlayer player, double x, double entityY, double z) {
        SimpleCollisionBox playerBox = player.boundingBox;
        double width = playerBox.maxX - playerBox.minX;
        SimpleCollisionBox scan = new SimpleCollisionBox(
                x - width / 2,
                entityY - airScanDepth,
                z - width / 2,
                x + width / 2,
                entityY - 0.05D,
                z + width / 2,
                false
        );

        int minX = GrimMath.floor(scan.minX);
        int maxX = GrimMath.ceil(scan.maxX);
        int minY = GrimMath.floor(scan.minY);
        int maxY = GrimMath.ceil(scan.maxY);
        int minZ = GrimMath.floor(scan.minZ);
        int maxZ = GrimMath.ceil(scan.maxZ);

        for (int bx = minX; bx < maxX; bx++) {
            for (int by = minY; by < maxY; by++) {
                for (int bz = minZ; bz < maxZ; bz++) {
                    WrappedBlockState block = player.compensatedWorld.getBlock(bx, by, bz);
                    if (block.getType() == StateTypes.WATER || block.getType() == StateTypes.LAVA) {
                        return false;
                    }
                    if (block.getType() == StateTypes.BUBBLE_COLUMN) {
                        return false;
                    }
                    if (block.getType().isAir()) {
                        continue;
                    }
                    if (CollisionData.getData(block.getType())
                            .getMovementCollisionBox(player, player.getClientVersion(), block, bx, by, bz)
                            .isIntersected(scan)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    public static String formatVerdict(BoatMoveVerdict verdict, double horizSpeed, double deltaY) {
        return verdict.name().toLowerCase() + " h=" + String.format("%.3f", horizSpeed) + " dy=" + String.format("%.3f", deltaY);
    }

    public static double reduceBoatSpeedOffset(GrimPlayer player, double offset) {
        if (!isControlledBoat(player)) {
            return offset;
        }

        if (!player.vehicleData.highSpeedBoatOnBlocks) {
            return offset;
        }

        double horizActual = Math.hypot(player.actualMovement.getX(), player.actualMovement.getZ());
        if (!isWithinAllowedBoatSpeed(horizActual)) {
            return offset;
        }

        double horizPredicted = Math.hypot(
                player.predictedVelocity.vector.getX(),
                player.predictedVelocity.vector.getZ()
        );
        double horizDelta = Math.max(0, horizActual - horizPredicted);
        offset = Math.max(0, offset - horizDelta);
        offset = Math.max(0, offset - Math.sqrt(highSpeedVehicleDesyncLenienceSq));
        return offset;
    }

    public static boolean shouldFlagPostPrediction(GrimPlayer player) {
        if (!isControlledBoat(player)) {
            return false;
        }

        double horiz = Math.hypot(player.actualMovement.getX(), player.actualMovement.getZ());
        if (horiz > maxHorizontalBlocksPerTick) {
            return true;
        }

        if (player.vehicleData.status == BoatEntityStatus.IN_AIR
                && !hasSolidGroundBelow(player)
                && hasOnlyAirBelow(player, player.y)
                && (player.actualMovement.getY() > 0 || horiz > maxAirHorizontalBlocksPerTick)) {
            return true;
        }

        return false;
    }
}
