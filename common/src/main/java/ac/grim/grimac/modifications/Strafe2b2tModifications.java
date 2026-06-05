package ac.grim.grimac.modifications;

import ac.grim.grimac.api.config.ConfigManager;
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

    private static final String PREFIX = "Strafe2b2t.";

    /** 28 km/h @ 20 TPS (7.77 m/s). */
    public static double maxStrafeHorizontalPerTick = 0.388D;
    /** Tight tolerance — blocks ~40 km/h strafe multiplier cheats. */
    public static double strictStrafeTolerance = 0.02D;
    /** Brief vanilla sprint-jump / bhop horizontal peak. */
    public static double vanillaBurstHorizontalCap = 0.72D;
    public static int jumpGraceAirTicks = 18;
    public static int bunnyHopGroundGraceTicks = 4;
    public static int maxAirStrafeExploitTicks = 50;

    private static double fallDeltaYThreshold = -0.02D;
    private static double jumpDeltaYThreshold = 0.035D;
    private static double hoverDeltaYEpsilon = 0.006D;
    private static double ascendDeltaYMin = 0.015D;
    private static double blatantCheatHorizontal = 1.1D;

    private Strafe2b2tModifications() {
    }

    public static void reload(ConfigManager config) {
        maxStrafeHorizontalPerTick = config.getDoubleElse(PREFIX + "max-strafe-horizontal-per-tick", 0.388D);
        strictStrafeTolerance = config.getDoubleElse(PREFIX + "strict-strafe-tolerance", 0.02D);
        vanillaBurstHorizontalCap = config.getDoubleElse(PREFIX + "vanilla-burst-horizontal-cap", 0.72D);
        jumpGraceAirTicks = Math.max(1, config.getIntElse(PREFIX + "jump-grace-air-ticks", 18));
        bunnyHopGroundGraceTicks = Math.max(1, config.getIntElse(PREFIX + "bunny-hop-ground-grace-ticks", 4));
        maxAirStrafeExploitTicks = Math.max(1, config.getIntElse(PREFIX + "max-air-strafe-exploit-ticks", 50));
        fallDeltaYThreshold = config.getDoubleElse(PREFIX + "fall-delta-y-threshold", -0.02D);
        jumpDeltaYThreshold = config.getDoubleElse(PREFIX + "jump-delta-y-threshold", 0.035D);
        hoverDeltaYEpsilon = config.getDoubleElse(PREFIX + "hover-delta-y-epsilon", 0.006D);
        ascendDeltaYMin = config.getDoubleElse(PREFIX + "ascend-delta-y-min", 0.015D);
        blatantCheatHorizontal = config.getDoubleElse(PREFIX + "blatant-cheat-horizontal", 1.1D);
    }

    public static double getStrictStrafeCap() {
        return maxStrafeHorizontalPerTick + strictStrafeTolerance;
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

        if (exceedsStrictStrafeSpeed(player, horizPerTick, dy)) {
            return StrafeMoveVerdict.SPEED_EXCEEDED;
        }

        if (shouldFlagAirStrafeExploit(player, horizPerTick, dy)) {
            return StrafeMoveVerdict.AIR_STRAFE_EXPLOIT;
        }

        saveLastLegitOnFootPosition(player, packetPosition, yaw, pitch);
        return StrafeMoveVerdict.ALLOW;
    }

    /** Called before pre-cancel so sprint-jump burst limits apply to the same packet. */
    public static void updateMovementBuffers(GrimPlayer player, boolean packetOnGround, double deltaY) {
        if (deltaY < fallDeltaYThreshold) {
            player.packetStateData.fallBufferTicks = 3;
        } else if (player.packetStateData.fallBufferTicks > 0) {
            player.packetStateData.fallBufferTicks--;
        }

        if (packetOnGround) {
            boolean jumpTakeoff = deltaY > jumpDeltaYThreshold
                    || player.isJumping
                    || (player.packetStateData.knownInput != KnownInput.DEFAULT
                    && player.packetStateData.knownInput.jump());
            if (jumpTakeoff) {
                player.packetStateData.airMomentumHorizLimit = vanillaBurstHorizontalCap;
                player.packetStateData.wasOnGroundLastStrafeTick = true;
                return;
            }
            player.packetStateData.consecutiveAirTicks = 0;
            player.packetStateData.ticksSinceOnGround = 0;
            player.packetStateData.wasOnGroundLastStrafeTick = true;
            player.packetStateData.airMomentumHorizLimit = 0;
            return;
        }

        player.packetStateData.consecutiveAirTicks++;
        player.packetStateData.ticksSinceOnGround++;

        boolean jumpImpulse = deltaY > jumpDeltaYThreshold
                || player.packetStateData.knownInput.jump()
                || player.clientVelocity.getY() > 0.06D
                || player.isJumping;

        if ((player.packetStateData.wasOnGroundLastStrafeTick
                || player.packetStateData.ticksSinceOnGround <= bunnyHopGroundGraceTicks)
                && jumpImpulse) {
            player.packetStateData.airMomentumHorizLimit = vanillaBurstHorizontalCap;
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
        if (player.packetStateData.consecutiveAirTicks > jumpGraceAirTicks) {
            return false;
        }
        return player.packetStateData.airMomentumHorizLimit > getStrictStrafeCap()
                || player.packetStateData.ticksSinceOnGround <= bunnyHopGroundGraceTicks;
    }

    private static double getActiveSpeedCap(GrimPlayer player, double deltaY) {
        if (isVanillaBurstExempt(player, deltaY)) {
            return vanillaBurstHorizontalCap;
        }
        return getStrictStrafeCap();
    }

    private static boolean exceedsStrictStrafeSpeed(GrimPlayer player, double horiz, double deltaY) {
        if (horiz > blatantCheatHorizontal) {
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
        if (player.packetStateData.consecutiveAirTicks <= maxAirStrafeExploitTicks) {
            return false;
        }
        if (player.packetStateData.fallBufferTicks > 0) {
            return false;
        }
        if (horizPerTick < getStrictStrafeCap() * 0.8D || horizPerTick > blatantCheatHorizontal) {
            return false;
        }
        if (hasLegitimateVerticalCause(player, deltaY)) {
            return false;
        }
        boolean ascending = deltaY > ascendDeltaYMin;
        boolean hovering = Math.abs(deltaY) < hoverDeltaYEpsilon;
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

    public static void rollbackToExactPosition(
            GrimPlayer player,
            double x,
            double y,
            double z,
            float yaw,
            float pitch
    ) {
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
