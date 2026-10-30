package ac.grim.grimac.predictionengine.predictions;

import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.predictionengine.UncertaintyHandler;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.PacketStateData;
import ac.grim.grimac.utils.data.VectorData;
import ac.grim.grimac.utils.latency.CompensatedEntities;
import ac.grim.grimac.utils.math.Vector3dm;
import ac.grim.grimac.utils.nmsutil.BlockProperties;
import ac.grim.grimac.utils.nmsutil.Collisions;
import ac.grim.grimac.utils.nmsutil.JumpPower;
import ac.grim.grimac.utils.nmsutil.StuckSpeed;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.settings.PacketEventsSettings;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StrafeStartupTest {
    @BeforeAll static void api() {
        var api = mock(PacketEventsAPI.class);
        var server = mock(ServerManager.class);
        when(api.getServerManager()).thenReturn(server);
        when(server.getVersion()).thenReturn(ServerVersion.V_1_21_11);
        when(api.getSettings()).thenReturn(new PacketEventsSettings());
        PacketEvents.setAPI(api);
    }

    static GrimPlayer player() throws Exception {
        var p = mock(GrimPlayer.class);
        var entities = mock(CompensatedEntities.class);
        when(entities.getPotionLevelForPlayer(any())).thenReturn(OptionalInt.empty());
        for (var entry : java.util.Map.of("compensatedEntities", entities,
                "packetStateData", new PacketStateData(), "uncertaintyHandler", mock(UncertaintyHandler.class)).entrySet()) {
            var field = GrimPlayer.class.getField(entry.getKey());
            field.setAccessible(true);
            field.set(p, entry.getValue());
        }
        when(p.getClientVersion()).thenReturn(ClientVersion.V_1_20_5);
        when(p.getMovementThreshold()).thenReturn(0.03);
        p.boundingBox = new SimpleCollisionBox(-0.3, 0, -0.3, 0.3, 1.8, 0.3);
        p.lastOnGround = p.onGround = p.hasGravity = true;
        return p;
    }

    static Vector3dm tick(StrafeAllowance allowance, GrimPlayer p, double requested, double physics) {
        p.actualMovement = new Vector3dm(requested, p.onGround ? 0 : 0.42, 0);
        var vanilla = new VectorData(new Vector3dm(physics, -0.0784, 0), VectorData.VectorType.Normal);
        vanilla.input = new Vector3dm(1, 0, 0);
        var candidates = new ArrayList<>(List.of(vanilla));
        allowance.addCandidates(p, candidates, true);
        var candidate = candidates.get(candidates.size() - 1);
        var result = allowance.movement(candidate, candidate.vector, p.actualMovement);
        p.predictedVelocity = candidate.returnNewModified(result, VectorData.VectorType.BestVelPicked);
        allowance.finish(p);
        return result;
    }

    @Test void recordedStartupAndNextJumpFitThePrediction() throws Exception {
        var p = player();
        var allowance = new StrafeAllowance();
        try (var collisions = mockStatic(Collisions.class); var properties = mockStatic(BlockProperties.class); var jump = mockStatic(JumpPower.class)) {
            properties.when(() -> BlockProperties.getFriction(any(), any(), any())).thenReturn(0.6F);
            jump.when(() -> JumpPower.getJumpPower(p)).thenReturn(0.42F);
            for (int tick = 0; tick < 3; tick++) assertEquals(0.28729999065398, tick(allowance, p, 0.28729999065398, 0.1).getX(), 1e-12);
            assertEquals(0.3556227428106805, tick(allowance, p, 0.3556227428106805, 0.1).getX(), 1e-12);
            p.onGround = false;
            assertEquals(0.545880910214387, tick(allowance, p, 0.545880910214387, 0.1).getX(), 1e-12);
        }
    }

    @Test void recordedGroundCoastKeepsAlreadyValidatedNativeMomentum() throws Exception {
        var p = player();
        var allowance = new StrafeAllowance();
        try (var collisions = mockStatic(Collisions.class); var properties = mockStatic(BlockProperties.class)) {
            properties.when(() -> BlockProperties.getFriction(any(), any(), any())).thenReturn(0.6F);
            assertEquals(0.424168, tick(allowance, p, 0.424168, 0.424168).getX(), 1e-12);
            assertEquals(0.358995, tick(allowance, p, 0.358995, 0.1).getX(), 1e-12);
            assertEquals(0.3436603678015125, tick(allowance, p, 0.3436603678015125, 0.1).getX(), 1e-12);
        }
    }

    @Test void sustainedOverspeedStillExhaustsTheBoundedCredit() throws Exception {
        var p = player();
        var allowance = new StrafeAllowance();
        try (var collisions = mockStatic(Collisions.class); var properties = mockStatic(BlockProperties.class)) {
            properties.when(() -> BlockProperties.getFriction(any(), any(), any())).thenReturn(0.6F);
            for (int i = 0; i < 10000; i++) tick(allowance, p, 0.2873, 0.1);
            double normal = StrafeAllowance.groundSpeed(0.2873, false);
            double total = 0;
            for (int i = 0; i < 300; i++) {
                var move = tick(allowance, p, 100, 0.1);
                assertTrue(move.getX() < 0.373);
                assertEquals(-0.0784, move.getY());
                total += move.getX();
            }
            assertTrue(total <= normal * 300 + 0.085, "No extra sustained movement beyond the finite credit");
            assertEquals(normal, tick(allowance, p, 100, 0.1).getX(), 1e-12);
            allowance.reset();
            assertEquals(normal, tick(allowance, p, 100, 0.1).getX(), 1e-12);
        }
    }

    @Test void idleAndMissingSupportCannotSpendGroundCredit() throws Exception {
        var p = player();
        var allowance = new StrafeAllowance();
        try (var collisions = mockStatic(Collisions.class); var properties = mockStatic(BlockProperties.class)) {
            properties.when(() -> BlockProperties.getFriction(any(), any(), any())).thenReturn(0.6F);
            for (int i = 0; i < 100; i++) tick(allowance, p, 0, 0.1);
            assertEquals(StrafeAllowance.groundSpeed(0.2873, false), tick(allowance, p, 100, 0.1).getX(), 1e-12);
            for (int i = 0; i < 3; i++) tick(allowance, p, 0.2873, 0.1);
            collisions.when(() -> Collisions.isEmpty(any(), any())).thenReturn(true);
            assertEquals(StrafeAllowance.groundSpeed(0.2873, false), tick(allowance, p, 100, 0.1).getX(), 1e-12);
        }
    }

    @Test void recordedHorizontalSequenceFitsWithoutRaisingTheSteadyLimit() throws Exception {
        var p = player();
        var allowance = new StrafeAllowance();
        try (var collisions = mockStatic(Collisions.class); var properties = mockStatic(BlockProperties.class); var jump = mockStatic(JumpPower.class);
             var resource = StrafeStartupTest.class.getResourceAsStream("/mio-speed-startup.csv")) {
            assertNotNull(resource);
            properties.when(() -> BlockProperties.getFriction(any(), any(), any())).thenReturn(0.6F);
            jump.when(() -> JumpPower.getJumpPower(p)).thenReturn(0.42F);
            var rows = new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\\R");
            for (int i = 1; i < rows.length; i++) {
                var r = rows[i].split(",");
                p.onGround = Boolean.parseBoolean(r[1]);
                p.lastOnGround = Boolean.parseBoolean(r[2]);
                p.actualMovement = new Vector3dm(Double.parseDouble(r[3]), Double.parseDouble(r[4]), Double.parseDouble(r[5]));
                double horizontal = Math.hypot(p.actualMovement.getX(), p.actualMovement.getZ());
                double nativeSpeed = Double.parseDouble(r[6]);
                boolean impulse = Boolean.parseBoolean(r[7]);
                var nativeVector = new Vector3dm(horizontal == 0 ? 0 : p.actualMovement.getX() * nativeSpeed / horizontal,
                        p.actualMovement.getY(), horizontal == 0 ? 0 : p.actualMovement.getZ() * nativeSpeed / horizontal);
                var vanilla = new VectorData(nativeVector, impulse ? VectorData.VectorType.Knockback : VectorData.VectorType.Normal);
                vanilla.input = new Vector3dm(1, 0, 0);
                var candidates = new ArrayList<>(List.of(vanilla));
                allowance.addCandidates(p, candidates, true);
                var candidate = candidates.get(candidates.size() - 1);
                var result = impulse ? p.actualMovement.clone() : allowance.movement(candidate, candidate.vector, p.actualMovement);
                assertTrue(Math.hypot(p.actualMovement.getX() - result.getX(), p.actualMovement.getZ() - result.getZ()) < 0.001, r[0]);
                p.predictedVelocity = candidate.returnNewModified(result, VectorData.VectorType.BestVelPicked);
                allowance.finish(p);
            }
        }
    }

    @Test void cobwebImpulseAndHugeNativeSpeedCannotSeedAnotherBurst() throws Exception {
        for (var type : List.of(VectorData.VectorType.Normal, VectorData.VectorType.Knockback, VectorData.VectorType.Explosion)) {
            var p = player();
            var allowance = new StrafeAllowance();
            try (var collisions = mockStatic(Collisions.class); var properties = mockStatic(BlockProperties.class)) {
                properties.when(() -> BlockProperties.getFriction(any(), any(), any())).thenReturn(0.6F);
                for (int i = 0; i < 3; i++) tick(allowance, p, 0.2873, 0.1);
                var vanilla = new VectorData(new Vector3dm(0.1, -0.0784, 0), type);
                vanilla.input = new Vector3dm(1, 0, 0);
                if (type == VectorData.VectorType.Normal) vanilla.stuckSpeedMultiplier = StuckSpeed.COBWEB;
                var candidates = new ArrayList<>(List.of(vanilla));
                p.actualMovement = new Vector3dm(100, 100, 100);
                allowance.addCandidates(p, candidates, true);
                var candidate = candidates.get(candidates.size() - 1);
                var result = allowance.movement(candidate, candidate.vector, p.actualMovement);
                double nativeCap = type == VectorData.VectorType.Normal ? 0.1 : 0;
                assertTrue(Math.hypot(result.getX(), result.getZ()) <= Math.max(nativeCap,
                        StrafeAllowance.groundSpeed(0.2873, false) * candidate.stuckSpeedMultiplier.getX()) + 1e-12);
                p.predictedVelocity = candidate.returnNewModified(result, VectorData.VectorType.BestVelPicked);
                allowance.finish(p);
                assertEquals(StrafeAllowance.groundSpeed(0.2873, false), tick(allowance, p, 100, 0.1).getX(), 1e-12);
                allowance.reset();
                assertEquals(10, tick(allowance, p, 100, 10).getX());
                assertEquals(StrafeAllowance.groundSpeed(0.2873, false), tick(allowance, p, 100, 0.1).getX(), 1e-12);
            }
        }
    }

    @Test void enteringWaterOrLavaClearsTheExtraDryGroundAllowance() throws Exception {
        for (boolean water : new boolean[]{false, true}) {
            var p = player();
            var allowance = new StrafeAllowance();
            try (var collisions = mockStatic(Collisions.class); var properties = mockStatic(BlockProperties.class)) {
                properties.when(() -> BlockProperties.getFriction(any(), any(), any())).thenReturn(0.6F);
                for (int i = 0; i < 3; i++) tick(allowance, p, 0.2873, 0.1);
                p.wasTouchingWater = water;
                p.wasTouchingLava = !water;
                assertEquals(StrafeAllowance.groundSpeed(0.2873, false), tick(allowance, p, 100, 0.1).getX(), 1e-12);
                p.wasTouchingWater = p.wasTouchingLava = false;
                assertEquals(StrafeAllowance.groundSpeed(0.2873, false), tick(allowance, p, 100, 0.1).getX(), 1e-12);
            }
        }
    }
}
