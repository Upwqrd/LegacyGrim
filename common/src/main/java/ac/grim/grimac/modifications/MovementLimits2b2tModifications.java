package ac.grim.grimac.modifications;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.impl.movement.StrafeLimits2b2t;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.data.PacketStateData;
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
    public static final double JUMP_BURST_BLOCKS_PER_TICK = 11.0D / 20.0D;
    public static double jumpFirstTicksHorizontalCap = 0.62D;
    public static int jumpFirstTicksHorizontalGrace = 5;
    public static double jumpAirHorizontalCap = JUMP_BURST_BLOCKS_PER_TICK;
    public static int jumpAirHorizontalGraceTicks = 20;
    /** Pre-execution: cancel packet before position apply (blocks 60 km/h teleport). */
    public static double preCancelHorizontalPerTick = 0.45D;
    /** Hard per-server-tick strafe ceiling (30 km/h + micro gap); locks rest of tick on breach. */
    public static boolean hardStrafeBarrierEnabled = true;
    public static double hardStrafeBarrierPerTick = 0.435D;
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
    private static final double JUMP_TAKEOFF_DELTA_Y = 0.035D;
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
        jumpFirstTicksHorizontalCap = config.getDoubleElse(PREFIX + "jump-first-ticks-horizontal-cap", 0.72D);
        jumpFirstTicksHorizontalGrace = Math.max(1, config.getIntElse(PREFIX + "jump-first-ticks-grace", 5));
        jumpAirHorizontalCap = config.getDoubleElse(PREFIX + "jump-air-horizontal-cap", JUMP_BURST_BLOCKS_PER_TICK);
        jumpAirHorizontalGraceTicks = Math.max(1, config.getIntElse(PREFIX + "jump-air-horizontal-grace-ticks", 20));
        preCancelHorizontalPerTick = config.getDoubleElse(PREFIX + "pre-cancel-horizontal-per-tick", 0.45D);
        hardStrafeBarrierEnabled = config.getBooleanElse(PREFIX + "hard-strafe-barrier-enabled", false);
        hardStrafeBarrierPerTick = config.getDoubleElse(PREFIX + "hard-strafe-barrier-per-tick", 0.435D);
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

    /**
     * Updates sprint-jump burst state before pre-cancel runs on this packet.
     */
    public static void updatePacketStateBeforeMove(GrimPlayer player, boolean packetOnGround, double deltaY) {
        if (!enabled || !Strafe2b2tModifications.shouldEvaluateOnFoot(player, true, false)) {
            return;
        }
        Strafe2b2tModifications.updateMovementBuffers(player, packetOnGround, deltaY);
    }

    public static void trackPacketState(
            GrimPlayer player,
            Vector3d packetPosition,
            boolean packetOnGround,
            float yaw,
            float pitch
    ) {
        updatePacketStateBeforeMove(player, packetOnGround, packetPosition.getY() - player.y);
    }

    public static boolean isJumpTakeoffMovement(GrimPlayer player, double deltaY, boolean packetOnGround) {
        if (deltaY > JUMP_TAKEOFF_DELTA_Y) {
            return true;
        }
        if (hasJumpPacket(player)) {
            return true;
        }
        if (!packetOnGround && player.packetStateData.wasOnGroundLastStrafeTick) {
            return true;
        }
        return false;
    }

    public static boolean isInJumpSpeedGrace(GrimPlayer player, double deltaY, boolean onGround) {
        if (isJumpTakeoffMovement(player, deltaY, onGround)) {
            return true;
        }
        if (player.packetStateData.airMomentumHorizLimit > maxHorizontalPerTick + 0.01D) {
            return true;
        }
        if (player.packetStateData.ticksSinceOnGround <= jumpAirHorizontalGraceTicks) {
            return true;
        }
        if (player.packetStateData.wasOnGroundLastStrafeTick
                && player.packetStateData.ticksSinceOnGround <= Strafe2b2tModifications.bunnyHopGroundGraceTicks + 1) {
            return true;
        }
        return false;
    }

    /** Vanilla sprint / walk on ground — below hard barrier and 30 km/h cap. */
    public static boolean isVanillaGroundStrafe(GrimPlayer player, double packetHoriz, boolean packetOnGround) {
        if (!packetOnGround) {
            return false;
        }
        return packetHoriz <= maxHorizontalPerTick + GROUND_SPEED_SLACK + 0.02D;
    }

    public static boolean isInActiveJumpArc(GrimPlayer player, double deltaY, boolean onGround) {
        if (isInJumpSpeedGrace(player, deltaY, onGround)) {
            return true;
        }
        if (player.isJumping) {
            return true;
        }
        if (player.clientVelocity.getY() > 0.05D) {
            return true;
        }
        return hasJumpPacket(player);
    }

    public static boolean shouldSkipStrictSpeedEnforcement(
            GrimPlayer player,
            double packetHoriz,
            double deltaY,
            boolean onGround
    ) {
        if (hasMovementExempt(player)) {
            return true;
        }
        if (isInActiveJumpArc(player, deltaY, onGround)) {
            return true;
        }
        return isVanillaGroundStrafe(player, packetHoriz, onGround);
    }

    public static double resolveHorizontalSpeed(GrimPlayer player) {
        return resolvePacketHorizontalSpeed(player);
    }

    /** Packet delta only — simulation offset must not trigger strafe setbacks. */
    public static double resolvePacketHorizontalSpeed(GrimPlayer player) {
        return Math.hypot(player.x - player.lastX, player.z - player.lastZ);
    }

    public static boolean isBlatantHorizontalSpeed(double horiz) {
        return horiz > blatantHorizontalPerTick;
    }

    /**
     * Snapshot position at server tick start; clears per-tick strafe lock.
     */
    public static void onServerTickStart(GrimPlayer player) {
        if (!enabled || !hardStrafeBarrierEnabled) {
            return;
        }
        PacketStateData data = player.packetStateData;
        data.hardStrafeServerTickId = GrimAPI.INSTANCE.getTickManager().currentTick;
        data.hardStrafeBarrierLocked = false;
        data.hardStrafeMovementPacketsThisTick = 0;
        data.hardStrafeTickStartX = player.x;
        data.hardStrafeTickStartY = player.y;
        data.hardStrafeTickStartZ = player.z;
        data.hardStrafeTickStartYaw = player.yaw;
        data.hardStrafeTickStartPitch = player.pitch;
        data.hasHardStrafeTickStart = true;
    }

    private static void ensureHardStrafeTickAligned(GrimPlayer player) {
        int tick = GrimAPI.INSTANCE.getTickManager().currentTick;
        if (player.packetStateData.hardStrafeServerTickId != tick) {
            onServerTickStart(player);
        }
    }

    private static double resolveHardStrafePacketHoriz(double fromX, double fromZ, double toX, double toZ) {
        return Math.hypot(toX - fromX, toZ - fromZ);
    }

    /**
     * Hard 30 km/h barrier: one breach per server tick locks all further movement packets until next tick.
     */
    public static boolean tryHardStrafeTickBarrier(
            GrimPlayer player,
            double fromX,
            double fromY,
            double fromZ,
            Vector3d packetPosition,
            boolean packetOnGround,
            PacketReceiveEvent event
    ) {
        if (!enabled || !hardStrafeBarrierEnabled
                || !Strafe2b2tModifications.shouldEvaluateOnFoot(player, true, false)) {
            return false;
        }
        if (hasMovementExempt(player)) {
            return false;
        }

        double deltaY = packetPosition.getY() - fromY;
        double packetHoriz = resolveHardStrafePacketHoriz(
                fromX,
                fromZ,
                packetPosition.getX(),
                packetPosition.getZ()
        );
        if (shouldSkipStrictSpeedEnforcement(player, packetHoriz, deltaY, packetOnGround)) {
            return false;
        }

        ensureHardStrafeTickAligned(player);
        PacketStateData data = player.packetStateData;
        data.hardStrafeMovementPacketsThisTick++;

        if (data.hardStrafeBarrierLocked) {
            return cancelHardStrafeBarrier(player, event, "hard_barrier_locked", false);
        }

        if (packetHoriz > hardStrafeBarrierPerTick) {
            data.hardStrafeBarrierLocked = true;
            return cancelHardStrafeBarrier(
                    player,
                    event,
                    "hard_barrier h=" + String.format("%.3f", packetHoriz),
                    true
            );
        }
        return false;
    }

    private static boolean cancelHardStrafeBarrier(
            GrimPlayer player,
            PacketReceiveEvent event,
            String verbose,
            boolean applySetback
    ) {
        StrafeLimits2b2t limits = player.checkManager.getPostPredictionCheck(StrafeLimits2b2t.class);
        if (limits != null) {
            limits.flagAndAlert(verbose);
        }
        if (applySetback) {
            forceSetbackToHardStrafeTickStart(player);
            hardStopHorizontalMovement(player);
        }
        event.setCancelled(true);
        player.onPacketCancel();
        return true;
    }

    public static void forceSetbackToHardStrafeTickStart(GrimPlayer player) {
        PacketStateData data = player.packetStateData;
        if (data.hasHardStrafeTickStart) {
            Strafe2b2tModifications.rollbackToExactPosition(
                    player,
                    data.hardStrafeTickStartX,
                    data.hardStrafeTickStartY,
                    data.hardStrafeTickStartZ,
                    data.hardStrafeTickStartYaw,
                    data.hardStrafeTickStartPitch
            );
            return;
        }
        Strafe2b2tModifications.rollbackToExactPosition(
                player,
                player.lastX,
                player.lastY,
                player.lastZ,
                player.yaw,
                player.pitch
        );
    }

    /**
     * Hard ceiling on incoming packets — applied before {@code player.x/y/z} update.
     */
    public static double getPreCancelHorizontalLimit(GrimPlayer player, boolean packetOnGround, double deltaY) {
        if (isJumpTakeoffMovement(player, deltaY, packetOnGround)) {
            return Strafe2b2tModifications.vanillaBurstHorizontalCap;
        }
        if (player.packetStateData.airMomentumHorizLimit > preCancelHorizontalPerTick) {
            return player.packetStateData.airMomentumHorizLimit;
        }
        if (packetOnGround) {
            return Math.max(preCancelHorizontalPerTick, maxHorizontalPerTick + GROUND_SPEED_SLACK);
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
        double deltaY = toY - fromY;
        double horiz = Math.hypot(toX - fromX, toZ - fromZ);
        if (shouldSkipStrictSpeedEnforcement(player, horiz, deltaY, packetOnGround)) {
            return false;
        }
        if (isJumpTakeoffMovement(player, deltaY, packetOnGround)) {
            return horiz > Strafe2b2tModifications.vanillaBurstHorizontalCap + AIR_SPEED_SLACK;
        }
        return horiz > getPreCancelHorizontalLimit(player, packetOnGround, deltaY) + AIR_SPEED_SLACK;
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

    public static boolean exceedsAllowedSpeed(GrimPlayer player, double horiz, boolean onGround, double deltaY) {
        return horiz > getAllowedHorizontalSpeed(player, onGround, deltaY)
                + (onGround && !isJumpTakeoffMovement(player, deltaY, onGround) ? GROUND_SPEED_SLACK : AIR_SPEED_SLACK);
    }

    public static boolean requiresInstantSpeedSetback(GrimPlayer player, double horiz, boolean onGround, double deltaY) {
        if (shouldSkipStrictSpeedEnforcement(player, horiz, deltaY, onGround)) {
            return horiz > Strafe2b2tModifications.vanillaBurstHorizontalCap + AIR_SPEED_SLACK;
        }
        return isBlatantHorizontalSpeed(horiz) || exceedsAllowedSpeed(player, horiz, onGround, deltaY);
    }

    public static boolean shouldPostPredictionSetback(
            GrimPlayer player,
            PacketMoveVerdict verdict,
            double horiz,
            double deltaY,
            boolean onGround
    ) {
        if (verdict != PacketMoveVerdict.SPEED_EXCEEDED) {
            return true;
        }
        if (shouldSkipStrictSpeedEnforcement(player, horiz, deltaY, onGround)) {
            return false;
        }
        return horiz > blatantHorizontalPerTick;
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
        if (Speed2b2tModifications.enabled) {
            return PacketMoveVerdict.ALLOW;
        }
        if (isInJumpSpeedGrace(player, deltaY, onGround)) {
            if (horiz > Strafe2b2tModifications.vanillaBurstHorizontalCap + AIR_SPEED_SLACK) {
                return PacketMoveVerdict.SPEED_EXCEEDED;
            }
        } else if (isBlatantHorizontalSpeed(horiz) || exceedsAllowedSpeed(player, horiz, onGround, deltaY)) {
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
        // Use inventory swap tick tracking to allow a 7‑tick grace after swapping chestplate/elytra
        int currentTick = GrimAPI.INSTANCE.getTickManager().currentTick;
        if (currentTick - player.lastInventorySwapTick <= 7 && deltaY > 0) {
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

    public static double getAllowedHorizontalSpeed(GrimPlayer player, boolean onGround, double deltaY) {
        if (isJumpTakeoffMovement(player, deltaY, onGround)) {
            return Strafe2b2tModifications.vanillaBurstHorizontalCap;
        }
        if (player.packetStateData.airMomentumHorizLimit > maxHorizontalPerTick) {
            return player.packetStateData.airMomentumHorizLimit;
        }
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

    private static void ensureVerticalTickAligned(GrimPlayer player, double fromX, double fromY, double fromZ) {
        int tick = GrimAPI.INSTANCE.getTickManager().currentTick;
        if (player.packetStateData.verticalTickId != tick) {
            player.packetStateData.verticalTickId = tick;
            player.packetStateData.verticalDistanceThisTick = 0.0;
            player.packetStateData.verticalTickStartX = fromX;
            player.packetStateData.verticalTickStartY = fromY;
            player.packetStateData.verticalTickStartZ = fromZ;
            player.packetStateData.verticalTickStartYaw = player.yaw;
            player.packetStateData.verticalTickStartPitch = player.pitch;
            player.packetStateData.hasVerticalTickStart = true;
        }
    }

    public static void forceSetbackToVerticalTickStart(GrimPlayer player) {
        PacketStateData data = player.packetStateData;
        if (data.hasVerticalTickStart) {
            Strafe2b2tModifications.rollbackToExactPosition(
                    player,
                    data.verticalTickStartX,
                    data.verticalTickStartY,
                    data.verticalTickStartZ,
                    data.verticalTickStartYaw,
                    data.verticalTickStartPitch
            );
            return;
        }
        Strafe2b2tModifications.rollbackToExactPosition(
                player,
                player.lastX,
                player.lastY,
                player.lastZ,
                player.yaw,
                player.pitch
        );
    }

    public static boolean tryBlockVerticalSpeedPacket(
            GrimPlayer player,
            double fromX,
            double fromY,
            double fromZ,
            Vector3d packetPosition,
            boolean packetOnGround,
            PacketReceiveEvent event
    ) {
        if (!enabled) {
            return false;
        }
        if (hasMovementExempt(player)) {
            return false;
        }

        double deltaY = packetPosition.getY() - fromY;
        if (deltaY <= 0) {
            return false;
        }

        ensureVerticalTickAligned(player, fromX, fromY, fromZ);
        PacketStateData data = player.packetStateData;
        data.verticalDistanceThisTick += deltaY;

        double maxYDelta = 0.57D;
        if (isJumpTakeoffMovement(player, deltaY, packetOnGround)) {
            maxYDelta = 0.60D;
        }

        final java.util.OptionalInt jumpBoost = player.compensatedEntities.getPotionLevelForPlayer(PotionTypes.JUMP_BOOST);
        if (jumpBoost.isPresent()) {
            maxYDelta += 0.1D * jumpBoost.getAsInt();
        }

        if (data.verticalDistanceThisTick > maxYDelta) {
            StrafeLimits2b2t limits = player.checkManager.getPostPredictionCheck(StrafeLimits2b2t.class);
            if (limits != null) {
                limits.flagAndAlert("high_jump dy_sum=" + String.format("%.3f", data.verticalDistanceThisTick) + " max=" + String.format("%.3f", maxYDelta));
            }
            // After vertical overflow, also clear horizontal speed tick start to avoid stale horizontal accumulation
            data.speed2b2tTickStartX = data.speed2b2tTickStartY = data.speed2b2tTickStartZ = 0.0D;
            data.speed2b2tTickStartYaw = data.speed2b2tTickStartPitch = 0.0F;
            data.hasSpeed2b2tTickStart = false;
        }

        return false;
    }
}
