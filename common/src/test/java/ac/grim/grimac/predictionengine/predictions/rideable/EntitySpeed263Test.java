package ac.grim.grimac.predictionengine.predictions.rideable;

import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.data.VehicleData;
import ac.grim.grimac.utils.enums.BoatEntityStatus;
import ac.grim.grimac.utils.math.Vector3dm;
import ac.grim.grimac.utils.nmsutil.StuckSpeed;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Regression coverage for the unchanged EntitySpeed policy shipped in the 26.3 branch. */
class EntitySpeed263Test {
    @Test
    void diagonalSpeedAndStuckSlowdownKeepTheOriginalLimits() {
        for (var stuck : new ac.grim.grimac.utils.data.IndexedVector3d[]{
                StuckSpeed.NONE, StuckSpeed.COBWEB, StuckSpeed.SWEET_BERRY_BUSH}) {
            var physics = new Vector3dm(0.1, -0.4, 0.2);
            var requested = new Vector3dm(10, 5, -10);
            var result = EntitySpeedAllowance.horizontalMovement(physics, requested, stuck);
            double limit = (60.0 / 72.0) * Math.min(stuck.getX(), stuck.getZ());
            assertEquals(limit, Math.hypot(result.getX(), result.getZ()), 1e-12);
            assertEquals(physics.getY(), result.getY());
            assertEquals(10, requested.getX());
            assertEquals(0.1, physics.getX());
        }
    }

    @Test
    void flightAndUnsupportedVehiclesDoNotAcquireEntitySpeedPermission() {
        assertTrue(EntitySpeedAllowance.supports(EntityTypes.OAK_BOAT));
        assertTrue(EntitySpeedAllowance.supports(EntityTypes.OAK_CHEST_BOAT));
        assertTrue(EntitySpeedAllowance.supports(EntityTypes.BAMBOO_RAFT));
        assertTrue(EntitySpeedAllowance.supports(EntityTypes.HORSE));
        assertTrue(EntitySpeedAllowance.supports(EntityTypes.PIG));
        assertTrue(EntitySpeedAllowance.supports(EntityTypes.STRIDER));
        assertFalse(EntitySpeedAllowance.supports(EntityTypes.HAPPY_GHAST));
        assertFalse(EntitySpeedAllowance.supports(EntityTypes.MINECART));
        assertFalse(EntitySpeedAllowance.supports(EntityTypes.LLAMA));
        assertFalse(EntitySpeedAllowance.supports(EntityTypes.NAUTILUS));
    }

    @Test
    void airborneBoatKeepsAccumulatingGravityDespiteHoverAndAscentRequests() throws Exception {
        var player = mock(GrimPlayer.class);
        var data = new VehicleData();
        data.oldStatus = data.status = BoatEntityStatus.IN_AIR;
        var field = GrimPlayer.class.getField("vehicleData");
        field.setAccessible(true);
        field.set(player, data);
        player.hasGravity = true;
        var engine = mock(PredictionEngineBoat.class);
        var floatBoat = PredictionEngineBoat.class.getDeclaredMethod("floatBoat", GrimPlayer.class, Vector3dm.class);
        floatBoat.setAccessible(true);
        var physics = new Vector3dm();
        for (int tick = 0; tick < 200; tick++) {
            double previousY = physics.getY();
            floatBoat.invoke(engine, player, physics);
            assertTrue(physics.getY() < previousY);
            for (double requestedY : new double[]{0, 0.3, -0.0313}) {
                var result = EntitySpeedAllowance.horizontalMovement(physics,
                        new Vector3dm(0.5, requestedY, 0), StuckSpeed.NONE);
                assertEquals(physics.getY(), result.getY());
            }
        }
    }
}
