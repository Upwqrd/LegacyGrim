package ac.grim.grimac.modifications;

import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.BlockBreak;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.util.Vector3i;

/**
 * 2b2t.org.ru fork: one vanilla break session per block (blocks SpeedMine / instant rebreak).
 */
public final class Breaking2b2tModifications {

    public static final int MAX_DIG_PACKETS_PER_TICK = 4;

    private Breaking2b2tModifications() {
    }

    public enum DigVerdict {
        ALLOW,
        DUPLICATE_FINISH,
        NO_START_SESSION,
        INSTANT_REBREAK,
        DIG_PACKET_SPAM
    }

    public static void onTickStart(GrimPlayer player) {
        player.packetStateData.breakDigPacketsThisTick = 0;
    }

    public static DigVerdict evaluateDig(GrimPlayer player, BlockBreak blockBreak) {
        player.packetStateData.breakDigPacketsThisTick++;

        if (player.packetStateData.breakDigPacketsThisTick > MAX_DIG_PACKETS_PER_TICK) {
            return DigVerdict.DIG_PACKET_SPAM;
        }

        Vector3i pos = blockBreak.position;
        DiggingAction action = blockBreak.action;

        if (action == DiggingAction.START_DIGGING) {
            if (player.packetStateData.breakStartAcknowledged
                    && pos.equals(player.packetStateData.activeBreakPosition)
                    && !player.packetStateData.breakFinishUsed) {
                return DigVerdict.DIG_PACKET_SPAM;
            }
            if (player.packetStateData.breakFinishUsed && pos.equals(player.packetStateData.activeBreakPosition)) {
                return DigVerdict.INSTANT_REBREAK;
            }

            player.packetStateData.activeBreakPosition = pos;
            player.packetStateData.breakStartAcknowledged = true;
            player.packetStateData.breakFinishUsed = false;
            player.packetStateData.cancelledBreakPosition = null;
            return DigVerdict.ALLOW;
        }

        if (action == DiggingAction.CANCELLED_DIGGING) {
            if (pos.equals(player.packetStateData.activeBreakPosition)) {
                player.packetStateData.cancelledBreakPosition = pos;
                player.packetStateData.breakStartAcknowledged = false;
            }
            return DigVerdict.ALLOW;
        }

        if (action == DiggingAction.FINISHED_DIGGING) {
            if (player.packetStateData.breakFinishUsed && pos.equals(player.packetStateData.activeBreakPosition)) {
                return DigVerdict.DUPLICATE_FINISH;
            }

            if (!player.packetStateData.breakStartAcknowledged) {
                if (pos.equals(player.packetStateData.cancelledBreakPosition)) {
                    player.packetStateData.breakFinishUsed = true;
                    player.packetStateData.cancelledBreakPosition = null;
                    player.packetStateData.activeBreakPosition = null;
                    return DigVerdict.ALLOW;
                }
                return DigVerdict.NO_START_SESSION;
            }

            if (!pos.equals(player.packetStateData.activeBreakPosition)) {
                return DigVerdict.NO_START_SESSION;
            }

            player.packetStateData.breakFinishUsed = true;
            player.packetStateData.breakStartAcknowledged = false;
            player.packetStateData.activeBreakPosition = null;
            return DigVerdict.ALLOW;
        }

        return DigVerdict.ALLOW;
    }

    public static boolean shouldCancelDig(GrimPlayer player, DigVerdict verdict) {
        return verdict != DigVerdict.ALLOW;
    }

    public static String formatVerdict(DigVerdict verdict, Vector3i pos) {
        return verdict.name().toLowerCase() + " pos=" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
