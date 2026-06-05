package ac.grim.grimac.modifications;

import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.data.KnownInput;
import ac.grim.grimac.utils.nmsutil.GetBoundingBox;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.potion.PotionTypes;
import com.github.retrooper.packetevents.util.Vector3d;

/**
 * 2b2t.org.ru fork: strict 28 km/h strafe cap; vanilla sprint-jump bursts exempt.
 */
public final class Strafe2b2tModifications {

    /** 28 km/h @ 20 TPS (7.77 m/s). */
    public static final double MAX_STRAFE_HORIZONTAL_PER_TICK = 0.388D;
    /** Tight tolerance — blocks ~40 km/h strafe multiplier cheats. */
    public static final double STRICT_STRAFE_TOLERANCE = 0.02D;
    /** Brief vanilla sprint-jump / bhop horizontal peak. */
    public static final double VANILLA_BURST_HORIZONTAL_CAP = 0.72D;
    public static final int JUMP_GRACE_AIR_TICKS = 18;
    public static final int BUNNY_HOP_GROUND_GRACE_TICKS = 4;
    public static final int MAX_AIR_STRAFE_EXPLOIT_TICKS = 50;

    private static final double FALL_DELTA_Y_THRESHOLD = -0.02D;
    private static final double JUMP_DELTA_Y_THRESHOLD = 0.035D;
    private static final double HOVER_DELTA_Y_EPSILON = 0.006D;
    private static final double ASCEND_DELTA_Y_MIN = 0.015D;
    private static final double BLATANT_CHEAT_HORIZONTAL = 1.1D;

    private Strafe2b2tModifications() {
    }

    public static double getStrictStrafeCap() {
        return MAX_STRAFE_HORIZONTAL_PER_TICK + STRICT_STRAFE_TOLERANCE;
    }

    public static boolean shouldEvaluateOnFoot(GrimPlayer player, boolean hasPosition, boolean isTeleport) {
        if (!hasPosition || isTeleport || player.disableGrim) {
            return false;
        }
        if (player.inVehicle() || player.isFlying || player.isGliding || player.isInBed) {
            return false;
        }
        return player.gamemode != GameMode.SPECTATOR && player.gamemode != GameMode.CREATIVE;
    }

    public static double horizontalBlocksPerTick(double dx, double dz) {
        return Math.hypot(dx, dz);
    }

    public static StrafeMoveVerdict evaluateOnFootMove(
            GrimPlayer player,
            Vector3d packetPosition,
            boolean packetOnGround,
            float yaw,
            float pitch
    ) {
        if (!shouldEvaluateOnFoot(player, true, false)) {
            return StrafeMoveVerdict.ALLOW;
        }

        double lastX = player.x;
        double lastY = player.y;
        double lastZ = player.z;

        double dx = packetPosition.getX() - lastX;
        double dy = packetPosition.getY() - lastY;
        double dz = packetPosition.getZ() - lastZ;
        double horizPerTick = horizontalBlocksPerTick(dx, dz);

        updateMovementBuffers(player, packetOnGround, dy);

        Step2b2tModifications.StepVerdict stepVerdict = Step2b2tModifications.evaluateVerticalStep(player, dy, horizPerTick);
        if (stepVerdict == Step2b2tModifications.StepVerdict.STEP_TOO_HIGH) {
            return StrafeMoveVerdict.STEP_TOO_HIGH;
        }
        if (stepVerdict == Step2b2tModifications.StepVerdict.ILLEGAL_STEP) {
            return StrafeMoveVerdict.ILLEGAL_STEP;
        }

        if (exceedsStrictStrafeSpeed(player, horizPerTick, dy)) {
            return StrafeMoveVerdict.SPEED_EXCEEDED;
        }

        if (shouldFlagAirStrafeExploit(player, horizPerTick, dy)) {
            return StrafeMoveVerdict.AIR_STRAFE_EXPLOIT;
        }

        saveLastLegitOnFootPosition(player, packetPosition, yaw, pitch);
        return StrafeMoveVerdict.ALLOW;
    }

    private static void updateMovementBuffers(GrimPlayer player, boolean packetOnGround, double deltaY) {
        if (deltaY < FALL_DELTA_Y_THRESHOLD) {
            player.packetStateData.fallBufferTicks = 3;
        } else if (player.packetStateData.fallBufferTicks > 0) {
            player.packetStateData.fallBufferTicks--;
        }

        if (packetOnGround) {
            player.packetStateData.consecutiveAirTicks = 0;
            player.packetStateData.ticksSinceOnGround = 0;
            player.packetStateData.wasOnGroundLastStrafeTick = true;
            player.packetStateData.airMomentumHorizLimit = 0;
            return;
        }

        player.packetStateData.consecutiveAirTicks++;
        player.packetStateData.ticksSinceOnGround++;

        boolean jumpImpulse = deltaY > JUMP_DELTA_Y_THRESHOLD
                || player.packetStateData.knownInput.jump()
                || player.clientVelocity.getY() > 0.06D
                || player.isJumping;

        if ((player.packetStateData.wasOnGroundLastStrafeTick
                || player.packetStateData.ticksSinceOnGround <= BUNNY_HOP_GROUND_GRACE_TICKS)
                && jumpImpulse) {
            player.packetStateData.airMomentumHorizLimit = VANILLA_BURST_HORIZONTAL_CAP;
        } else if (player.packetStateData.airMomentumHorizLimit > 0) {
            player.packetStateData.airMomentumHorizLimit *= 0.92D;
            if (player.packetStateData.airMomentumHorizLimit < getStrictStrafeCap()) {
                player.packetStateData.airMomentumHorizLimit = 0;
            }
        }

        player.packetStateData.wasOnGroundLastStrafeTick = false;
    }

    private static boolean isVanillaBurstExempt(GrimPlayer player, double deltaY) {
        if (Movement2b2tModifications.isHighSpeedFallContext(player, deltaY)) {
            return true;
        }
        if (player.packetStateData.consecutiveAirTicks > JUMP_GRACE_AIR_TICKS) {
            return false;
        }
        return player.packetStateData.airMomentumHorizLimit > getStrictStrafeCap()
                || player.packetStateData.ticksSinceOnGround <= BUNNY_HOP_GROUND_GRACE_TICKS;
    }

    private static double getActiveSpeedCap(GrimPlayer player, double deltaY) {
        if (isVanillaBurstExempt(player, deltaY)) {
            return VANILLA_BURST_HORIZONTAL_CAP;
        }
        return getStrictStrafeCap();
    }

    private static boolean exceedsStrictStrafeSpeed(GrimPlayer player, double horiz, double deltaY) {
        if (horiz > BLATANT_CHEAT_HORIZONTAL) {
            return true;
        }
        return horiz > getActiveSpeedCap(player, deltaY);
    }

    public static boolean shouldBypassVanillaSimulation(GrimPlayer player, double horizPerTick) {
        if (!shouldEvaluateOnFoot(player, true, false)) {
            return false;
        }
        double deltaY = player.actualMovement.getY();
        if (Movement2b2tModifications.isHighSpeedFallContext(player, deltaY)) {
            return horizPerTick <= Movement2b2tModifications.getHardHorizontalLimit(player, deltaY);
        }
        if (Step2b2tModifications.isLegitStepTick(player, deltaY, horizPerTick)) {
            return true;
        }
        return horizPerTick <= getActiveSpeedCap(player, deltaY);
    }

    private static boolean shouldFlagAirStrafeExploit(GrimPlayer player, double horizPerTick, double deltaY) {
        if (isVanillaBurstExempt(player, deltaY)) {
            return false;
        }
        if (player.packetStateData.consecutiveAirTicks <= MAX_AIR_STRAFE_EXPLOIT_TICKS) {
            return false;
        }
        if (player.packetStateData.fallBufferTicks > 0) {
            return false;
        }
        if (horizPerTick < getStrictStrafeCap() * 0.8D || horizPerTick > BLATANT_CHEAT_HORIZONTAL) {
            return false;
        }
        if (hasLegitimateVerticalCause(player, deltaY)) {
            return false;
        }
        boolean ascending = deltaY > ASCEND_DELTA_Y_MIN;
        boolean hovering = Math.abs(deltaY) < HOVER_DELTA_Y_EPSILON;
        return ascending || hovering;
    }

    private static boolean hasLegitimateVerticalCause(GrimPlayer player, double deltaY) {
        if (player.isClimbing || player.wasTouchingWater || player.wasTouchingLava) {
            return true;
        }
        if (player.isGliding || player.isFlying || player.riptideSpinAttackTicks > 0) {
            return true;
        }
        if (player.uncertaintyHandler.isSteppingNearBubbleColumn) {
            return true;
        }
        if (player.uncertaintyHandler.isSteppingOnBouncyBlock && deltaY > 0) {
            return true;
        }
        if (player.likelyKB != null || player.likelyExplosions != null || player.firstBreadKB != null) {
            return true;
        }
        if (player.compensatedEntities.self.hasPotionEffect(PotionTypes.LEVITATION)
                || player.compensatedEntities.self.hasPotionEffect(PotionTypes.SLOW_FALLING)) {
            return true;
        }
        if (Step2b2tModifications.isLegitStepTick(player, deltaY,
                Math.hypot(player.actualMovement.getX(), player.actualMovement.getZ()))) {
            return true;
        }
        return false;
    }

    public static void saveLastLegitOnFootPosition(GrimPlayer player, Vector3d position, float yaw, float pitch) {
        player.packetStateData.hasLastLegitOnFootPosition = true;
        player.packetStateData.lastLegitOnFootX = position.getX();
        player.packetStateData.lastLegitOnFootY = position.getY();
        player.packetStateData.lastLegitOnFootZ = position.getZ();
        player.packetStateData.lastLegitOnFootYaw = yaw;
        player.packetStateData.lastLegitOnFootPitch = pitch;
    }

    public static void rollbackOnFoot(GrimPlayer player, double fallbackX, double fallbackY, double fallbackZ) {
        double x = fallbackX;
        double y = fallbackY;
        double z = fallbackZ;
        float yaw = player.yaw;
        float pitch = player.pitch;

        if (player.packetStateData.hasLastLegitOnFootPosition) {
            x = player.packetStateData.lastLegitOnFootX;
            y = player.packetStateData.lastLegitOnFootY;
            z = player.packetStateData.lastLegitOnFootZ;
            yaw = player.packetStateData.lastLegitOnFootYaw;
            pitch = player.packetStateData.lastLegitOnFootPitch;
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
        player.getSetbackTeleportUtil().executeNonSimulatingForceResync();
    }

    public static String formatVerdict(StrafeMoveVerdict verdict, double horizSpeed, double deltaY) {
        return verdict.name().toLowerCase() + " h=" + String.format("%.3f", horizSpeed) + " dy=" + String.format("%.3f", deltaY);
    }

    public static double reduceStrafeOffset(GrimPlayer player, double offset) {
        if (Elytra2b2tModifications.reduceElytraOffset(player, offset) == 0 && (player.isGliding || player.wasGliding)) {
            return 0;
        }
        if (Movement2b2tModifications.shouldBypassVanillaSimulation(player)) {
            return 0;
        }
        offset = Step2b2tModifications.reduceStepOffset(player, offset);
        double horizActual = Math.hypot(player.actualMovement.getX(), player.actualMovement.getZ());
        if (shouldBypassVanillaSimulation(player, horizActual)) {
            return 0;
        }
        return offset;
    }

    public static boolean shouldFlagPostPrediction(GrimPlayer player) {
        if (!shouldEvaluateOnFoot(player, true, false)) {
            return false;
        }

        double horiz = Math.hypot(player.actualMovement.getX(), player.actualMovement.getZ());
        double deltaY = player.actualMovement.getY();

        if (shouldBypassVanillaSimulation(player, horiz)) {
            Step2b2tModifications.StepVerdict step = Step2b2tModifications.evaluateVerticalStep(player, deltaY, horiz);
            return step != Step2b2tModifications.StepVerdict.ALLOW;
        }

        if (exceedsStrictStrafeSpeed(player, horiz, deltaY)) {
            return true;
        }

        return shouldFlagAirStrafeExploit(player, horiz, deltaY);
    }
}
