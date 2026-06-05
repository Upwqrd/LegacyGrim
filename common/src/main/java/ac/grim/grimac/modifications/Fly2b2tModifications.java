package ac.grim.grimac.modifications;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.potion.PotionTypes;
import ac.grim.grimac.modifications.Strafe2b2tModifications;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import com.github.retrooper.packetevents.util.Vector3d;

/**
 * 2b2t.org.ru fork: strict survival vertical limits (Flight / HighJump).
 * Elytra + active firework momentum exempt only — not blanket glide coast.
 */
public final class Fly2b2tModifications {

    private static final String PREFIX = "Fly2b2t.";

    public static boolean enabled = false;
    public static int jumpGraceAirTicks = 20;
    public static int maxHoverAirTicks = 3;
    public static int maxAscendAirTicks = 2;
    public static double hoverDeltaYEpsilon = 0.006D;
    public static double ascendDeltaYMin = 0.028D;
    public static double blatantAscendDeltaY = 0.075D;
    public static double maxAirAscendPerTick = 0.42D;
    public static double maxBoostAirAscendPerTick = 0.56D;
    public static double minMomentumHorizForElytraExempt = 0.15D;
    public static double minMomentumDyForElytraExempt = 0.12D;
    public static int maxSlowFallAirTicks = 22;

    private Fly2b2tModifications() {
    }

    public static void reload(ConfigManager config) {
        enabled = config.getBooleanElse(PREFIX + "enabled", false);
        jumpGraceAirTicks = Math.max(0, config.getIntElse(PREFIX + "jump-grace-air-ticks", 20));
        maxHoverAirTicks = Math.max(1, config.getIntElse(PREFIX + "max-hover-air-ticks", 3));
        maxAscendAirTicks = Math.max(1, config.getIntElse(PREFIX + "max-ascend-air-ticks", 2));
        hoverDeltaYEpsilon = config.getDoubleElse(PREFIX + "hover-delta-y-epsilon", 0.006D);
        ascendDeltaYMin = config.getDoubleElse(PREFIX + "ascend-delta-y-min", 0.028D);
        blatantAscendDeltaY = config.getDoubleElse(PREFIX + "blatant-ascend-delta-y", 0.075D);
        maxAirAscendPerTick = config.getDoubleElse(PREFIX + "max-air-ascend-per-tick", 0.42D);
        maxBoostAirAscendPerTick = config.getDoubleElse(PREFIX + "max-boost-air-ascend-per-tick", 0.56D);
        minMomentumHorizForElytraExempt = config.getDoubleElse(PREFIX + "min-momentum-horiz-for-elytra-exempt", 0.08D);
        minMomentumDyForElytraExempt = config.getDoubleElse(PREFIX + "min-momentum-dy-for-elytra-exempt", 0.06D);
        maxSlowFallAirTicks = Math.max(1, config.getIntElse(PREFIX + "max-slow-fall-air-ticks", 22));
    }

    public static boolean canUseServerFlight(GrimPlayer player) {
        if (player.gamemode == GameMode.CREATIVE || player.gamemode == GameMode.SPECTATOR) {
            return true;
        }
        return player.canFly || player.isFlying;
    }

    /**
     * Active firework on elytra, or glide-end momentum burst (chestplate swap while boosting).
     */
    public static boolean shouldExemptElytraFireworkMomentum(GrimPlayer player, double deltaY, double horiz) {
        if (player.isGliding) {
            return true;
        }
        if (Movement2b2tModifications.hasActiveFireworkBoost(player)) {
            return true;
        }
        if (!Movement2b2tModifications.hasGlideEndCoast(player)) {
            return false;
        }
        return horiz >= minMomentumHorizForElytraExempt
                || deltaY >= minMomentumDyForElytraExempt
                || deltaY <= -0.04D;
    }

    public static double getMaxAllowedAirAscend(GrimPlayer player) {
        if (player.compensatedEntities.self.hasPotionEffect(PotionTypes.JUMP_BOOST)) {
            return maxBoostAirAscendPerTick;
        }
        return maxAirAscendPerTick;
    }

    public static void tickFlyBuffers(GrimPlayer player, boolean packetOnGround, double deltaY) {
        if (packetOnGround) {
            if (!MovementLimits2b2tModifications.isJumpTakeoffMovement(player, deltaY, true)) {
                player.packetStateData.consecutiveAirTicks = 0;
                player.packetStateData.ticksSinceOnGround = 0;
                player.packetStateData.consecutiveHoverAirTicks = 0;
                player.packetStateData.consecutiveAscendAirTicks = 0;
                player.packetStateData.consecutiveStrictAirAscendTicks = 0;
            }
            return;
        }

        player.packetStateData.consecutiveAirTicks++;
        player.packetStateData.ticksSinceOnGround++;

        if (Math.abs(deltaY) < hoverDeltaYEpsilon) {
            player.packetStateData.consecutiveHoverAirTicks++;
        } else {
            player.packetStateData.consecutiveHoverAirTicks = 0;
        }

        if (deltaY > ascendDeltaYMin) {
            player.packetStateData.consecutiveAscendAirTicks++;
        } else {
            player.packetStateData.consecutiveAscendAirTicks = 0;
        }

        if (deltaY > getMaxAllowedAirAscend(player) * 0.85D) {
            player.packetStateData.consecutiveStrictAirAscendTicks++;
        } else if (deltaY < 0.01D) {
            player.packetStateData.consecutiveStrictAirAscendTicks = 0;
        }
    }

    public static boolean hasLegitFlightExempt(GrimPlayer player, double deltaY, double horiz) {
        if (shouldExemptElytraFireworkMomentum(player, deltaY, horiz)) {
            return true;
        }
        if (player.inVehicle() || player.isFlying || player.canFly) {
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
        if (player.packetStateData.ticksSinceOnGround <= jumpGraceAirTicks && deltaY > 0) {
            if (Spider2b2tModifications.isSlowWallClimbPattern(player, deltaY)) {
                return false;
            }
            return true;
        }
        if (player.packetStateData.fallBufferTicks > 0) {
            return true;
        }
        if (MovementLimits2b2tModifications.isLegitOnFootAirMovement(player, deltaY, false)) {
            return true;
        }
        if (MovementLimits2b2tModifications.isInJumpSpeedGrace(player, deltaY, false)) {
            return true;
        }
        if (player.isJumping || player.clientVelocity.getY() > 0.08D) {
            return true;
        }
        // New: Exempt the moments when elytra is being equipped or unequipped (glide start/stop)
        if (player.wasGliding && !player.isGliding) {
            return true;
        }
        if (!player.wasGliding && player.isGliding) {
            return true;
        }
        return false;
    }

    public static boolean shouldBlockHighJump(GrimPlayer player, boolean packetOnGround, double deltaY, double horiz) {
        if (packetOnGround || canUseServerFlight(player)) {
            return false;
        }
        if (shouldExemptElytraFireworkMomentum(player, deltaY, horiz)) {
            return false;
        }
        if (hasLegitFlightExempt(player, deltaY, horiz)) {
            return false;
        }

        double maxAscend = getMaxAllowedAirAscend(player);
        if (player.packetStateData.ticksSinceOnGround > jumpGraceAirTicks && deltaY > maxAscend) {
            return true;
        }
        if (player.packetStateData.consecutiveStrictAirAscendTicks >= maxAscendAirTicks) {
            return true;
        }
        return false;
    }

    public static boolean shouldBlockSurvivalFlight(
            GrimPlayer player,
            boolean packetOnGround,
            double deltaY,
            double horizPerTick
    ) {
        if (!enabled) {
            return false;
        }
        if (!player.packetStateData.didLastMovementIncludePosition) {
            return false;
        }
        if (canUseServerFlight(player) || packetOnGround) {
            return false;
        }
        if (shouldExemptElytraFireworkMomentum(player, deltaY, horizPerTick)) {
            return false;
        }

        // After applying buffers, enforce a strict vertical ascent limit.
        if (deltaY > getMaxAllowedAirAscend(player)) {
            return true;
        }
        if (!hasLegitFlightExempt(player, deltaY, horizPerTick)) {
            if (shouldBlockHighJump(player, packetOnGround, deltaY, horizPerTick)) {
                return true;
            }

            if (deltaY > blatantAscendDeltaY && player.packetStateData.ticksSinceOnGround > jumpGraceAirTicks) {
                return true;
            }

            if (player.packetStateData.consecutiveHoverAirTicks >= maxHoverAirTicks) {
                return true;
            }

            if (player.packetStateData.consecutiveAscendAirTicks >= maxAscendAirTicks) {
                return true;
            }

            if (player.packetStateData.consecutiveAirTicks >= maxSlowFallAirTicks
                    && deltaY > -0.04D
                    && deltaY < hoverDeltaYEpsilon * 2
                    && player.clientVelocity.getY() > -0.15D) {
                return true;
            }

            if (player.packetStateData.consecutiveAirTicks >= 28
                    && deltaY > -0.05D
                    && player.clientVelocity.getY() > -0.1D) {
                return true;
            }
        }

        return false;
    }

    public static void rollbackFlight(GrimPlayer player, double x, double y, double z) {
        // Use Strafe rollback and also zero vertical velocity, sending a packet to client.
        Strafe2b2tModifications.rollbackToExactPosition(player, x, y, z, player.yaw, player.pitch);
        // Ensure velocity is cleared and client receives zero velocity packet.
        player.clientVelocity.setY(0);
        if (player.user != null) {
            player.user.writePacket(new WrapperPlayServerEntityVelocity(player.entityID,
                    new Vector3d(0, 0, 0)));
        }
    }

    public static String formatVerdict(double horiz, double deltaY, int airTicks) {
        return "survival_fly h=" + String.format("%.3f", horiz)
                + " dy=" + String.format("%.3f", deltaY)
                + " air=" + airTicks;
    }

    public static String formatHighJumpVerdict(double deltaY, int ascendTicks) {
        return "highjump dy=" + String.format("%.3f", deltaY) + " ascend=" + ascendTicks;
    }
}
