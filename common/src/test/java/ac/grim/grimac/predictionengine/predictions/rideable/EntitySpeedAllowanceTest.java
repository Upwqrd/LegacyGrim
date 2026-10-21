package ac.grim.grimac.predictionengine.predictions.rideable;

import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.VectorData;
import ac.grim.grimac.utils.data.VelocityData;
import ac.grim.grimac.utils.data.VehicleData;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import ac.grim.grimac.utils.data.packetentity.PacketEntitySelf;
import ac.grim.grimac.utils.data.packetentity.PacketEntityHorse;
import ac.grim.grimac.utils.data.packetentity.PacketEntityRideable;
import ac.grim.grimac.utils.latency.CompensatedEntities;
import ac.grim.grimac.utils.latency.CompensatedInventory;
import ac.grim.grimac.utils.math.Vector3dm;
import ac.grim.grimac.utils.enums.BoatEntityStatus;
import ac.grim.grimac.utils.nmsutil.Collisions;
import ac.grim.grimac.utils.nmsutil.StuckSpeed;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.settings.PacketEventsSettings;
import com.github.retrooper.packetevents.util.LogManager;
import io.github.retrooper.packetevents.impl.netty.NettyManagerImpl;
import ac.grim.grimac.utils.nmsutil.Collisions.Axis;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EntitySpeedAllowanceTest {
    private static final double CAP = 60.0 / 72.0;

    @BeforeAll
    static void packetEvents() {
        PacketEventsAPI<?> api = mock(PacketEventsAPI.class);
        ServerManager server = mock(ServerManager.class);
        when(server.getVersion()).thenReturn(ServerVersion.V_1_21_11);
        when(api.getServerManager()).thenReturn(server);
        when(api.getSettings()).thenReturn(new PacketEventsSettings());
        when(api.getNettyManager()).thenReturn(new NettyManagerImpl());
        when(api.getLogManager()).thenReturn(mock(LogManager.class));
        PacketEvents.setAPI(api);
    }

    @Test
    void sixtyKmhIsNotSixtyBlocksPerSecond() {
        assertEquals(16.666666666666668, EntitySpeedAllowance.MAX_BLOCKS_PER_TICK * 20, 1e-12);
        assertEquals(60, EntitySpeedAllowance.MAX_BLOCKS_PER_TICK * 72, 1e-12);
    }

    @Test
    void acceptsAllSpeedsBelowLimitAndInstantTurns() {
        for (Vector3dm requested : List.of(new Vector3dm(), new Vector3dm(CAP, 100, 0),
                new Vector3dm(-0.2, -100, 0.3), new Vector3dm(0, 0, -CAP))) {
            Vector3dm result = movement(new Vector3dm(0, -0.04, 0), requested);
            assertEquals(requested.getX(), result.getX());
            assertEquals(requested.getZ(), result.getZ());
            assertEquals(-0.04, result.getY());
        }
    }

    @Test
    void diagonalAndHugeCoordinatesCannotExceedCircularLimit() {
        Random random = new Random(42);
        for (int i = 0; i < 10000; i++) {
            Vector3dm requested = new Vector3dm((random.nextDouble() - 0.5) * 1000, 0,
                    (random.nextDouble() - 0.5) * 1000);
            Vector3dm result = movement(new Vector3dm(0, -0.5, 0), requested);
            assertTrue(Math.hypot(result.getX(), result.getZ()) <= CAP + 1e-15);
        }
        Vector3dm huge = movement(new Vector3dm(), new Vector3dm(Double.MAX_VALUE, 0, Double.MAX_VALUE));
        assertTrue(Double.isFinite(huge.getX()));
        assertTrue(Math.hypot(huge.getX(), huge.getZ()) <= CAP);
        Vector3dm diagonal = movement(new Vector3dm(), new Vector3dm(CAP, 0, CAP));
        assertEquals(CAP, Math.hypot(diagonal.getX(), diagonal.getZ()), 1e-15);
    }

    @Test
    void boatFlyHoverAscentAndAntiKickDoNotReplaceGravity() throws Exception {
        GrimPlayer player = mock(GrimPlayer.class);
        VehicleData data = new VehicleData();
        data.oldStatus = data.status = BoatEntityStatus.IN_AIR;
        var vehicleDataField = GrimPlayer.class.getField("vehicleData");
        vehicleDataField.setAccessible(true);
        vehicleDataField.set(player, data);
        player.hasGravity = true;
        PredictionEngineBoat engine = mock(PredictionEngineBoat.class);
        var floatBoat = PredictionEngineBoat.class.getDeclaredMethod("floatBoat", GrimPlayer.class, Vector3dm.class);
        floatBoat.setAccessible(true);
        Vector3dm physics = new Vector3dm();
        for (int tick = 0; tick < 200; tick++) {
            floatBoat.invoke(engine, player, physics);
            assertTrue(physics.getY() < 0);
            for (double spoofedY : new double[]{0, 0.3, -0.0313, 100}) {
                Vector3dm result = movement(physics, new Vector3dm(CAP, spoofedY, 0));
                assertEquals(physics.getY(), result.getY());
                assertNotEquals(spoofedY, result.getY());
            }
        }
    }

    @Test
    void cobwebAndBushSlowdownCannotBeErased() {
        for (var stuck : List.of(StuckSpeed.COBWEB, StuckSpeed.COBWEB_WEAVING, StuckSpeed.SWEET_BERRY_BUSH)) {
            Vector3dm result = EntitySpeedAllowance.horizontalMovement(new Vector3dm(0, -0.123, 0),
                    new Vector3dm(CAP, 5, CAP), stuck);
            assertEquals(CAP * stuck.getX(), Math.hypot(result.getX(), result.getZ()), 1e-15);
            assertEquals(-0.123, result.getY());
        }
    }

    @Test
    void horizontalPermissionStillCollidesWithWallsAndGround() {
        SimpleCollisionBox boat = new SimpleCollisionBox(-0.7, 1, -0.7, 0.7, 1.6, 0.7);
        List<SimpleCollisionBox> blocks = List.of(new SimpleCollisionBox(0.8, 0, -2, 2, 3, 2),
                new SimpleCollisionBox(-5, 0, -5, 5, 1, 5));
        Vector3dm requested = movement(new Vector3dm(0, -0.04, 0), new Vector3dm(CAP, 0, 0));
        Vector3dm result = Collisions.collideBoundingBoxLegacy(requested, boat, blocks, List.of(Axis.Y, Axis.X, Axis.Z));
        assertEquals(0.1, result.getX(), 1e-12);
        assertEquals(0, result.getY());
    }

    @Test
    void nonFiniteRequestsFailClosed() {
        Vector3dm physics = new Vector3dm(0.1, -0.04, 0.2);
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertEquals(physics, movement(physics, new Vector3dm(invalid, 0, 0)));
            assertEquals(physics, movement(physics, new Vector3dm(0, invalid, 0)));
            assertEquals(physics, movement(physics, new Vector3dm(0, 0, invalid)));
        }
    }

    @Test
    void whitelistExcludesPlayersMinecartsFlyingAndUnknownVehicles() {
        assertTrue(EntitySpeedAllowance.supports(EntityTypes.BOAT));
        assertTrue(EntitySpeedAllowance.supports(EntityTypes.OAK_BOAT));
        assertTrue(EntitySpeedAllowance.supports(EntityTypes.HORSE));
        assertTrue(EntitySpeedAllowance.supports(EntityTypes.PIG));
        assertTrue(EntitySpeedAllowance.supports(EntityTypes.STRIDER));
        for (var type : List.of(EntityTypes.PLAYER, EntityTypes.MINECART, EntityTypes.HAPPY_GHAST,
                EntityTypes.NAUTILUS, EntityTypes.LLAMA, EntityTypes.ARMOR_STAND)) {
            assertFalse(EntitySpeedAllowance.supports(type));
        }
    }

    @Test
    void candidatesKeepVanillaYAndProvenanceWithoutMutatingOriginals() throws Exception {
        GrimPlayer player = rider();
        VectorData vanilla = new VectorData(new Vector3dm(0.1, -0.04, 0.2), VectorData.VectorType.Normal);
        vanilla.stuckSpeedMultiplier = StuckSpeed.COBWEB;
        List<VectorData> candidates = new ArrayList<>(List.of(vanilla));
        EntitySpeedAllowance.addCandidates(player, candidates);
        assertEquals(2, candidates.size());
        assertSame(vanilla, candidates.get(0));
        VectorData extra = candidates.get(1);
        assertSame(vanilla, extra.lastVector);
        assertSame(StuckSpeed.COBWEB, extra.stuckSpeedMultiplier);
        assertEquals(vanilla.vector, extra.vector);
        assertNotSame(vanilla.vector, extra.vector);
        assertEquals(VectorData.VectorType.EntitySpeed, extra.vectorType);
    }

    @Test
    void neverAddsVelocityOrSkippedTickAlternatives() throws Exception {
        GrimPlayer player = rider();
        List<VectorData> candidates = new ArrayList<>();
        for (var type : List.of(VectorData.VectorType.Knockback, VectorData.VectorType.Explosion,
                VectorData.VectorType.Trident, VectorData.VectorType.ZeroPointZeroThree)) {
            candidates.add(new VectorData(new Vector3dm(0, 0.1, 0), type));
        }
        EntitySpeedAllowance.addCandidates(player, candidates);
        assertEquals(4, candidates.size());
    }

    @Test
    void pendingVelocityStillAllowsCancellationWithEntitySpeed() throws Exception {
        for (String field : List.of("firstBreadKB", "likelyKB", "firstBreadExplosion", "likelyExplosions")) {
            GrimPlayer player = rider();
            GrimPlayer.class.getField(field).set(player, new VelocityData(1, 1, false, new Vector3dm(1, 1, 0)));
            List<VectorData> candidates = normalCandidates();
            EntitySpeedAllowance.addCandidates(player, candidates);
            assertEquals(2, candidates.size(), field);
        }
    }

    @Test
    void dismountAndInvalidMovementNeverGetPermission() throws Exception {
        GrimPlayer player = rider();
        when(player.compensatedEntities.self.getRiding()).thenReturn(null);
        List<VectorData> candidates = normalCandidates();
        EntitySpeedAllowance.addCandidates(player, candidates);
        assertEquals(1, candidates.size());
        player = rider();
        player.actualMovement.setX(Double.NaN);
        EntitySpeedAllowance.addCandidates(player, candidates);
        assertEquals(1, candidates.size());
    }

    @Test
    void secondPassengersAndStackedVehiclesDoNotGetControl() throws Exception {
        GrimPlayer player = rider();
        PacketEntity boat = player.compensatedEntities.self.getRiding();
        boat.passengers.add(0, mock(PacketEntity.class));
        List<VectorData> candidates = normalCandidates();
        EntitySpeedAllowance.addCandidates(player, candidates);
        assertEquals(1, candidates.size());
        boat.passengers.remove(0);
        when(boat.getRiding()).thenReturn(mock(PacketEntity.class));
        EntitySpeedAllowance.addCandidates(player, candidates);
        assertEquals(1, candidates.size());
    }

    @Test
    void unsaddledHorseDoesNotGetEntityControlBypass() throws Exception {
        GrimPlayer player = rider();
        PacketEntityHorse horse = mock(PacketEntityHorse.class);
        when(horse.getType()).thenReturn(EntityTypes.HORSE);
        setPassengers(horse, player.compensatedEntities.self);
        when(player.compensatedEntities.self.getRiding()).thenReturn(horse);
        List<VectorData> candidates = normalCandidates();
        EntitySpeedAllowance.addCandidates(player, candidates);
        assertEquals(1, candidates.size());
        when(horse.hasSaddle()).thenReturn(true);
        EntitySpeedAllowance.addCandidates(player, candidates);
        assertEquals(2, candidates.size());
    }

    @Test
    void modernPigAndStriderRequireServerSaddleAndCorrectSteeringItem() throws Exception {
        for (var type : List.of(EntityTypes.PIG, EntityTypes.STRIDER)) {
            GrimPlayer player = rider();
            PacketEntityRideable animal = mock(PacketEntityRideable.class);
            when(animal.getType()).thenReturn(type);
            setPassengers(animal, player.compensatedEntities.self);
            when(player.compensatedEntities.self.getRiding()).thenReturn(animal);
            CompensatedInventory inventory = mock(CompensatedInventory.class);
            var field = GrimPlayer.class.getField("inventory");
            field.setAccessible(true);
            field.set(player, inventory);
            ItemStack mainHand = mock(ItemStack.class);
            ItemStack offHand = mock(ItemStack.class);
            when(inventory.getHeldItem()).thenReturn(mainHand);
            when(inventory.getOffHand()).thenReturn(offHand);
            when(mainHand.getType()).thenReturn(ItemTypes.AIR);
            when(offHand.getType()).thenReturn(ItemTypes.AIR);
            List<VectorData> candidates = normalCandidates();
            EntitySpeedAllowance.addCandidates(player, candidates);
            assertEquals(1, candidates.size());
            when(animal.hasItemInSlot(EquipmentSlot.SADDLE)).thenReturn(true);
            EntitySpeedAllowance.addCandidates(player, candidates);
            assertEquals(1, candidates.size(), "a saddle alone must not grant steering");
            when(offHand.getType()).thenReturn(type == EntityTypes.PIG
                    ? ItemTypes.CARROT_ON_A_STICK : ItemTypes.WARPED_FUNGUS_ON_A_STICK);
            EntitySpeedAllowance.addCandidates(player, candidates);
            assertEquals(2, candidates.size());
        }
    }

    private static List<VectorData> normalCandidates() {
        return new ArrayList<>(List.of(new VectorData(new Vector3dm(), VectorData.VectorType.Normal)));
    }

    private static GrimPlayer rider() throws Exception {
        GrimPlayer player = mock(GrimPlayer.class);
        CompensatedEntities entities = mock(CompensatedEntities.class);
        entities.self = mock(PacketEntitySelf.class);
        PacketEntity boat = mock(PacketEntity.class);
        when(boat.getType()).thenReturn(EntityTypes.OAK_BOAT);
        when(entities.self.getRiding()).thenReturn(boat);
        setPassengers(boat, entities.self);
        var entitiesField = GrimPlayer.class.getField("compensatedEntities");
        entitiesField.setAccessible(true);
        entitiesField.set(player, entities);
        player.actualMovement = new Vector3dm(CAP, 0, 0);
        return player;
    }

    private static void setPassengers(PacketEntity vehicle, PacketEntity self) throws Exception {
        var field = PacketEntity.class.getField("passengers");
        field.setAccessible(true);
        field.set(vehicle, new ArrayList<>(List.of(self)));
    }

    private static Vector3dm movement(Vector3dm physics, Vector3dm requested) {
        return EntitySpeedAllowance.horizontalMovement(physics, requested, StuckSpeed.NONE);
    }
}
