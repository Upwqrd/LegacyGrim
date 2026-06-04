package ac.grim.grimac.manager.tick.impl;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.manager.tick.Tickable;
import ac.grim.grimac.modifications.Speed2b2tModifications;
import ac.grim.grimac.player.GrimPlayer;

public class ResetTick implements Tickable {
    @Override
    public void tick() {
        for (GrimPlayer player : GrimAPI.INSTANCE.getPlayerDataManager().getEntries()) {
            player.checkManager.getPacketEntityReplication().tickStartTick();
            Speed2b2tModifications.onServerTickStart(player);
        }
    }
}
