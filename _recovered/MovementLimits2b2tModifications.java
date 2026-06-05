package ac.grim.grimac.modifications;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.impl.movement.StrafeLimits2b2t;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.KnownInput;
import ac.grim.grimac.utils.nmsutil.Collisions;
import ac.grim.grimac.utils.nmsutil.GetBoundingBox;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.potion.PotionTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;

/**
 * 2b2t.org.ru fork: pre-cancel overspeed packets; PostPrediction enforces 30 km/h sustained.
 */
public final class MovementLimits2b2tModifications {

    private static final String PREFIX = "MovementLimits2b2t.";

    /** 30 km/h @ 20 TPS ≈ 0.4167 blocks/tick. */
    public static final double KMH_30_BLOCKS_PER_TICK = 30.0D / 3.6D / 20.0D;

    public static boolean enabled = true;
    /** Sustained ground / late-air cap (~30 km/h). */
    public static double maxHorizontalPerTick = KMH_30_BLOCKS_PER_TICK;
    public static double jumpFirstTicksHorizontalCap = 0.46D;
    public static int jumpFirstTicksHorizontalGrace = 5;
    public static double jumpAirHorizontalCap = 0.46D;
    public static int jumpAirHorizontalGraceTicks = 20;
    /** Pre-execution: cancel packet before position apply (blocks 60 km/h teleport). */
    public static double preCancelHorizontalPerTick = 0.45D;
    public static double maxStepWithoutJump = 1.0D;
    public static double blatantHighJumpDeltaY = 1.0D;
    public static int maxHoverAirTicks = 3;
    public static double hoverDeltaYEpsilon = 0.004D;
    public static double blatantFlyAscendDeltaY = 0.55D;
    public static int blatantFlyMinAirTicks = 18;
    public static double airProbeDepth = 1.5D;
    public static double setbackViolationLevel = 1.0D;
    /** Post-prediction instant rollback (well above legit sprint-jump). */
    public static double blatantHorizontalPerTick = 0.48D;
    private static final double GROUND_SPEED_SLACK = 0.012D;
    private static final double AIR_SPEED_SLACK = 0.012D;
    private static final double STEP_EPSILON = 0.02D;
    private static final int NATURAL_JUMP_ARC_TICKS = 45;

    public enum PacketMoveVerdict {
        ALLOW,
        SPEED_EXCEEDED,
        STEP_TOO_HIGH,
        HIGH_JUMP,
        SURVIVAL_FLY
    }

    private MovementLimits2b2tModifications() {
    }

    public static void reload(ConfigManager config) {
        enabled = config.getBooleanElse(PREFIX + "enabled", true);
        maxHorizontalPerTick = config.getDoubleElse(PREFIX + "max-horizontal-per-tick", KMH_30_BLOCKS_PER_TICK);
        jumpFirstTicksHorizontalCap = config.getDoubleElse(PREFIX + "jump-first-ticks-horizontal-cap", 0.46D);
        jumpFirstTicksHorizontalGrace = Math.max(1, config.getIntElse(PREFIX + "jump-first-ticks-grace", 5));
        jumpAirHorizontalCap = config.getDoubleElse(PREFIX + "jump-air-horizontal-cap", 0.46D);
        jumpAirHorizontalGraceTicks = Math.max(1, config.getIntElse(PREFIX + "jump-air-horizontal-grace-ticks", 20));
        preCancelHorizontalPerTick = config.getDoubleElse(PREFIX + "pre-cancel-horizontal-per-tick", 0.45D);
        maxStepWithoutJump = config.getDoubleElse(PREFIX + "max-step-without-jump", 1.0D);
        blatantHighJumpDeltaY = config.getDoubleElse(PREFIX + "blatant-high-jump-delta-y", 1.0D);
        maxHoverAirTicks = Math.max(1, config.getIntElse(PREFIX + "max-hover-air-ticks", 3));
        hoverDeltaYEpsilon = config.getDoubleElse(PREFIX + "hover-delta-y-epsilon", 0.004D);
        blatantFlyAscendDeltaY = config.getDoubleElse(PREFIX + "blatant-fly-ascend-delta-y", 0.55D);
        blatantFlyMinAirTicks = Math.max(1, config.getIntElse(PREFIX + "blatant-fly-min-air-ticks", 18));
        airProbeDepth = config.getDoubleElse(PREFIX + "air-probe-depth", 1.5D);
        setbackViolationLevel = config.getDoubleElse(PREFIX + "setback-violation-level", 1.0D);
        blatantHorizontalPerTick = config.getDoubleElse(PREFIX + "blatant-horizontal-per-tick", 0.48D);
    }

    public static double kmhToBlocksPerTick(double kmh) {
        return kmh / 3.6D / 20.0D;
    }

    public static void trackPacketState(
            GrimPlayer player,
            Vector3d packetPosition,
            boolean packetOnGround,
            float yaw,
            float pitch
    ) {
    }

    public static double resolveHorizontalSpeed(GrimPlayer player) {
        double predicted = Math.hypot(player.actualMovement.getX(), player.actualMovement.getZ());
        double packet = Math.hypot(player.x - player.lastX, player.z - player.lastZ);
        return Math.max(predicted, packet);
    }

    public static boolean isBlatantHorizontalSpeed(double horiz) {
        return horiz > blatantHorizontalPerTick;
    }

    /**
     * Hard ceiling on incoming packets — applied before {@code player.x/y/z} update.
     */
    public static double getPreCancelHorizontalLimit(GrimPlayer player, boolean packetOnGround) {
        if (packetOnGround) {
            return preCancelHorizontalPerTick;
        }
        int projectedAirTick = player.packetStateData.ticksSinceOnGround + 1;
        if (projectedAirTick <= jumpFirstTicksHorizontalGrace) {
            return jumpFirstTicksHorizontalCap;
        }
        if (projectedAirTick <= jumpAirHorizontalGraceTicks) {
            return jumpAirHorizontalCap;
        }
        return preCancelHorizontalPerTick;
    }

    public static boolean shouldPreCancelMovementPacket(
            GrimPlayer player,
            double fromX,
            double fromY,
            double fromZ,
            double toX,
            double toY,
            double toZ,
            boolean packetOnGround
    ) {
        if (!enabled || !Strafe2b2tModifications.shouldEvaluateOnFoot(player, true, false)) {
            return false;
        }
        if (hasMovementExempt(player)) {
            return false;
        }
        double horiz = Math.hypot(toX - fromX, toZ - fromZ);
        return horiz > getPreCancelHorizontalLimit(player, packetOnGround);
    }

    /**
     * Cancel packet in netty thread, do not move hitbox, zero horizontal server velocity on client.
     */
    public static boolean tryPreCancelOverspeed(
            GrimPlayer player,
            double fromX,
            double fromY,
            double fromZ,
            Vector3d packetPosition,
            boolean packetOnGround,
            PacketReceiveEvent event
    ) {
        if (!shouldPreCancelMovementPacket(player, fromX, fromY, fromZ,
                packetPosition.getX(), packetPosition.getY(), packetPosition.getZ(), packetOnGround)) {
            return false;
        }

        double horiz = Math.hypot(packetPosition.getX() - fromX, packetPosition.getZ() - fromZ);
        StrafeLimits2b2t limits = player.checkManager.getPostPredictionCheck(StrafeLimits2b2t.class);
        if (limits != null) {
            limits.flagAndAlert("pre_cancel h=" + String.format("%.3f", horiz));
        }

        hardStopHorizontalMovement(player);
        event.setCancelled(true);
        player.onPacketCancel();
        return true;
    }

    public static void hardStopHorizontalMovement(GrimPlayer player) {
        double vy = player.clientVelocity.getY();
        player.clientVelocity.setX(0);
        player.clientVelocity.setZ(0);
        if (player.user != null) {
            player.user.writePacket(new WrapperPlayServerEntityVelocity(player.entityID, new Vector3d(0, vy, 0)));
        }
    }

    public static boolean exceedsAllowedSpeed(GrimPlayer player, double horiz, boolean onGround) {
        return horiz > getAllowedHorizontalSpeed(player, onGround)
                + (onGround ? GROUND_SPEED_SLACK : AIR_SPEED_SLACK);
    }

    public static boolean requiresInstantSpeedSetback(GrimPlayer player, double horiz, boolean onGround) {
        return isBlatantHorizontalSpeed(horiz) || exceedsAllowedSpeed(player, horiz, onGround);
    }

    public static PacketMoveVerdict evaluatePostPrediction(GrimPlayer player, double horiz, double deltaY, boolean onGround) {
        if (!enabled || !Strafe2b2tModifications.shouldEvaluateOnFoot(player, true, false)) {
            return PacketMoveVerdict.ALLOW;
        }
        updateAirTicks(player, onGround, deltaY);

        if (hasMovementExempt(player)) {
            return PacketMoveVerdict.ALLOW;
        }

        PacketMoveVerdict vertical = evaluateVertical(player, deltaY, onGround);
        if (vertical != PacketMoveVerdict.ALLOW) {
            return vertical;
        }
        if (isBlatantHorizontalSpeed(horiz) || exceedsAllowedSpeed(player, horiz, onGround)) {
            return PacketMoveVerdict.SPEED_EXCEEDED;
        }
        return PacketMoveVerdict.ALLOW;
    }

    public static void tickPearlPhaseGrace(GrimPlayer player) {
        if (player.packetStateData.pearlPhaseGraceTicks > 0) {
            player.packetStateData.pearlPhaseGraceTicks--;
        }
    }

    public static void updateAirTicks(GrimPlayer player, boolean onGround, double deltaY) {
        if (player.movementPackets == player.packetStateData.lastAirTickUpdateMovementPacket) {
            return;
        }
        player.packetStateData.lastAirTickUpdateMovementPacket = player.movementPackets;

        tickPearlPhaseGrace(player);

        if (onGround) {
            player.packetStateData.consecutiveAirTicks = 0;
            player.packetStateData.ticksSinceOnGround = 0;
            player.packetStateData.consecutiveHoverAirTicks = 0;
            player.packetStateData.wasOnGroundLastStrafeTick = true;
            return;
        }

        player.packetStateData.consecutiveAirTicks++;
        player.packetStateData.ticksSinceOnGround++;

        if (Math.abs(deltaY) < hoverDeltaYEpsilon && !isLegitOnFootAirMovement(player, deltaY, onGround)) {
            player.packetStateData.consecutiveHoverAirTicks++;
        } else {
            player.packetStateData.consecutiveHoverAirTicks = 0;
        }
        player.packetStateData.wasOnGroundLastStrafeTick = false;
    }

    public static boolean isLegitOnFootAirMovement(GrimPlayer player, double deltaY, boolean onGround) {
        if (onGround) {
            return false;
        }
        if (hasJumpPacket(player)) {
            return true;
        }
        if (player.packetStateData.fallBufferTicks > 0) {
            return true;
        }
        if (player.packetStateData.ticksSinceOnGround <= NATURAL_JUMP_ARC_TICKS) {
            return true;
        }
        if (deltaY < 0.15D) {
            return true;
        }
        if (Step2b2tModifications.isLegitStepTick(player, deltaY,
                Math.hypot(player.actualMovement.getX(), player.actualMovement.getZ()))) {
            return true;
        }
        KnownInput input = player.packetStateData.knownInput;
        if (input != null && input != KnownInput.DEFAULT && !input.moving()) {
            return true;
        }
        return false;
    }

    private static boolean isCoastingMidAir(GrimPlayer player) {
        KnownInput input = player.packetStateData.knownInput;
        return input != null && input != KnownInput.DEFAULT && !input.moving();
    }

    private static boolean hasMovementExempt(GrimPlayer player) {
        if (player.isGliding) {
            return true;
        }
        if (player.inVehicle() || player.isFlying || player.canFly) {
            return true;
        }
        if (player.gamemode == GameMode.CREATIVE || player.gamemode == GameMode.SPECTATOR) {
            return true;
        }
        if (player.wasTouchingWater || player.wasTouchingLava || player.isClimbing) {
            return true;
        }
        if (player.riptideSpinAttackTicks > 0) {
            return true;
        }
        if (player.compensatedEntities.self.hasPotionEffect(PotionTypes.LEVITATION)
                || player.compensatedEntities.self.hasPotionEffect(PotionTypes.SLOW_FALLING)) {
            return true;
        }
        if (player.likelyKB != null || player.likelyExplosions != null || player.firstBreadKB != null) {
            return true;
        }
        if (player.packetStateData.pearlPhaseGraceTicks > 0) {
            return true;
        }
        return false;
    }

    private static boolean isJumpOrStepArc(GrimPlayer player, double deltaY, boolean onGround) {
        return isLegitOnFootAirMovement(player, deltaY, onGround);
    }

    private static PacketMoveVerdict evaluateVertical(GrimPlayer player, double deltaY, boolean onGround) {
        boolean jumping = hasJumpPacket(player);

        if (deltaY > blatantHighJumpDeltaY + STEP_EPSILON) {
            return PacketMoveVerdict.HIGH_JUMP;
        }

        if (deltaY > 0 && !jumping) {
            if (deltaY > maxStepWithoutJump + STEP_EPSILON) {
                return PacketMoveVerdict.STEP_TOO_HIGH;
            }
            return PacketMoveVerdict.ALLOW;
        }

        if (!onGround
                && player.packetStateData.ticksSinceOnGround > jumpAirHorizontalGraceTicks
                && hasAirBelow(player)
                && !isJumpOrStepArc(player, deltaY, onGround)) {
            if (deltaY > blatantFlyAscendDeltaY) {
                return PacketMoveVerdict.SURVIVAL_FLY;
            }
            double horiz = Math.hypot(player.actualMovement.getX(), player.actualMovement.getZ());
            if (player.packetStateData.consecutiveHoverAirTicks >= maxHoverAirTicks + 2
                    && horiz < 0.03D
                    && !isCoastingMidAir(player)) {
                return PacketMoveVerdict.SURVIVAL_FLY;
            }
        }

        return PacketMoveVerdict.ALLOW;
    }

    public static double getAllowedHorizontalSpeed(GrimPlayer player, boolean onGround) {
        if (!onGround) {
            if (player.packetStateData.ticksSinceOnGround <= jumpFirstTicksHorizontalGrace) {
                return jumpFirstTicksHorizontalCap;
            }
            if (player.packetStateData.ticksSinceOnGround <= jumpAirHorizontalGraceTicks) {
                return jumpAirHorizontalCap;
            }
            return maxHorizontalPerTick;
        }
        return maxHorizontalPerTick;
    }

    public static boolean hasJumpPacket(GrimPlayer player) {
        if (player.isJumping) {
            return true;
        }
        KnownInput input = player.packetStateData.knownInput;
        return input != null && input != KnownInput.DEFAULT && input.jump();
    }

    public static boolean hasAirBelow(GrimPlayer player) {
        SimpleCollisionBox feet = player.boundingBox != null
                ? player.boundingBox
                : GetBoundingBox.getCollisionBoxForPlayer(player, player.x, player.y, player.z);
        SimpleCollisionBox probe = new SimpleCollisionBox(
                feet.minX,
                feet.minY - airProbeDepth,
                feet.minZ,
                feet.maxX,
                feet.minY - 0.05D,
                feet.maxZ
        );
        return Collisions.isEmpty(player, probe);
    }

    public static String formatVerdict(PacketMoveVerdict verdict, double horiz, double deltaY) {
        return verdict.name().toLowerCase() + " h=" + String.format("%.3f", horiz)
                + " dy=" + String.format("%.3f", deltaY);
    }

    public static void rollback(GrimPlayer player, double x, double y, double z) {
        Strafe2b2tModifications.rollbackOnFoot(player, x, y, z);
        hardStopHorizontalMovement(player);
    }
}
