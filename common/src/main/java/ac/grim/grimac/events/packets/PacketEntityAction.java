package ac.grim.grimac.events.packets;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.checks.impl.elytra.ElytraA;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.data.IntToObjectPair;
import ac.grim.grimac.utils.data.SprintingState;
import ac.grim.grimac.utils.data.packetentity.JumpableEntity;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;

public class PacketEntityAction extends PacketListenerAbstract {

    public PacketEntityAction() {
        super(PacketListenerPriority.LOW);
    }

    @Override
    public boolean isPreVia() {
        return true;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.ENTITY_ACTION) {
            WrapperPlayClientEntityAction action = new WrapperPlayClientEntityAction(event);
            GrimPlayer player = GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(event.getUser());

            if (player == null) return;

            switch (action.getAction()) {
                case START_SPRINTING -> {
                    player.isSprinting = true;
                    player.vehicleData.camelSprintingState = SprintingState.STARTED;
                }
                case STOP_SPRINTING -> {
                    player.isSprinting = false;
                    player.vehicleData.camelSprintingState = SprintingState.STOPPED;
                }
                case START_SNEAKING -> player.isSneaking = true;
                case STOP_SNEAKING -> player.isSneaking = false;
                case START_FLYING_WITH_ELYTRA -> {
                    handleStartGliding(player, event);
                }
                case START_JUMPING_WITH_HORSE -> {
                    PacketEntity riding = player.compensatedEntities.self.getRiding();
                    if (riding instanceof JumpableEntity jumpable) {
                        if (player.vehicleData.pendingJumps.size() >= 20) return; // discard
                        player.vehicleData.pendingJumps.add(new IntToObjectPair<>(action.getJumpBoost(), jumpable));
                    }
                }
            }
        }
    }

    static void handleStartGliding(GrimPlayer player, PacketReceiveEvent event) {
        player.packetStateData.lastElytraStartWasDuplicate = false;
        player.packetStateData.lastElytraStartWasContinuation = false;
        if (PacketEvents.getAPI().getServerManager().getVersion().isOlderThan(ServerVersion.V_1_9))
            return;

        if (player.isGliding && player.canGlide() && !player.inVehicle() && !player.isFlying
                && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_15)) {
            player.packetStateData.lastElytraStartWasDuplicate = true;
            return;
        }

        if (player.onGround) {
            player.getSetbackTeleportUtil().executeNonSimulatingForceResync();
            player.resyncGlidingState();
            event.setCancelled(true);
            player.onPacketCancel();
            return;
        }

        player.checkManager.get(ElytraA.class).onStartGliding(event);

        // Starting fall flying is server sided on 1.14 and below
        if (player.getClientVersion().isOlderThan(ClientVersion.V_1_15)) return;

        // This shouldn't be needed with latency compensated inventories
        // TODO: Remove this?
        if (player.canGlide()) {
            player.packetStateData.lastElytraStartWasContinuation = player.wasGliding
                    && !player.clientClaimsLastOnGround && !player.inVehicle() && !player.isFlying;
            player.isGliding = true;
            player.pointThreeEstimator.updatePlayerGliding();
        } else {
            // A client is flying with a ghost elytra, resync
            player.getSetbackTeleportUtil().executeNonSimulatingForceResync();
            player.resyncGlidingState();
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }
}
