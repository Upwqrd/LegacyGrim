package ac.grim.grimac.modifications;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.BlockBreak;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.util.Vector3i;

/**
 * 2b2t.org.ru fork: blocks Shoreline SpeedMine (NORMAL/GRIM/GRIM_V3) instant rebreak sequences.
 */
public final class Breaking2b2tModifications {

    private static final String PREFIX = "Breaking2b2t.";

    public static boolean enabled = true;
    public static int maxDigPacketsPerTick = 5;
    /** Ticks before the same block can be STARTed again after FINISH (anti instant remine). */
    public static int rebreakCooldownTicks = 8;
    /** Minimum server ticks between START and FINISH (vanilla uses 1+). */
    public static int minBreakTicks = 1;
    /** Applied only when multiple dig packets land in the same server tick (SpeedMine spam). */
    public static int minBreakMsSameTick = 250;

    private Breaking2b2tModifications() {
    }

    public enum DigVerdict {
        ALLOW,
        DUPLICATE_FINISH,
        NO_START_SESSION,
        INSTANT_REBREAK,
        DIG_PACKET_SPAM,
        FINISH_WITHOUT_START,
        TOO_FAST_FINISH,
        ILLEGAL_DIG_SEQUENCE
    }

    public static void reload(ConfigManager config) {
        enabled = config.getBooleanElse(PREFIX + "enabled", true);
        maxDigPacketsPerTick = Math.max(1, config.getIntElse(PREFIX + "max-dig-packets-per-tick", 5));
        rebreakCooldownTicks = Math.max(1, config.getIntElse(PREFIX + "rebreak-cooldown-ticks", 8));
        minBreakTicks = Math.max(0, config.getIntElse(PREFIX + "min-break-ticks", 1));
        minBreakMsSameTick = Math.max(0, config.getIntElse(PREFIX + "min-break-ms-same-tick", 250));
    }

    public static void onTickStart(GrimPlayer player) {
        resetDigPacketsThisTick(player);
    }

    private static void resetDigPacketsThisTick(GrimPlayer player) {
        player.packetStateData.breakDigPacketsThisTick = 0;
        player.packetStateData.breakFinishCountThisTick = 0;
        player.packetStateData.breakFinishPositionThisTick = null;
    }

    private static void ensureDigRateLimitTick(GrimPlayer player) {
        int tick = GrimAPI.INSTANCE.getTickManager().currentTick;
        if (player.packetStateData.breakDigRateLimitTickId != tick) {
            player.packetStateData.breakDigRateLimitTickId = tick;
            resetDigPacketsThisTick(player);
        }
    }

    public static DigVerdict evaluateDig(GrimPlayer player, BlockBreak blockBreak) {
        if (!enabled) {
            return DigVerdict.ALLOW;
        }

        ensureDigRateLimitTick(player);
        player.packetStateData.breakDigPacketsThisTick++;

        if (player.packetStateData.breakDigPacketsThisTick > maxDigPacketsPerTick) {
            return DigVerdict.DIG_PACKET_SPAM;
        }

        Vector3i pos = blockBreak.position;
        DiggingAction action = blockBreak.action;
        int tick = GrimAPI.INSTANCE.getTickManager().currentTick;

        if (action == DiggingAction.START_DIGGING) {
            return evaluateStart(player, pos, tick);
        }

        if (action == DiggingAction.CANCELLED_DIGGING) {
            if (pos.equals(player.packetStateData.activeBreakPosition)) {
                player.packetStateData.cancelledBreakPosition = pos;
                clearActiveBreakSession(player);
            }
            return DigVerdict.ALLOW;
        }

        if (action == DiggingAction.FINISHED_DIGGING) {
            return evaluateFinish(player, pos, tick);
        }

        return DigVerdict.ALLOW;
    }

    private static DigVerdict evaluateStart(GrimPlayer player, Vector3i pos, int tick) {
        if (isInstantRebreakAttempt(player, pos, tick)) {
            return DigVerdict.INSTANT_REBREAK;
        }

        if (player.packetStateData.breakStartAcknowledged
                && pos.equals(player.packetStateData.activeBreakPosition)
                && !player.packetStateData.breakFinishUsed) {
            return DigVerdict.DIG_PACKET_SPAM;
        }

        player.packetStateData.activeBreakPosition = pos;
        player.packetStateData.breakStartAcknowledged = true;
        player.packetStateData.breakFinishUsed = false;
        player.packetStateData.breakStartTick = tick;
        player.packetStateData.breakStartTimeMs = System.currentTimeMillis();
        player.packetStateData.cancelledBreakPosition = null;
        return DigVerdict.ALLOW;
    }

    private static DigVerdict evaluateFinish(GrimPlayer player, Vector3i pos, int tick) {
        if (player.packetStateData.breakFinishPositionThisTick != null
                && player.packetStateData.breakFinishPositionThisTick.equals(pos)) {
            player.packetStateData.breakFinishCountThisTick++;
            if (player.packetStateData.breakFinishCountThisTick > 1) {
                clearActiveBreakSession(player);
                return DigVerdict.DUPLICATE_FINISH;
            }
        } else {
            player.packetStateData.breakFinishPositionThisTick = pos;
            player.packetStateData.breakFinishCountThisTick = 1;
        }

        if (!player.packetStateData.breakStartAcknowledged) {
            if (pos.equals(player.packetStateData.cancelledBreakPosition)) {
                return DigVerdict.ILLEGAL_DIG_SEQUENCE;
            }
            return DigVerdict.FINISH_WITHOUT_START;
        }

        if (!pos.equals(player.packetStateData.activeBreakPosition)) {
            clearActiveBreakSession(player);
            return DigVerdict.NO_START_SESSION;
        }

        if (player.packetStateData.breakFinishUsed) {
            clearActiveBreakSession(player);
            return DigVerdict.DUPLICATE_FINISH;
        }

        int elapsedTicks = tick - player.packetStateData.breakStartTick;
        long elapsedMs = System.currentTimeMillis() - player.packetStateData.breakStartTimeMs;
        boolean speedMineSameTick = player.packetStateData.breakDigPacketsThisTick >= 3;
        if (elapsedTicks < minBreakTicks
                || (speedMineSameTick && elapsedMs < minBreakMsSameTick)) {
            clearActiveBreakSession(player);
            return DigVerdict.TOO_FAST_FINISH;
        }

        player.packetStateData.breakFinishUsed = true;
        clearActiveBreakSession(player);
        player.packetStateData.lastCompletedBreakPosition = pos;
        player.packetStateData.lastCompletedBreakTick = tick;
        return DigVerdict.ALLOW;
    }

    private static boolean isInstantRebreakAttempt(GrimPlayer player, Vector3i pos, int tick) {
        if (player.packetStateData.lastCompletedBreakPosition != null
                && player.packetStateData.lastCompletedBreakPosition.equals(pos)) {
            int since = tick - player.packetStateData.lastCompletedBreakTick;
            if (since >= 0 && since < rebreakCooldownTicks) {
                return true;
            }
        }
        return false;
    }

    private static void clearActiveBreakSession(GrimPlayer player) {
        player.packetStateData.breakStartAcknowledged = false;
        player.packetStateData.breakFinishUsed = false;
        player.packetStateData.activeBreakPosition = null;
        player.packetStateData.breakStartTick = Integer.MIN_VALUE;
        player.packetStateData.breakStartTimeMs = 0L;
    }

    public static boolean shouldCancelDig(GrimPlayer player, DigVerdict verdict) {
        return verdict != DigVerdict.ALLOW;
    }

    public static String formatVerdict(DigVerdict verdict, Vector3i pos) {
        return verdict.name().toLowerCase() + " pos=" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
