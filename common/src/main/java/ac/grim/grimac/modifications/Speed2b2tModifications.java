package ac.grim.grimac.modifications;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.impl.movement.SpeedLimits2b2t;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.data.PacketStateData;
import ac.grim.grimac.utils.nmsutil.GetBoundingBox;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.potion.PotionTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;

/**
 * 2b2t.org.ru fork: horizontal speed enforcement mirroring {@link Fly2b2tModifications}.
 * Blocks sustained &gt;30 km/h, per-packet bursts, and lag-split multi-packet ticks.
 */
public final class Speed2b2tModifications {

    private static final String PREFIX = "Speed2b2t.";

    public static boolean enabled = true;
    /** ~30 km/h sustained cap. */
    public static double maxSustainedBlocksPerTick = MovementLimits2b2tModifications.KMH_30_BLOCKS_PER_TICK;
    /** Single packet cap (30 km/h + micro gap). */
    public static double maxPacketBlocksPerTick = 0.435D;
    /** Max horizontal travel from tick start (anti lag-split). */
    public static double maxTickCumulativeBlocks = 0.65D;
    /** Instant block (~39.6 km/h+). */
    public static double blatantBlocksPerTick = 0.55D;
    /** Sprint-jump burst only in air / takeoff (vanilla peak ~0.6–0.7). */
    public static double jumpBurstMaxBlocksPerTick = Strafe2b2tModifications.VANILLA_BURST_HORIZONTAL_CAP;
    public static int jumpBurstGraceTicks = 20;

    private Speed2b2tModifications() {
    }

    public static void reload(ConfigManager config) {
        enabled = config.getBooleanElse(PREFIX + "enabled", true);
        maxSustainedBlocksPerTick = config.getDoubleElse(
                PREFIX + "max-sustained-blocks-per-tick",
                MovementLimits2b2tModifications.KMH_30_BLOCKS_PER_TICK
        );
        maxPacketBlocksPerTick = config.getDoubleElse(PREFIX + "max-packet-blocks-per-tick", 0.435D);
        maxTickCumulativeBlocks = config.getDoubleElse(PREFIX + "max-tick-cumulative-blocks", 0.65D);
        blatantBlocksPerTick = config.getDoubleElse(PREFIX + "blatant-blocks-per-tick", 0.55D);
        jumpBurstMaxBlocksPerTick = config.getDoubleElse(
                PREFIX + "jump-burst-max-blocks-per-tick",
                Strafe2b2tModifications.VANILLA_BURST_HORIZONTAL_CAP
        );
        jumpBurstGraceTicks = Math.max(1, config.getIntElse(PREFIX + "jump-burst-grace-ticks", 20));
    }

    public static void onServerTickStart(GrimPlayer player) {
        if (!enabled) {
            return;
        }
        PacketStateData data = player.packetStateData;
        data.speed2b2tServerTickId = GrimAPI.INSTANCE.getTickManager().currentTick;
        data.speed2b2tTickLocked = false;
        data.speed2b2tTickStartX = player.x;
        data.speed2b2tTickStartY = player.y;
        data.speed2b2tTickStartZ = player.z;
        data.speed2b2tTickStartYaw = player.yaw;
        data.speed2b2tTickStartPitch = player.pitch;
        data.hasSpeed2b2tTickStart = true;
    }

    private static void ensureServerTickAligned(GrimPlayer player) {
        int tick = GrimAPI.INSTANCE.getTickManager().currentTick;
        if (player.packetStateData.speed2b2tServerTickId != tick) {
            onServerTickStart(player);
        }
    }

    public static boolean hasSpeedExempt(GrimPlayer player) {
        if (player.inVehicle() || player.isFlying || player.isGliding || player.isInBed) {
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
        if (Movement2b2tModifications.hasActiveFireworkBoost(player)) {
            return true;
        }
        return false;
    }

    /**
     * Narrow sprint-jump burst — not ground sprint, not post-grace air strafe.
     */
    public static boolean isLegitSprintJumpBurst(
            GrimPlayer player,
            double deltaY,
            boolean packetOnGround,
            double packetHoriz
    ) {
        if (packetHoriz > jumpBurstMaxBlocksPerTick) {
            return false;
        }
        if (packetOnGround) {
            return MovementLimits2b2tModifications.isJumpTakeoffMovement(player, deltaY, true);
        }
        if (player.packetStateData.consecutiveAirTicks > jumpBurstGraceTicks
                && player.packetStateData.ticksSinceOnGround > jumpBurstGraceTicks) {
            return false;
        }
        return MovementLimits2b2tModifications.isJumpTakeoffMovement(player, deltaY, false)
                || player.packetStateData.airMomentumHorizLimit > maxSustainedBlocksPerTick
                || player.packetStateData.consecutiveAirTicks <= jumpBurstGraceTicks;
    }

    public static void tickSpeedBuffers(
            GrimPlayer player,
            boolean packetOnGround,
            double packetHoriz,
            double deltaY
    ) {
        if (MovementLimits2b2tModifications.isInJumpSpeedGrace(player, deltaY, packetOnGround)) {
            player.packetStateData.consecutiveGroundOverspeedTicks = 0;
            player.packetStateData.consecutiveAirOverspeedTicks = 0;
            return;
        }
        double sustainedCap = maxSustainedBlocksPerTick + 0.008D;
        if (packetOnGround) {
            if (packetHoriz > sustainedCap) {
                player.packetStateData.consecutiveGroundOverspeedTicks++;
            } else {
                player.packetStateData.consecutiveGroundOverspeedTicks = 0;
            }
            player.packetStateData.consecutiveAirOverspeedTicks = 0;
            return;
        }
        if (packetHoriz > sustainedCap) {
            player.packetStateData.consecutiveAirOverspeedTicks++;
        } else {
            player.packetStateData.consecutiveAirOverspeedTicks = 0;
        }
    }

    private static double horizFromTickStart(PacketStateData data, double toX, double toZ) {
        if (!data.hasSpeed2b2tTickStart) {
            return 0;
        }
        return Math.hypot(toX - data.speed2b2tTickStartX, toZ - data.speed2b2tTickStartZ);
    }

    /**
     * Packet-time block (like fly rollback) — called before position apply.
     */
    public static boolean tryBlockSpeedPacket(
            GrimPlayer player,
            double fromX,
            double fromY,
            double fromZ,
            Vector3d packetPosition,
            boolean packetOnGround,
            PacketReceiveEvent event
    ) {
        if (!enabled || !Strafe2b2tModifications.shouldEvaluateOnFoot(player, true, false)) {
            return false;
        }
        if (hasSpeedExempt(player)) {
            return false;
        }

        double deltaY = packetPosition.getY() - fromY;
        double packetHoriz = Math.hypot(packetPosition.getX() - fromX, packetPosition.getZ() - fromZ);

        if (packetOnGround
                && MovementLimits2b2tModifications.isVanillaGroundStrafe(player, packetHoriz, true)) {
            ensureServerTickAligned(player);
            tickSpeedBuffers(player, packetOnGround, packetHoriz, deltaY);
            return false;
        }

        ensureServerTickAligned(player);
        PacketStateData data = player.packetStateData;

        if (isLegitSprintJumpBurst(player, deltaY, packetOnGround, packetHoriz)) {
            if (MovementLimits2b2tModifications.isJumpTakeoffMovement(player, deltaY, packetOnGround)) {
                data.speed2b2tTickStartX = fromX;
                data.speed2b2tTickStartY = fromY;
                data.speed2b2tTickStartZ = fromZ;
                data.speed2b2tTickLocked = false;
                data.hasSpeed2b2tTickStart = true;
            }
            tickSpeedBuffers(player, packetOnGround, packetHoriz, deltaY);
            return false;
        }

        double tickHoriz = horizFromTickStart(data, packetPosition.getX(), packetPosition.getZ());
        tickSpeedBuffers(player, packetOnGround, packetHoriz, deltaY);

        if (data.speed2b2tTickLocked) {
            return cancelSpeedPacket(player, event, "speed_locked", true);
        }

        boolean blatant = packetHoriz > blatantBlocksPerTick || tickHoriz > blatantBlocksPerTick;
        boolean packetOver = packetHoriz > maxPacketBlocksPerTick;
        boolean tickOver = tickHoriz > maxTickCumulativeBlocks;

        if (blatant || packetOver || tickOver) {
            data.speed2b2tTickLocked = true;
            String reason = blatant ? "speed_blatant" : (packetOver ? "speed_packet" : "speed_tick_sum");
            return cancelSpeedPacket(
                    player,
                    event,
                    reason + " h=" + String.format("%.3f", Math.max(packetHoriz, tickHoriz)),
                    true
            );
        }

        return false;
    }

    private static boolean cancelSpeedPacket(
            GrimPlayer player,
            PacketReceiveEvent event,
            String verbose,
            boolean setback
    ) {
        SpeedLimits2b2t check = player.checkManager.getPostPredictionCheck(SpeedLimits2b2t.class);
        if (check != null) {
            check.flagAndAlert(verbose);
        }
        if (setback) {
            rollbackSpeed(player);
            hardStopHorizontalMovement(player);
        }
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

    public static void rollbackSpeed(GrimPlayer player) {
        PacketStateData data = player.packetStateData;
        double x = data.hasSpeed2b2tTickStart ? data.speed2b2tTickStartX : player.lastX;
        double y = data.hasSpeed2b2tTickStart ? data.speed2b2tTickStartY : player.lastY;
        double z = data.hasSpeed2b2tTickStart ? data.speed2b2tTickStartZ : player.lastZ;
        float yaw = data.hasSpeed2b2tTickStart ? data.speed2b2tTickStartYaw : player.yaw;
        float pitch = data.hasSpeed2b2tTickStart ? data.speed2b2tTickStartPitch : player.pitch;

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
        data.speed2b2tTickLocked = false;
        data.consecutiveGroundOverspeedTicks = 0;
        data.consecutiveAirOverspeedTicks = 0;
        player.getSetbackTeleportUtil().executeNonSimulatingForceResync();
    }

    /**
     * Post-prediction sustained speed (mirrors {@link Fly2b2tModifications#shouldBlockSurvivalFlight}).
     */
    public static boolean shouldBlockSustainedSpeed(
            GrimPlayer player,
            boolean onGround,
            double deltaY,
            double horizPerTick
    ) {
        if (!enabled || !player.packetStateData.didLastMovementIncludePosition) {
            return false;
        }
        if (hasSpeedExempt(player)) {
            return false;
        }
        if (isLegitSprintJumpBurst(player, deltaY, onGround, horizPerTick)) {
            return false;
        }

        if (onGround && MovementLimits2b2tModifications.isVanillaGroundStrafe(player, horizPerTick, true)) {
            return false;
        }

        double sustainedCap = maxSustainedBlocksPerTick + 0.008D;

        if (horizPerTick > blatantBlocksPerTick) {
            return true;
        }

        if (onGround) {
            if (MovementLimits2b2tModifications.isInJumpSpeedGrace(player, deltaY, true)) {
                return horizPerTick > blatantBlocksPerTick;
            }
            if (horizPerTick > sustainedCap) {
                return true;
            }
            return player.packetStateData.consecutiveGroundOverspeedTicks >= 3;
        }

        if (player.packetStateData.ticksSinceOnGround > jumpBurstGraceTicks
                && horizPerTick > sustainedCap) {
            return true;
        }

        return player.packetStateData.consecutiveAirOverspeedTicks >= 2
                && horizPerTick > sustainedCap * 0.95D;
    }

    public static String formatVerdict(double horiz, boolean onGround) {
        return "speed h=" + String.format("%.3f", horiz) + " g=" + onGround;
    }
}
