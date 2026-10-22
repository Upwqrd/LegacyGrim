package ac.grim.grimac.predictionengine.predictions.rideable;

import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.data.IndexedVector3d;
import ac.grim.grimac.utils.data.VectorData;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import ac.grim.grimac.utils.data.packetentity.PacketEntityHorse;
import ac.grim.grimac.utils.data.packetentity.PacketEntityRideable;
import ac.grim.grimac.utils.math.Vector3dm;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.item.type.ItemType;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;

import java.util.List;

public final class EntitySpeedAllowance {
    public static final double MAX_BLOCKS_PER_TICK = 60.0 / 72.0;

    private EntitySpeedAllowance() {
    }

    static boolean supports(EntityType type) {
        return EntityTypes.isTypeInstanceOf(type, EntityTypes.BOAT)
                || type == EntityTypes.HORSE || type == EntityTypes.DONKEY
                || type == EntityTypes.MULE || type == EntityTypes.SKELETON_HORSE
                || type == EntityTypes.ZOMBIE_HORSE || type == EntityTypes.CAMEL
                || type == EntityTypes.PIG || type == EntityTypes.STRIDER;
    }

    public static void addCandidates(GrimPlayer player, List<VectorData> candidates) {
        PacketEntity riding = player.compensatedEntities.self.getRiding();
        if (riding == null || riding.isDead || !supports(riding.getType())
                || !canControl(player, riding)
                || !finite(player.actualMovement)) {
            return;
        }

        int vanillaCount = candidates.size();
        for (int i = 0; i < vanillaCount; i++) {
            VectorData vanilla = candidates.get(i);
            if (vanilla.isKnockback() || vanilla.isExplosion() || vanilla.isTrident()
                    || vanilla.isZeroPointZeroThree() || !finite(vanilla.vector)) {
                continue;
            }
            candidates.add(vanilla.returnNewModified(vanilla.vector.clone(), VectorData.VectorType.EntitySpeed));
        }
    }

    static boolean canControl(GrimPlayer player, PacketEntity riding) {
        if (riding.getRiding() != null || riding.passengers.isEmpty()
                || riding.passengers.get(0) != player.compensatedEntities.self) {
            return false;
        }
        if (riding instanceof PacketEntityHorse horse) {
            return horse.hasSaddle();
        }
        if (riding instanceof PacketEntityRideable animal) {
            boolean saddled = PacketEvents.getAPI().getServerManager().getVersion().isNewerThan(ServerVersion.V_1_21_4)
                    ? animal.hasItemInSlot(EquipmentSlot.SADDLE) : animal.hasSaddle;
            if (!saddled) return false;
            ItemType required = animal.getType() == EntityTypes.PIG
                    ? ItemTypes.CARROT_ON_A_STICK : ItemTypes.WARPED_FUNGUS_ON_A_STICK;
            return player.inventory.getHeldItem().getType() == required
                    || player.inventory.getOffHand().getType() == required;
        }
        return EntityTypes.isTypeInstanceOf(riding.getType(), EntityTypes.BOAT);
    }

    public static Vector3dm horizontalMovement(Vector3dm physics, Vector3dm requested, IndexedVector3d stuck) {
        double limit = MAX_BLOCKS_PER_TICK * Math.max(0, Math.min(1, Math.min(stuck.getX(), stuck.getZ())));
        if (!finite(physics) || !finite(requested) || !Double.isFinite(limit)) {
            return physics.clone();
        }
        double length = Math.hypot(requested.getX(), requested.getZ());
        double scale = length > limit ? limit / length : 1;
        return new Vector3dm(requested.getX() * scale, physics.getY(), requested.getZ() * scale);
    }

    private static boolean finite(Vector3dm vector) {
        return Double.isFinite(vector.getX()) && Double.isFinite(vector.getY()) && Double.isFinite(vector.getZ());
    }
}
