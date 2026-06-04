package ac.grim.grimac.checks.impl.breaking;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.BlockBreakCheck;
import ac.grim.grimac.modifications.Breaking2b2tModifications;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.BlockBreak;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;

@CheckData(name = "SpeedMineLimits2b2t", stableKey = "grim.breaking.speed_mine_limits_2b2t",
        description = "Blocks Shoreline SpeedMine instant rebreak (NORMAL/GRIM packet sequences)")
public class SpeedMineLimits2b2t extends Check implements BlockBreakCheck {

    public SpeedMineLimits2b2t(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onBlockBreak(BlockBreak blockBreak) {
        if (!Breaking2b2tModifications.enabled) {
            return;
        }
        if (blockBreak.action != DiggingAction.START_DIGGING
                && blockBreak.action != DiggingAction.FINISHED_DIGGING
                && blockBreak.action != DiggingAction.CANCELLED_DIGGING) {
            return;
        }

        Breaking2b2tModifications.DigVerdict verdict = Breaking2b2tModifications.evaluateDig(player, blockBreak);
        if (verdict == Breaking2b2tModifications.DigVerdict.ALLOW) {
            reward();
            return;
        }

        flagAndAlert(Breaking2b2tModifications.formatVerdict(verdict, blockBreak.position));
        if (shouldModifyPackets()) {
            blockBreak.cancel();
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (isTickPacket(event.getPacketType())) {
            Breaking2b2tModifications.onTickStart(player);
        }
    }
}
