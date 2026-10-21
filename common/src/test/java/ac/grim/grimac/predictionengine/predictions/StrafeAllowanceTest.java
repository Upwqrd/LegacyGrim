package ac.grim.grimac.predictionengine.predictions;

import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.checks.impl.movement.NoSlow;
import ac.grim.grimac.checks.impl.sprint.SprintC;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import ac.grim.grimac.utils.data.packetentity.PacketEntitySelf;
import ac.grim.grimac.utils.math.TrigHandler;
import com.github.retrooper.packetevents.protocol.attribute.Attributes;
import ac.grim.grimac.predictionengine.UncertaintyHandler;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.PacketStateData;
import ac.grim.grimac.utils.data.VectorData;
import ac.grim.grimac.utils.data.VelocityData;
import ac.grim.grimac.utils.latency.CompensatedEntities;
import ac.grim.grimac.utils.latency.CompensatedWorld;
import ac.grim.grimac.utils.data.MainSupportingBlockData;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import ac.grim.grimac.utils.math.Vector3dm;
import ac.grim.grimac.utils.nmsutil.Collisions;
import ac.grim.grimac.utils.nmsutil.JumpPower;
import ac.grim.grimac.utils.nmsutil.StuckSpeed;
import com.github.retrooper.packetevents.protocol.potion.PotionTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.settings.PacketEventsSettings;
import com.github.retrooper.packetevents.util.LogManager;
import io.github.retrooper.packetevents.impl.netty.NettyManagerImpl;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StrafeAllowanceTest {
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
    void supportedProfileRejectsHigherMultiplierAndSustainedLongJumpSpeed() {
        double peak = StrafeAllowance.groundSpeed(0.2873, true);
        assertEquals((1.18F * 0.2873 - 0.01) * 1.63, peak, 1e-15);
        double speed = StrafeAllowance.airSpeed(0.2873, peak, true);
        assertTrue(speed < 0.373);
        for (int tick = 0; tick < 200; tick++) {
            Vector3dm bounded = StrafeAllowance.boundedMovement(new Vector3dm(0, -0.12, 0),
                    new Vector3dm(peak * 1.1, 1, peak), speed);
            assertTrue(Math.hypot(bounded.getX(), bounded.getZ()) < peak);
            assertEquals(-0.12, bounded.getY());
            double next = StrafeAllowance.airSpeed(0.2873, speed, false);
            assertTrue(next <= speed);
            assertTrue(next >= 0.2873);
            speed = next;
        }
    }

    @Test
    void unrestrictedFasterHopKeepsItsDecayAndRejectsTheNextMultiplier() {
        double cap = 0.535807242;
        assertTrue(cap < StrafeAllowance.groundSpeed(0.2873, true));
        double[] recorded = {0.535807242, 0.371792456, 0.369454138,
                0.367130527, 0.364821530, 0.362527055, 0.360247011,
                0.357981307, 0.355729852, 0.353492557, 0.351269333, 0.349060092};
        for (int tick = 0; tick < recorded.length; tick++) {
            assertEquals(recorded[tick], cap, 1e-8);
            if (tick + 1 < recorded.length)
                cap = StrafeAllowance.airSpeed(0.2873, cap, tick == 0);
        }
        double higher = (1.18F * 0.2873 - 0.01) * 1.7;
        Vector3dm bounded = StrafeAllowance.boundedMovement(new Vector3dm(0, 0.4, 0),
                new Vector3dm(higher, 10, 0), StrafeAllowance.groundSpeed(0.2873, true));
        assertTrue(bounded.getX() < higher);
        assertTrue(bounded.getX() < 0.537);
        assertEquals(0.4, bounded.getY());
    }

    @Test
    void unrestrictedRecordingAndMeteorRemainInsideTheBoundedPrediction() throws Exception {
        var fixture = StrafeAllowanceTest.class.getResourceAsStream("/strafe-unrestricted.csv");
        assertNotNull(fixture);
        String[] rows;
        try (fixture) {
            rows = new String(fixture.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\\R");
        }
        for (var version : List.of(ClientVersion.V_1_20_5, ClientVersion.V_1_21_11)) {
            GrimPlayer player = player();
            when(player.getClientVersion()).thenReturn(version);
            StrafeAllowance allowance = new StrafeAllowance();
            try (var jump = mockStatic(JumpPower.class); var collisions = mockStatic(Collisions.class)) {
                jump.when(() -> JumpPower.getJumpPower(player)).thenReturn(0.42F);
                for (String row : rows) {
                    String[] values = row.split(",");
                    player.onGround = Boolean.parseBoolean(values[0]);
                    player.actualMovement = new Vector3dm(Double.parseDouble(values[1]),
                            Double.parseDouble(values[2]), Double.parseDouble(values[3]));
                    List<VectorData> candidates = candidates();
                    allowance.addCandidates(player, candidates, true);
                    assertEquals(2, candidates.size());
                    VectorData candidate = candidates.get(1);
                    Vector3dm predicted = allowance.movement(candidate, candidate.vector, player.actualMovement);
                    assertEquals(player.actualMovement.getX(), predicted.getX(), 1e-7, row);
                    assertEquals(player.actualMovement.getZ(), predicted.getZ(), 1e-7, row);
                    Vector3dm over = allowance.movement(candidate, candidate.vector, new Vector3dm(100, 100, 100));
                    assertTrue(Math.hypot(over.getX(), over.getZ()) < 0.537);
                    assertEquals(candidate.vector.getY(), over.getY());
                    player.predictedVelocity = new VectorData(predicted, candidate, VectorData.VectorType.BestVelPicked);
                    allowance.finish(player);
                    player.lastOnGround = player.onGround;
                }
                allowance.reset();
                double meteor = (1.18F * 0.2873 - 0.01) * 1.6;
                for (int tick = 0; tick < 11; tick++) {
                    player.lastOnGround = tick == 0;
                    player.onGround = false;
                    player.actualMovement = new Vector3dm(meteor, tick == 0 ? 0.40123128 : 0.31, 0);
                    List<VectorData> candidates = candidates();
                    allowance.addCandidates(player, candidates, true);
                    VectorData candidate = candidates.get(1);
                    Vector3dm predicted = allowance.movement(candidate, candidate.vector, player.actualMovement);
                    assertEquals(meteor, predicted.getX(), 1e-12);
                    player.predictedVelocity = new VectorData(predicted, candidate, VectorData.VectorType.BestVelPicked);
                    allowance.finish(player);
                    meteor = tick == 0 ? Math.max(0.2873, meteor - 0.76 * (meteor - 0.2873))
                            : Math.max(0.2873, meteor * 158 / 159);
                }
            }
        }
    }

    @Test
    void radialLimitCannotBeExpandedByDiagonalHugeOrNonFiniteRequests() {
        Random random = new Random(134);
        double cap = StrafeAllowance.groundSpeed(0.2873, true);
        Vector3dm physics = new Vector3dm(0.1, -0.08, 0.1);
        for (int i = 0; i < 10000; i++) {
            Vector3dm result = StrafeAllowance.boundedMovement(physics,
                    new Vector3dm(random.nextGaussian() * 100, 100, random.nextGaussian() * 100), cap);
            assertTrue(Math.hypot(result.getX(), result.getZ()) <= cap + 1e-15);
            assertEquals(physics.getY(), result.getY());
        }
        for (double bad : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertEquals(physics, StrafeAllowance.boundedMovement(physics, new Vector3dm(bad, 0, 0), cap));
            assertEquals(physics, StrafeAllowance.boundedMovement(physics, new Vector3dm(0, bad, 0), cap));
        }
        Vector3dm huge = StrafeAllowance.boundedMovement(physics,
                new Vector3dm(Double.MAX_VALUE, 0, Double.MAX_VALUE), cap);
        assertTrue(Double.isFinite(huge.getX()));
        assertTrue(Math.hypot(huge.getX(), huge.getZ()) <= cap);
    }

    @Test
    void hopRequiresPhysicalSupportEvenWhenClientClaimsGround() throws Exception {
        GrimPlayer player = player();
        player.actualMovement = new Vector3dm(0.1, 0.40123128, 0);
        player.onGround = false;
        StrafeAllowance allowance = new StrafeAllowance();
        try (var jump = mockStatic(JumpPower.class); var collisions = mockStatic(Collisions.class)) {
            jump.when(() -> JumpPower.getJumpPower(player)).thenReturn(0.42F);
            collisions.when(() -> Collisions.isEmpty(eq(player), any())).thenReturn(true);
            List<VectorData> candidates = candidates();
            allowance.addCandidates(player, candidates, true);
            assertEquals(1, candidates.size());
            collisions.when(() -> Collisions.isEmpty(eq(player), any())).thenReturn(false);
            allowance.addCandidates(player, candidates, true);
            assertEquals(2, candidates.size());
            assertEquals(0.40123128, candidates.get(1).vector.getY());
            assertTrue(candidates.get(1).isJump());
            assertEquals(-0.0784, candidates.get(0).vector.getY());
        }
    }

    @Test
    void validatedEdgeLandingAllowsOneSteeredJumpWithoutCreatingAirSupport() throws Exception {
        for (var version : List.of(ClientVersion.V_1_20_5, ClientVersion.V_1_21_11)) {
            GrimPlayer player = player();
            when(player.getClientVersion()).thenReturn(version);
            player.boundingBox = new SimpleCollisionBox(-13.429247855343462, 1.0244241860610739,
                    -56.18018511239655, -12.829247831501604, 2.824424138377358, -55.58018508855469);
            var floor = new SimpleCollisionBox(-13, 0, -56, -12, 1, -55);
            var incoming = new Vector3dm(-0.18091379689360565, -0.22768848754498797, 0.2850732529348292);
            var landing = Collisions.collideBoundingBoxLegacy(incoming, player.boundingBox, List.of(floor),
                    List.of(Collisions.Axis.Y, Collisions.Axis.X, Collisions.Axis.Z));
            assertEquals(-0.024424186061073883, landing.getY(), 1e-14);
            var after = player.boundingBox.copy().offset(landing.getX(), landing.getY(), landing.getZ());
            assertFalse(floor.isIntersected(after.copy().offset(0, -0.03, 0)));
            StrafeAllowance allowance = new StrafeAllowance();
            player.lastOnGround = player.onGround = false;
            allowance.addCandidates(player, candidates(), true);
            var physics = new VectorData(incoming, VectorData.VectorType.Normal);
            player.predictedVelocity = new VectorData(landing, physics, VectorData.VectorType.BestVelPicked);
            player.predictedVelocity.preUncertainty = physics;
            player.verticalCollision = player.onGround = true;
            allowance.finish(player);
            player.boundingBox = after;
            player.lastOnGround = true;
            player.onGround = false;
            player.actualMovement = new Vector3dm(-0.3587160617162848, 0.42, 0.3740646134803711);
            try (var jump = mockStatic(JumpPower.class); var collisions = mockStatic(Collisions.class)) {
                jump.when(() -> JumpPower.getJumpPower(player)).thenReturn(0.42F);
                collisions.when(() -> Collisions.isEmpty(eq(player), any())).thenReturn(true);
                List<VectorData> candidates = candidates();
                allowance.addCandidates(player, candidates, true);
                assertEquals(2, candidates.size());
                VectorData candidate = candidates.get(1);
                var predicted = allowance.movement(candidate, candidate.vector, player.actualMovement);
                assertEquals(player.actualMovement, predicted);
                player.verticalCollision = false;
                player.predictedVelocity = new VectorData(predicted, candidate, VectorData.VectorType.BestVelPicked);
                player.predictedVelocity.preUncertainty = candidate;
                allowance.finish(player);
                // Claiming ground again in mid-air cannot reuse the preceding landing.
                candidates = candidates();
                allowance.addCandidates(player, candidates, true);
                assertEquals(1, candidates.size());
            }
        }
    }

    @Test
    void shortenedHopUsesAcceptedMomentumAndGroundCoastingCannotAccelerate() throws Exception {
        GrimPlayer player = player();
        StrafeAllowance allowance = new StrafeAllowance();
        try (var jump = mockStatic(JumpPower.class); var collisions = mockStatic(Collisions.class)) {
            jump.when(() -> JumpPower.getJumpPower(player)).thenReturn(0.42F);
            player.lastOnGround = true;
            player.onGround = false;
            player.actualMovement = new Vector3dm(0.535807242, 0.42, 0);
            allowance.addCandidates(player, candidates(), true);
            player.predictedVelocity = new VectorData(player.actualMovement.clone(), VectorData.VectorType.Normal);
            allowance.finish(player);
            player.lastOnGround = false;
            double speed = StrafeAllowance.airSpeed(0.2873, 0.535807242, true);
            for (int tick = 0; tick < 8; tick++) {
                player.onGround = tick == 7;
                List<VectorData> candidates = candidates();
                allowance.addCandidates(player, candidates, true);
                var predicted = allowance.movement(candidates.get(1), candidates.get(1).vector,
                        new Vector3dm(speed, -0.1, 0));
                assertEquals(speed, predicted.getX(), 1e-12);
                player.predictedVelocity = new VectorData(predicted, VectorData.VectorType.Normal);
                allowance.finish(player);
                if (tick != 7) speed *= 158.0 / 159.0;
            }
            player.lastOnGround = true;
            // The supported client's boost is proportional to the accepted landing speed.
            player.onGround = false;
            double boosted = speed * 1.535;
            player.actualMovement = new Vector3dm(boosted, 0.42, 0);
            List<VectorData> candidates = candidates();
            allowance.addCandidates(player, candidates, true);
            var predicted = allowance.movement(candidates.get(1), candidates.get(1).vector, player.actualMovement);
            assertEquals(boosted, predicted.getX(), 1e-12);
            assertTrue(boosted > StrafeAllowance.groundSpeed(0.2873, true));
            var excessive = allowance.movement(candidates.get(1), candidates.get(1).vector,
                    new Vector3dm(boosted * 2, 10, 0));
            assertEquals(boosted, excessive.getX(), 1e-12);
            assertEquals(0.42, excessive.getY(), 1e-7);
            // An already accepted landing can coast on ground, with mandatory decay.
            player.onGround = true;
            player.predictedVelocity = new VectorData(new Vector3dm(speed, 0, 0), VectorData.VectorType.Normal);
            allowance.finish(player);
            double previous = speed;
            for (int tick = 0; tick < 100; tick++) {
                candidates = candidates();
                allowance.addCandidates(player, candidates, true);
                predicted = allowance.movement(candidates.get(1), candidates.get(1).vector, new Vector3dm(100, 100, 0));
                assertTrue(predicted.getX() <= previous);
                player.predictedVelocity = new VectorData(predicted, VectorData.VectorType.Normal);
                allowance.finish(player);
                previous = predicted.getX();
            }
        }
    }

    @Test
    void ceilingCollisionOrGroundClaimCannotCreateEdgeSupport() throws Exception {
        for (int mode = 0; mode < 4; mode++) {
            GrimPlayer player = player();
            StrafeAllowance allowance = new StrafeAllowance();
            allowance.addCandidates(player, candidates(), true);
            player.onGround = mode != 0;
            player.verticalCollision = mode != 1;
            player.predictedVelocity = new VectorData(new Vector3dm(0.2, 0, 0), VectorData.VectorType.Normal);
            if (mode != 2) player.predictedVelocity.preUncertainty = new VectorData(
                    new Vector3dm(0.2, mode == 3 ? 0.42 : -0.1, 0), VectorData.VectorType.Normal);
            allowance.finish(player);
            player.lastOnGround = true;
            player.onGround = false;
            player.actualMovement = new Vector3dm(0.3, 0.42, 0);
            try (var jump = mockStatic(JumpPower.class); var collisions = mockStatic(Collisions.class)) {
                jump.when(() -> JumpPower.getJumpPower(player)).thenReturn(0.42F);
                collisions.when(() -> Collisions.isEmpty(eq(player), any())).thenReturn(true);
                List<VectorData> candidates = candidates();
                allowance.addCandidates(player, candidates, true);
                assertEquals(1, candidates.size());
            }
        }
    }

    @Test
    void shortestGroundedHopCycleStaysBoundedAndAirCannotRefreshBoost() throws Exception {
        GrimPlayer player = player();
        StrafeAllowance allowance = new StrafeAllowance();
        try (var jump = mockStatic(JumpPower.class); var collisions = mockStatic(Collisions.class)) {
            jump.when(() -> JumpPower.getJumpPower(player)).thenReturn(0.42F);
            for (int cycle = 0; cycle < 200; cycle++) {
                player.lastOnGround = true;
                player.onGround = false;
                player.actualMovement = new Vector3dm(100, 0.42, 0);
                List<VectorData> candidates = candidates();
                allowance.addCandidates(player, candidates, true);
                VectorData candidate = candidates.get(1);
                var bounded = allowance.movement(candidate, candidate.vector, player.actualMovement);
                assertTrue(bounded.getX() < 0.609);
                player.predictedVelocity = new VectorData(bounded, candidate, VectorData.VectorType.BestVelPicked);
                allowance.finish(player);
                player.lastOnGround = false;
                player.onGround = true;
                candidates = candidates();
                allowance.addCandidates(player, candidates, true);
                candidate = candidates.get(1);
                bounded = allowance.movement(candidate, candidate.vector, new Vector3dm(100, 0, 0));
                assertTrue(bounded.getX() < 0.397);
                player.predictedVelocity = new VectorData(bounded, candidate, VectorData.VectorType.BestVelPicked);
                allowance.finish(player);
            }
            player.lastOnGround = player.onGround = false;
            double previous = Double.POSITIVE_INFINITY;
            for (int tick = 0; tick < 500; tick++) {
                List<VectorData> candidates = candidates();
                allowance.addCandidates(player, candidates, true);
                VectorData candidate = candidates.get(1);
                var bounded = allowance.movement(candidate, candidate.vector, new Vector3dm(100, 100, 0));
                assertTrue(bounded.getX() <= previous);
                assertEquals(candidate.vector.getY(), bounded.getY());
                player.predictedVelocity = new VectorData(bounded, candidate, VectorData.VectorType.BestVelPicked);
                allowance.finish(player);
                previous = bounded.getX();
            }
            assertEquals(0.2873, previous);
        }
    }

    @Test
    void recordedSprintJumpCanSteerAtPhysicsSpeedWithoutCreditingLongJump() throws Exception {
        for (var version : List.of(ClientVersion.V_1_20_5, ClientVersion.V_1_21_11)) {
            GrimPlayer player = player();
            when(player.getClientVersion()).thenReturn(version);
            player.onGround = false;
            player.actualMovement = new Vector3dm(-0.4302957311054705, 0.41999998688697815, -0.4019605555583041);
            var physics = new VectorData(new Vector3dm(-0.43216038911845145, 0.41999998688697815,
                    -0.3999551256195114), VectorData.VectorType.Jump);
            physics.input = new Vector3dm(1, 0, 0);
            StrafeAllowance allowance = new StrafeAllowance();
            try (var jump = mockStatic(JumpPower.class); var collisions = mockStatic(Collisions.class)) {
                jump.when(() -> JumpPower.getJumpPower(player)).thenReturn(0.42F);
                List<VectorData> candidates = new ArrayList<>(List.of(physics));
                allowance.addCandidates(player, candidates, true);
                VectorData candidate = candidates.get(2);
                var predicted = allowance.movement(candidate, candidate.vector, player.actualMovement);
                assertEquals(player.actualMovement.getX(), predicted.getX(), 1e-8);
                assertEquals(player.actualMovement.getZ(), predicted.getZ(), 1e-8);
                assertEquals(physics.vector.getY(), predicted.getY());
                var huge = allowance.movement(candidate, candidate.vector, new Vector3dm(100, 100, 100));
                assertEquals(Math.hypot(physics.vector.getX(), physics.vector.getZ()),
                        Math.hypot(huge.getX(), huge.getZ()), 1e-15);
                player.predictedVelocity = new VectorData(predicted, candidate, VectorData.VectorType.BestVelPicked);
                allowance.finish(player);
                player.lastOnGround = false;
                candidates = candidates();
                allowance.addCandidates(player, candidates, true);
                predicted = allowance.movement(candidates.get(1), candidates.get(1).vector, new Vector3dm(100, 100, 100));
                assertTrue(Math.hypot(predicted.getX(), predicted.getZ()) < 0.373);
                assertEquals(candidates.get(1).vector.getY(), predicted.getY());
            }
        }
    }

    @Test
    void groundSteeringPreservesExistingPhysicsBudgetAndWallCollision() throws Exception {
        GrimPlayer player = player();
        StrafeAllowance allowance = new StrafeAllowance();
        List<VectorData> candidates = candidates();
        candidates.get(0).vector.setX(0.4);
        allowance.addCandidates(player, candidates, true);
        VectorData candidate = candidates.get(1);
        var turned = allowance.movement(candidate, candidate.vector, new Vector3dm(0, 100, 0.4));
        assertEquals(0.4, turned.getZ());
        assertEquals(candidate.vector.getY(), turned.getY());
        var large = allowance.movement(candidate, candidate.vector, new Vector3dm(100, 100, 100));
        assertEquals(0.4, Math.hypot(large.getX(), large.getZ()), 1e-15);
        var blocked = Collisions.collideBoundingBoxLegacy(turned, player.boundingBox,
                List.of(new SimpleCollisionBox(-5, 0, 0.4, 5, 5, 2),
                        new SimpleCollisionBox(-5, 0, -5, 5, 1, 5)),
                List.of(Collisions.Axis.Y, Collisions.Axis.X, Collisions.Axis.Z));
        assertEquals(0.1, blocked.getZ(), 1e-12);
        assertEquals(0, blocked.getY());
    }

    @Test
    void acceptedPredictionBoundsNextTickEvenIfClientRequestsHugeDistance() throws Exception {
        GrimPlayer player = player();
        player.onGround = false;
        StrafeAllowance allowance = new StrafeAllowance();
        try (var jump = mockStatic(JumpPower.class); var collisions = mockStatic(Collisions.class)) {
            jump.when(() -> JumpPower.getJumpPower(player)).thenReturn(0.42F);
            List<VectorData> candidates = candidates();
            allowance.addCandidates(player, candidates, true);
            VectorData candidate = candidates.get(1);
            player.actualMovement = new Vector3dm(100, 100, 0);
            Vector3dm accepted = allowance.movement(candidate, candidate.vector, player.actualMovement);
            player.predictedVelocity = new VectorData(accepted.clone(), candidate, VectorData.VectorType.BestVelPicked);
            assertNull(player.predictedVelocity.preUncertainty);
            allowance.finish(player);
            player.lastOnGround = false;
            candidates = candidates();
            allowance.addCandidates(player, candidates, true);
            assertEquals(2, candidates.size());
            Vector3dm next = allowance.movement(candidates.get(1), new Vector3dm(0, 0.31, 0), player.actualMovement);
            assertTrue(next.getX() < 0.373);
            assertEquals(0.31, next.getY());
            allowance.reset();
            candidates = candidates();
            allowance.addCandidates(player, candidates, true);
            assertEquals(2, candidates.size());
            assertEquals(0.2873, allowance.movement(candidates.get(1), candidates.get(1).vector, player.actualMovement).getX());
        }
    }

    @Test
    void ordinaryHopWinnerKeepsAirStrafeAndResetOnlyAllowsBaseAirSpeed() throws Exception {
        GrimPlayer player = player();
        player.onGround = false;
        StrafeAllowance allowance = new StrafeAllowance();
        try (var jump = mockStatic(JumpPower.class); var collisions = mockStatic(Collisions.class)) {
            jump.when(() -> JumpPower.getJumpPower(player)).thenReturn(0.42F);
            List<VectorData> candidates = candidates();
            allowance.addCandidates(player, candidates, true);
            double peak = StrafeAllowance.groundSpeed(0.2873, true);
            player.predictedVelocity = new VectorData(new Vector3dm(peak, 0.40123128, 0),
                    candidates.get(0), VectorData.VectorType.BestVelPicked);
            allowance.finish(player);
            player.lastOnGround = false;
            candidates = candidates();
            allowance.addCandidates(player, candidates, true);
            assertEquals(2, candidates.size());
            Vector3dm requested = new Vector3dm(100, 100, 0);
            Vector3dm result = allowance.movement(candidates.get(1), candidates.get(1).vector, requested);
            assertEquals(StrafeAllowance.airSpeed(0.2873, peak, true), result.getX(), 1e-15);
            assertEquals(-0.0784, result.getY());
            allowance.reset();
            candidates = candidates();
            allowance.addCandidates(player, candidates, true);
            result = allowance.movement(candidates.get(1), candidates.get(1).vector, requested);
            assertEquals(0.2873, result.getX(), 1e-15);
            assertEquals(-0.0784, result.getY());
            assertFalse(candidates.get(1).isJump());
        }
    }

    @Test
    void pendingServerVelocityAllowsStrafeButSpecialMovementDoesNot() throws Exception {
        for (String field : List.of("firstBreadKB", "likelyKB", "firstBreadExplosion", "likelyExplosions")) {
            GrimPlayer player = player();
            GrimPlayer.class.getField(field).set(player, new VelocityData(1, 1, false, new Vector3dm(1, 1, 0)));
            List<VectorData> candidates = candidates();
            new StrafeAllowance().addCandidates(player, candidates, true);
            assertEquals(2, candidates.size(), field);
        }
        for (String field : List.of("isFlying", "isGliding", "isClimbing", "wasTouchingWater", "wasTouchingLava", "isSneaking")) {
            GrimPlayer player = player();
            GrimPlayer.class.getField(field).setBoolean(player, true);
            List<VectorData> candidates = candidates();
            new StrafeAllowance().addCandidates(player, candidates, true);
            assertEquals(1, candidates.size(), field);
        }
        GrimPlayer player = player();
        List<VectorData> candidates = candidates();
        new StrafeAllowance().addCandidates(player, candidates, false);
        assertEquals(1, candidates.size());
        player.lastOnGround = false;
        new StrafeAllowance().addCandidates(player, candidates, true);
        assertEquals(2, candidates.size());
    }

    @Test
    void potionAndStuckSlowdownsRemainBoundedWithoutMutatingVanilla() throws Exception {
        GrimPlayer player = player();
        when(player.compensatedEntities.getPotionLevelForPlayer(PotionTypes.SLOWNESS)).thenReturn(OptionalInt.of(0));
        List<VectorData> candidates = candidates();
        candidates.get(0).stuckSpeedMultiplier = StuckSpeed.COBWEB;
        StrafeAllowance allowance = new StrafeAllowance();
        allowance.addCandidates(player, candidates, true);
        assertEquals(2, candidates.size());
        assertSame(StuckSpeed.COBWEB, candidates.get(1).stuckSpeedMultiplier);
        Vector3dm result = allowance.movement(candidates.get(1), new Vector3dm(0, -0.08, 0), new Vector3dm(100, 100, 0));
        assertEquals(StrafeAllowance.groundSpeed(0.2873 * 0.85, false) * 0.25, result.getX(), 1e-15);
        assertEquals(-0.08, result.getY());
        assertEquals(new Vector3dm(0.1, -0.0784, 0), candidates.get(0).vector);
    }

    @Test
    void actualCollisionResolverStillStopsStrafeAtWallsAndFloor() {
        var box = new SimpleCollisionBox(-0.3, 1, -0.3, 0.3, 2.8, 0.3);
        var blocks = List.of(new SimpleCollisionBox(0.4, 0, -2, 2, 4, 2),
                new SimpleCollisionBox(-5, 0, -5, 5, 1, 5));
        Vector3dm requested = StrafeAllowance.boundedMovement(new Vector3dm(0, -0.0784, 0),
                new Vector3dm(100, 1, 0), StrafeAllowance.groundSpeed(0.2873, true));
        Vector3dm result = Collisions.collideBoundingBoxLegacy(requested, box, blocks,
                List.of(Collisions.Axis.Y, Collisions.Axis.X, Collisions.Axis.Z));
        assertEquals(0.1, result.getX(), 1e-12);
        assertEquals(0, result.getY());
    }

    @Test
    void iceExitKeepsValidatedMomentumButCannotRefreshOrIncreaseItOnStone() throws Exception {
        for (var ice : List.of(StateTypes.ICE, StateTypes.PACKED_ICE, StateTypes.FROSTED_ICE, StateTypes.BLUE_ICE)) {
            GrimPlayer player = player();
            when(player.compensatedWorld.getBlockType(anyDouble(), anyDouble(), anyDouble())).thenReturn(ice);
            StrafeAllowance allowance = new StrafeAllowance();
            List<VectorData> candidates = candidates();
            candidates.get(0).vector.setX(0.6);
            allowance.addCandidates(player, candidates, true);
            // Ordinary physics can win while on ice; its accepted speed still carries to the exit.
            player.predictedVelocity = new VectorData(new Vector3dm(0.6, 0, 0), candidates.get(0), VectorData.VectorType.BestVelPicked);
            allowance.finish(player);
            when(player.compensatedWorld.getBlockType(anyDouble(), anyDouble(), anyDouble())).thenReturn(StateTypes.STONE);
            double previous = 0.6;
            for (int tick = 0; tick < 100; tick++) {
                player.actualMovement = new Vector3dm(1000, 0, 1000);
                candidates = candidates();
                allowance.addCandidates(player, candidates, true);
                VectorData candidate = candidates.get(1);
                Vector3dm result = allowance.movement(candidate, candidate.vector, player.actualMovement);
                double length = Math.hypot(result.getX(), result.getZ());
                assertTrue(length <= previous + 1e-15);
                if (tick == 0) assertEquals(0.6 * 158 / 159, length, 1e-15);
                player.predictedVelocity = new VectorData(result, candidate, VectorData.VectorType.BestVelPicked);
                allowance.finish(player);
                previous = length;
            }
            assertEquals(StrafeAllowance.groundSpeed(0.2873, false), previous, 1e-15);
        }
    }

    @Test
    void collisionAndResetRemoveIceCreditAndNativeSpeedCannotCreatePersistentCredit() throws Exception {
        GrimPlayer player = player();
        StrafeAllowance allowance = new StrafeAllowance();
        List<VectorData> candidates = candidates();
        candidates.get(0).vector.setX(10);
        allowance.addCandidates(player, candidates, true);
        Vector3dm bounded = allowance.movement(candidates.get(1), candidates.get(1).vector, new Vector3dm(100, 0, 0));
        assertEquals(10, bounded.getX()); // The supplied physics budget applies only to this candidate.
        player.predictedVelocity = new VectorData(bounded, candidates.get(1), VectorData.VectorType.BestVelPicked);
        allowance.finish(player);
        candidates = candidates();
        allowance.addCandidates(player, candidates, true);
        bounded = allowance.movement(candidates.get(1), candidates.get(1).vector, new Vector3dm(100, 0, 0));
        assertEquals(StrafeAllowance.groundSpeed(0.2873, false), bounded.getX());
        when(player.compensatedWorld.getBlockType(anyDouble(), anyDouble(), anyDouble())).thenReturn(StateTypes.BLUE_ICE);
        candidates = candidates();
        candidates.get(0).vector.setX(0.8);
        allowance.addCandidates(player, candidates, true);
        player.predictedVelocity = new VectorData(new Vector3dm(0.1, 0, 0), candidates.get(0), VectorData.VectorType.BestVelPicked);
        allowance.finish(player);
        when(player.compensatedWorld.getBlockType(anyDouble(), anyDouble(), anyDouble())).thenReturn(StateTypes.STONE);
        candidates = candidates();
        allowance.addCandidates(player, candidates, true);
        bounded = allowance.movement(candidates.get(1), candidates.get(1).vector, new Vector3dm(100, 0, 0));
        assertEquals(StrafeAllowance.groundSpeed(0.2873, false), bounded.getX());
        allowance.reset();
        candidates = candidates();
        allowance.addCandidates(player, candidates, true);
        bounded = allowance.movement(candidates.get(1), candidates.get(1).vector, new Vector3dm(100, 0, 0));
        assertEquals(StrafeAllowance.groundSpeed(0.2873, false), bounded.getX());
    }

    @Test
    void realKnockbackAndExplosionsKeepTheirYInsteadOfBeingReplacedByStrafeHop() throws Exception {
        for(var type:List.of(VectorData.VectorType.Knockback,VectorData.VectorType.Explosion)) {
            for(double y:new double[]{-0.4,0.4,1.2}) {
                GrimPlayer player=player();
                player.onGround=false;
                StrafeAllowance allowance=new StrafeAllowance();
                VectorData velocity=new VectorData(new Vector3dm(0.5,y,0),type);
                velocity.input=new Vector3dm(1,0,0);
                List<VectorData> candidates=new ArrayList<>(List.of(velocity));
                try(var jump=mockStatic(JumpPower.class);var collisions=mockStatic(Collisions.class)) {
                    jump.when(()->JumpPower.getJumpPower(player)).thenReturn(0.42F);
                    allowance.addCandidates(player,candidates,true);
                }
                assertEquals(2,candidates.size());
                VectorData strafe=candidates.get(1);
                assertEquals(y,strafe.vector.getY());
                assertEquals(velocity.isKnockback(),strafe.isKnockback());
                assertEquals(velocity.isExplosion(),strafe.isExplosion());
                assertFalse(strafe.isJump());
                for(int tick=0;tick<100;tick++) {
                    Vector3dm result=allowance.movement(strafe,new Vector3dm(0,y,0),new Vector3dm(100,100,0));
                    assertEquals(y,result.getY());
                    assertEquals(y,velocity.vector.getY());
                }
            }
        }
    }

    @Test
    void impulseCannotSeedPersistentIceSpeed() throws Exception {
        GrimPlayer player=player();
        when(player.compensatedWorld.getBlockType(anyDouble(),anyDouble(),anyDouble())).thenReturn(StateTypes.BLUE_ICE);
        StrafeAllowance allowance=new StrafeAllowance();
        List<VectorData> candidates=candidates();
        VectorData kb=new VectorData(new Vector3dm(100,0.4,0),VectorData.VectorType.Knockback);
        kb.input=new Vector3dm(1,0,0);
        candidates.add(kb);
        allowance.addCandidates(player,candidates,true);
        Vector3dm result=allowance.movement(candidates.get(3),kb.vector,new Vector3dm(100,100,0));
        assertEquals(StrafeAllowance.groundSpeed(0.2873,false),result.getX());
        assertEquals(0.4,result.getY());
    }

    @Test
    void mandatoryCrystalAttackSlowdownCannotRemoveEveryStrafeCandidate() throws Exception {
        for(boolean hop:new boolean[]{false,true}) {
            GrimPlayer player=player();
            player.onGround=!hop;
            player.actualMovement = new Vector3dm(0.1, hop ? 0.40123128 : 0, 0);
            StrafeAllowance allowance=new StrafeAllowance();
            List<VectorData> candidates=candidates();
            candidates.get(0).addVectorType(VectorData.VectorType.AttackSlow);
            try(var jump=mockStatic(JumpPower.class);var collisions=mockStatic(Collisions.class)) {
                jump.when(()->JumpPower.getJumpPower(player)).thenReturn(0.42F);
                allowance.addCandidates(player,candidates,true);
            }
            assertEquals(2,candidates.size());
            VectorData strafe=candidates.get(1);
            assertTrue(strafe.isAttackSlow());
            assertEquals(VectorData.VectorType.Strafe,strafe.vectorType);
            Vector3dm requested=new Vector3dm(100,100,0);
            Vector3dm predicted=allowance.movement(strafe,strafe.vector,requested);
            assertEquals(StrafeAllowance.groundSpeed(0.2873,hop),predicted.getX(),1e-15);
            assertEquals(hop?0.40123128:-0.0784,predicted.getY(),1e-15);
            assertTrue(predicted.getX()<0.55);
            player.packetStateData.setSlowedByUsingItem(true);
            List<VectorData> usingItem=candidates();
            try(var jump=mockStatic(JumpPower.class);var collisions=mockStatic(Collisions.class)) {
                jump.when(()->JumpPower.getJumpPower(player)).thenReturn(0.42F);
                allowance.addCandidates(player,usingItem,true);
            }
            assertEquals(2,usingItem.size());
        }
    }

    @Test
    void itemUseAllowsBoundedStrafeWithoutRemovingCobwebOrVerticalPhysics() throws Exception {
        GrimPlayer player=player();
        player.packetStateData.setSlowedByUsingItem(true);
        List<VectorData> candidates=candidates();
        candidates.set(0,candidates.get(0).returnNewModified(VectorData.VectorType.Flip_Use_Item));
        candidates.get(0).stuckSpeedMultiplier=StuckSpeed.COBWEB;
        StrafeAllowance allowance=new StrafeAllowance();
        allowance.addCandidates(player,candidates,true);
        assertEquals(2,candidates.size());
        VectorData extra=candidates.get(1);
        assertTrue(extra.isFlipItem());
        Vector3dm result=allowance.movement(extra,new Vector3dm(0,-0.3,0),new Vector3dm(100,100,100));
        assertEquals(StrafeAllowance.groundSpeed(0.2873,false)*0.25,Math.hypot(result.getX(),result.getZ()),1e-15);
        assertEquals(-0.3,result.getY());
        assertTrue(player.packetStateData.isSlowedByUsingItem());
    }

    @Test
    void slownessLevelsKeepVanillaFifteenPercentPenaltyDuringNoSlow() throws Exception {
        for(int amplifier=0;amplifier<=7;amplifier++) {
            GrimPlayer player=player();
            player.packetStateData.setSlowedByUsingItem(true);
            when(player.compensatedEntities.getPotionLevelForPlayer(PotionTypes.SLOWNESS)).thenReturn(OptionalInt.of(amplifier));
            List<VectorData> candidates=candidates();
            StrafeAllowance allowance=new StrafeAllowance();
            allowance.addCandidates(player,candidates,true);
            assertEquals(2,candidates.size());
            Vector3dm result=allowance.movement(candidates.get(1),new Vector3dm(0,-0.1,0),new Vector3dm(100,100,0));
            double base=0.2873*Math.max(0,1-0.15*(amplifier+1));
            assertEquals(StrafeAllowance.groundSpeed(base,false),result.getX(),1e-15);
            assertEquals(-0.1,result.getY());
        }
    }

    @Test
    void slowFallingStillUsesVanillaGravityWhileItemsAreInUse() throws Exception {
        GrimPlayer player=player();
        player.packetStateData.setSlowedByUsingItem(true);
        player.compensatedEntities.self=mock(PacketEntitySelf.class);
        when(player.compensatedEntities.self.getAttributeValue(Attributes.GRAVITY)).thenReturn(0.08);
        when(player.compensatedEntities.getSlowFallingAmplifier()).thenReturn(OptionalInt.of(0));
        player.friction=0.91F;
        Vector3dm falling=new Vector3dm(0.3,-0.4,0);
        PredictionEngineNormal.staticVectorEndOfTick(player,falling);
        assertEquals((-0.4-0.01)*0.98F,falling.getY(),1e-15);
        Vector3dm rising=new Vector3dm(0.3,0.4,0);
        PredictionEngineNormal.staticVectorEndOfTick(player,rising);
        assertEquals((0.4-0.08)*0.98F,rising.getY(),1e-15);
        when(player.compensatedEntities.getSlowFallingAmplifier()).thenReturn(OptionalInt.empty());
        falling=new Vector3dm(0.3,-0.4,0);
        PredictionEngineNormal.staticVectorEndOfTick(player,falling);
        assertEquals((-0.4-0.08)*0.98F,falling.getY(),1e-15);
    }

    @Test
    void normalItemPredictionKeepsBothVanillaSlowAndBoundedFullSpeedWithPotionPenalty() throws Exception {
        GrimPlayer player=player();
        when(player.getClientVersion()).thenReturn(ClientVersion.V_1_20_5);
        TrigHandler trig=mock(TrigHandler.class);
        when(trig.cos(anyFloat())).thenReturn(1F);
        set(player,"trigHandler",trig);
        player.sneakingSpeedMultiplier=0.3F;
        player.packetStateData.setSlowedByUsingItem(true);
        float speed=0.13F*0.85F;
        List<VectorData> candidates=new ArrayList<>();
        new PredictionEngine().loopVectors(player,java.util.Set.of(new VectorData(new Vector3dm(),VectorData.VectorType.Normal)),speed,candidates,false);
        double max=candidates.stream().mapToDouble(v->Math.hypot(v.vector.getX(),v.vector.getZ())).max().orElseThrow();
        assertEquals(speed,max,1e-7);
        assertTrue(candidates.stream().anyMatch(v->Math.abs(v.vector.length()-speed*0.2*0.98)<1e-7));
        assertTrue(candidates.stream().allMatch(v->v.vector.getY()==0));
        assertTrue(player.packetStateData.isSlowedByUsingItem());
    }

    @Test
    void noSlowPenaltyStaysDisabledAcrossReloadWithoutChangingItemState() throws Exception {
        GrimPlayer player=player();
        player.packetStateData.setSlowedByUsingItem(true);
        NoSlow check=mock(NoSlow.class,CALLS_REAL_METHODS);
        var field=ac.grim.grimac.checks.GrimProcessor.class.getDeclaredField("player");
        field.setAccessible(true);field.set(check,player);
        check.onReload(mock(ConfigManager.class));
        PredictionComplete result=mock(PredictionComplete.class);
        when(result.isChecked()).thenReturn(true);
        check.flaggedLastTick=true;
        for(int tick=0;tick<20;tick++)check.onPredictionComplete(result);
        assertFalse(check.flaggedLastTick);
        assertEquals(0,check.getViolations());
        assertTrue(player.packetStateData.isSlowedByUsingItem());
        check.onReload(mock(ConfigManager.class));
        check.onPredictionComplete(result);
        assertFalse(check.flaggedLastTick);
    }

    @Test
    void itemUseSprintCheckIsNotRegisteredOnAnySupportedClient() throws Exception {
        GrimPlayer player=player();
        SprintC check=mock(SprintC.class,CALLS_REAL_METHODS);
        var field=ac.grim.grimac.checks.GrimProcessor.class.getDeclaredField("player");
        field.setAccessible(true);field.set(check,player);
        for(var version:List.of(ClientVersion.V_1_8,ClientVersion.V_1_20_5,ClientVersion.V_1_21_4,ClientVersion.V_1_21_11)) {
            when(player.getClientVersion()).thenReturn(version);
            assertFalse(check.isApplicable());
        }
    }

    @Test
    void configuredStepNeverChangesVanillaHeightAttributesOrVehicles() throws Exception {
        GrimPlayer player = player();
        player.compensatedEntities.self = mock(PacketEntitySelf.class);
        when(player.getMaxUpStep()).thenCallRealMethod();
        when(player.compensatedEntities.self.getAttributeValue(Attributes.STEP_HEIGHT)).thenReturn(0.6);
        var field = GrimPlayer.class.getDeclaredField("allowedStepHeight");
        field.setAccessible(true);
        for (float height : new float[]{0, 1, 2}) {
            field.setFloat(player, height);
            assertEquals(0.6F, player.getMaxUpStep());
        }
        when(player.compensatedEntities.self.getAttributeValue(Attributes.STEP_HEIGHT)).thenReturn(3.0);
        assertEquals(3, player.getMaxUpStep());
        var riding = mock(ac.grim.grimac.utils.data.packetentity.PacketEntity.class);
        when(player.compensatedEntities.self.getRiding()).thenReturn(riding);
        when(riding.getAttributeValue(Attributes.STEP_HEIGHT)).thenReturn(0.6);
        assertEquals(0.6F, player.getMaxUpStep());
    }

    @Test
    void realStepCollisionRequiresObstacleGroundAndHeadroomAndRespectsHeight() throws Exception {
        for (var version : List.of(ClientVersion.V_1_20_5, ClientVersion.V_1_21_11)) {
            GrimPlayer player = player();
            when(player.getClientVersion()).thenReturn(version);
            set(player, "pointThreeEstimator", mock(ac.grim.grimac.predictionengine.PointThreeEstimator.class));
            var setback = mock(ac.grim.grimac.manager.SetbackTeleportUtil.class);
            when(player.getSetbackTeleportUtil()).thenReturn(setback);
            player.boundingBox = new SimpleCollisionBox(-0.3, 0, -0.3, 0.3, 1.8, 0.3);
            when(player.getMaxUpStep()).thenReturn(0.6F);
            var data = new VectorData(new Vector3dm(0.5, -0.0784, 0), VectorData.VectorType.Strafe);
            var blocks = new ArrayList<SimpleCollisionBox>();
            blocks.add(new SimpleCollisionBox(-5, -1, -5, 5, 0, 5));
            try (var collisions = mockStatic(Collisions.class, CALLS_REAL_METHODS)) {
                collisions.when(() -> Collisions.getCollisionBoxes(eq(player), any(), anyList(), eq(false)))
                        .thenAnswer(call -> { ((List<SimpleCollisionBox>) call.getArgument(2)).addAll(blocks); return true; });
                for (float allowed : new float[]{1, 2}) {
                    when(player.getAllowedStepHeight()).thenReturn(allowed);
                    for (double obstacle : new double[]{1, 2, 3}) {
                        blocks.subList(1, blocks.size()).clear();
                        blocks.add(new SimpleCollisionBox(0.4, 0, -2, 2, obstacle, 2));
                        player.actualMovement = new Vector3dm(0.5, obstacle, 0);
                        Vector3dm result = Collisions.collide(player, 0.5, -0.0784, 0, -0.0784, data);
                        assertEquals(obstacle <= allowed ? obstacle : 0, result.getY(), 1e-6);
                        assertEquals(obstacle <= allowed ? 0.5 : 0.1, result.getX(), 1e-6);
                    }
                }
                blocks.subList(1, blocks.size()).clear();
                player.actualMovement = new Vector3dm(0.5, 2, 0);
                assertEquals(0, Collisions.collide(player, 0.5, -0.0784, 0, -0.0784, data).getY(), 1e-6);
                blocks.add(new SimpleCollisionBox(0.4, 0, -2, 2, 2, 2));
                blocks.add(new SimpleCollisionBox(-2, 2.5, -2, 2, 3.5, 2));
                assertEquals(0, Collisions.collide(player, 0.5, -0.0784, 0, -0.0784, data).getY(), 1e-6);
                blocks.remove(2);
                blocks.remove(0);
                player.lastOnGround = false;
                player.boundingBox = player.boundingBox.copy().offset(0, 0.1, 0);
                assertEquals(-0.0784, Collisions.collide(player, 0.5, -0.0784, 0, -0.0784, data).getY(), 1e-6);
            }
        }
    }

    @Test
    void optionalStepPreservesCornerJumpPredictionAndRejectsAirVehicleAndZeroPointThree() throws Exception {
        for (var version : List.of(ClientVersion.V_1_20_5, ClientVersion.V_1_21_11)) {
            GrimPlayer player = player();
            when(player.getClientVersion()).thenReturn(version);
            when(player.getMaxUpStep()).thenReturn(0.6F);
            set(player, "pointThreeEstimator", mock(ac.grim.grimac.predictionengine.PointThreeEstimator.class));
            when(player.getSetbackTeleportUtil()).thenReturn(mock(ac.grim.grimac.manager.SetbackTeleportUtil.class));
            var blocks = List.of(new SimpleCollisionBox(-5, -1, -5, 5, 0, 5),
                    new SimpleCollisionBox(0.4, 0, 0.4, 2, 1, 2));
            try (var collisions = mockStatic(Collisions.class, CALLS_REAL_METHODS)) {
                collisions.when(() -> Collisions.getCollisionBoxes(eq(player), any(), anyList(), eq(false)))
                        .thenAnswer(call -> { ((List<SimpleCollisionBox>) call.getArgument(2)).addAll(blocks); return true; });
                for (double y : new double[]{0, 0.1, 0.4, 0.753, 1.01}) {
                    for (double motionY : new double[]{0.40123128, 0.31, -0.0784, -0.4}) {
                        player.boundingBox = new SimpleCollisionBox(-0.3, y, -0.3, 0.3, y + 1.8, 0.3);
                        player.actualMovement = new Vector3dm(0.5, motionY, 0.5);
                        var data = new VectorData(new Vector3dm(0.5, motionY, 0.5), VectorData.VectorType.Strafe);
                        when(player.getAllowedStepHeight()).thenReturn(0F);
                        Vector3dm baseline = Collisions.collide(player, 0.5, motionY, 0.5, motionY, data);
                        for (float height : new float[]{1, 2}) {
                            when(player.getAllowedStepHeight()).thenReturn(height);
                            assertEquals(baseline, Collisions.collide(player, 0.5, motionY, 0.5, motionY, data));
                        }
                    }
                }
                player.boundingBox = new SimpleCollisionBox(-0.3, 0, -0.3, 0.3, 1.8, 0.3);
                player.actualMovement = new Vector3dm(0.5, 1, 0.5);
                var normal = new VectorData(new Vector3dm(0.5, -0.0784, 0.5), VectorData.VectorType.Strafe);
                var zero = new VectorData(normal.vector, VectorData.VectorType.ZeroPointZeroThree);
                when(player.getAllowedStepHeight()).thenReturn(0F);
                Vector3dm baseline = Collisions.collide(player, 0.5, -0.0784, 0.5, -0.0784, normal);
                when(player.getAllowedStepHeight()).thenReturn(2F);
                assertEquals(1, Collisions.collide(player, 0.5, -0.0784, 0.5, -0.0784, normal).getY(), 1e-6);
                assertEquals(baseline, Collisions.collide(player, 0.5, -0.0784, 0.5, -0.0784, zero));
                assertEquals(baseline, Collisions.collide(player, 0.5, -0.0784, 0.5));
                when(player.inVehicle()).thenReturn(true);
                assertEquals(baseline, Collisions.collide(player, 0.5, -0.0784, 0.5, -0.0784, normal));
                when(player.inVehicle()).thenReturn(false);
                player.lastOnGround = false;
                assertEquals(baseline, Collisions.collide(player, 0.5, -0.0784, 0.5, -0.0784, normal));
            }
        }
    }

    @Test
    void supportedLowHopsKeepOrdinarySprintJumpXZAndTheExistingStrafeCap() throws Exception {
        for (double requestedY : new double[]{0.4, 0.40123128, 0.41, 0.42, 0.1, 10}) {
            GrimPlayer player = player();
            player.onGround = false;
            player.actualMovement = new Vector3dm(0.5888119122448519, requestedY, 0.004645438706155858);
            var jumpVector = new VectorData(new Vector3dm(0.5888247665951056, 0.41999998688697815, 0.0025738782645704744),
                    VectorData.VectorType.Jump);
            jumpVector.input = new Vector3dm(0, 0, 1);
            List<VectorData> candidates = new ArrayList<>(List.of(jumpVector));
            StrafeAllowance allowance = new StrafeAllowance();
            try (var jump = mockStatic(JumpPower.class); var collisions = mockStatic(Collisions.class)) {
                jump.when(() -> JumpPower.getJumpPower(player)).thenReturn(0.42F);
                allowance.addCandidates(player, candidates, true);
            }
            assertEquals(3, candidates.size());
            VectorData ordinary = candidates.get(1), strafe = candidates.get(2);
            double expectedY = Math.max(0.4, Math.min(0.42F, requestedY));
            assertEquals(jumpVector.vector.getX(), ordinary.vector.getX());
            assertEquals(jumpVector.vector.getZ(), ordinary.vector.getZ());
            assertEquals(expectedY, ordinary.vector.getY());
            assertEquals(VectorData.VectorType.Jump, ordinary.vectorType);
            assertTrue(ordinary.isJump());
            assertEquals(VectorData.VectorType.Strafe, strafe.vectorType);
            Vector3dm capped = allowance.movement(strafe, strafe.vector, new Vector3dm(100, 100, 100));
            assertEquals(Math.hypot(strafe.vector.getX(), strafe.vector.getZ()),
                    Math.hypot(capped.getX(), capped.getZ()), 1e-15);
            assertEquals(expectedY, capped.getY());
            assertEquals(0.41999998688697815, jumpVector.vector.getY());
        }
    }

    @Test
    void reverseStepSharesHeightLimitAndOnlyLandsOnTheFirstSurface() throws Exception {
        for (var version : List.of(ClientVersion.V_1_20_5, ClientVersion.V_1_21_11)) {
            GrimPlayer player = reversePlayer(version);
            var data = new VectorData(new Vector3dm(0.5, -0.0784, 0), VectorData.VectorType.Strafe);
            var blocks = new ArrayList<SimpleCollisionBox>();
            blocks.add(new SimpleCollisionBox(-5, -1, -5, 0.15, 0, 5));
            try (var collisions = mockStatic(Collisions.class, CALLS_REAL_METHODS)) {
                collisions.when(() -> Collisions.getCollisionBoxes(eq(player), any(), anyList(), eq(false)))
                        .thenAnswer(call -> { ((List<SimpleCollisionBox>) call.getArgument(2)).addAll(blocks); return true; });
                for (float allowed : new float[]{0, 1, 2}) {
                    when(player.getAllowedStepHeight()).thenReturn(allowed);
                    for (double drop : new double[]{0.5, 1, 1.00000005, 1.0000002, 1.5, 2, 3}) {
                        blocks.subList(1, blocks.size()).clear();
                        blocks.add(new SimpleCollisionBox(-5, -drop - 1, -5, 5, -drop, 5));
                        player.actualMovement = new Vector3dm(0.5, -drop, 0);
                        Vector3dm result = Collisions.collide(player, 0.5, -0.0784, 0, -0.0784, data);
                        assertEquals(drop <= allowed + Collisions.COLLISION_EPSILON ? -drop : 0, result.getY(), 1e-6);
                        assertEquals(0.5, result.getX(), 1e-6);
                    }
                }
                blocks.subList(1, blocks.size()).clear();
                player.actualMovement = new Vector3dm(0.5, -1, 0);
                assertEquals(0, Collisions.collide(player, 0.5, -0.0784, 0, -0.0784, data).getY(), 1e-6);
                blocks.add(new SimpleCollisionBox(-5, -2, -5, 5, -1, 5));
                blocks.add(new SimpleCollisionBox(0.2, -0.3, -2, 2, -0.2, 2));
                // A slab/trapdoor above the intended floor cannot be skipped.
                assertEquals(-0.2, Collisions.collide(player, 0.5, -0.0784, 0, -0.0784, data).getY(), 1e-6);
                blocks.remove(2);
                blocks.add(new SimpleCollisionBox(0.4, -2, -2, 1, 3, 2));
                assertEquals(0, Collisions.collide(player, 0.5, -0.0784, 0, -0.0784, data).getY(), 1e-6);
            }
        }
    }

    @Test
    void reverseStepDoesNotReplaceOrdinaryFallingOrAllowAirJumpVehicleOrPotionBypasses() throws Exception {
        GrimPlayer player = reversePlayer(ClientVersion.V_1_20_5);
        when(player.getAllowedStepHeight()).thenReturn(2F);
        var data = new VectorData(new Vector3dm(0.5, -0.0784, 0), VectorData.VectorType.Strafe);
        var blocks = List.of(new SimpleCollisionBox(-5, -1, -5, 0.15, 0, 5),
                new SimpleCollisionBox(-5, -2, -5, 5, -1, 5));
        try (var collisions = mockStatic(Collisions.class, CALLS_REAL_METHODS)) {
            collisions.when(() -> Collisions.getCollisionBoxes(eq(player), any(), anyList(), eq(false)))
                    .thenAnswer(call -> { ((List<SimpleCollisionBox>) call.getArgument(2)).addAll(blocks); return true; });
            player.actualMovement = new Vector3dm(0.5, -1, 0);
            assertEquals(-1, Collisions.collide(player, 0.5, -0.0784, 0, -0.0784, data).getY(), 1e-6);
            for (var potion : List.of(PotionTypes.SLOW_FALLING, PotionTypes.LEVITATION)) {
                when(player.compensatedEntities.hasPotionEffect(potion)).thenReturn(true);
                assertEquals(0, Collisions.collide(player, 0.5, -0.0784, 0, -0.0784, data).getY(), 1e-6);
                when(player.compensatedEntities.hasPotionEffect(potion)).thenReturn(false);
            }
            for (String field : List.of("isFlying", "isGliding", "isClimbing", "isSwimming", "wasTouchingWater", "wasTouchingLava")) {
                GrimPlayer.class.getField(field).setBoolean(player, true);
                assertEquals(0, Collisions.collide(player, 0.5, -0.0784, 0, -0.0784, data).getY(), 1e-6);
                GrimPlayer.class.getField(field).setBoolean(player, false);
            }
            player.hasGravity = false;
            assertEquals(0, Collisions.collide(player, 0.5, -0.0784, 0, -0.0784, data).getY(), 1e-6);
            player.hasGravity = true;
            player.lastOnGround = false;
            assertEquals(0, Collisions.collide(player, 0.5, -0.0784, 0, -0.0784, data).getY(), 1e-6);
            player.lastOnGround = true;
            when(player.inVehicle()).thenReturn(true);
            assertEquals(0, Collisions.collide(player, 0.5, -0.0784, 0, -0.0784, data).getY(), 1e-6);
            when(player.inVehicle()).thenReturn(false);
            assertEquals(0, Collisions.collide(player, 0.5, -0.0784, 0).getY(), 1e-6);
            var zero = new VectorData(data.vector, VectorData.VectorType.ZeroPointZeroThree);
            when(player.getAllowedStepHeight()).thenReturn(0F);
            Vector3dm zeroBaseline = Collisions.collide(player, 0.5, -0.0784, 0, -0.0784, zero);
            when(player.getAllowedStepHeight()).thenReturn(2F);
            assertEquals(zeroBaseline, Collisions.collide(player, 0.5, -0.0784, 0, -0.0784, zero));
            data.stuckSpeedMultiplier = StuckSpeed.COBWEB;
            assertEquals(0, Collisions.collide(player, 0.5, -0.0784, 0, -0.0784, data).getY(), 1e-6);
            data.stuckSpeedMultiplier = StuckSpeed.NONE;
            player.actualMovement = new Vector3dm(0.5, 0.42, 0);
            assertEquals(0.42, Collisions.collide(player, 0.5, 0.42, 0, 0.42, data).getY(), 1e-6);
            player.actualMovement = new Vector3dm(0.5, 0, 0);
            assertEquals(0, Collisions.collide(player, 0.5, -0.0784, 0, -0.0784, data).getY(), 1e-6);
        }
    }

    @Test
    void reverseStepRejectsCobwebAtEntryOrLandingButNotAdjacentWeb() throws Exception {
        var web = com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState.getDefaultState(StateTypes.COBWEB);
        for (var version : List.of(ClientVersion.V_1_20_5, ClientVersion.V_1_21_11)) {
            GrimPlayer player = reversePlayer(version);
            when(player.getAllowedStepHeight()).thenReturn(1F);
            var data = new VectorData(new Vector3dm(0.7, -0.0784, 0), VectorData.VectorType.Strafe);
            player.actualMovement = new Vector3dm(0.7, -1, 0);
            var blocks = List.of(new SimpleCollisionBox(-5, -1, -5, 0.15, 0, 5),
                    new SimpleCollisionBox(-5, -2, -5, 5, -1, 5));
            var webs = new ArrayList<com.github.retrooper.packetevents.util.Vector3i>();
            try (var collisions = mockStatic(Collisions.class, CALLS_REAL_METHODS)) {
                collisions.when(() -> Collisions.getCollisionBoxes(eq(player), any(), anyList(), eq(false)))
                        .thenAnswer(call -> { ((List<SimpleCollisionBox>) call.getArgument(2)).addAll(blocks); return true; });
                collisions.when(() -> Collisions.hasMaterial(eq(player), any(), any())).thenAnswer(call -> {
                    SimpleCollisionBox box = call.getArgument(1);
                    ac.grim.grimac.utils.functions.BlockAndPositionPredicate predicate = call.getArgument(2);
                    for (var pos : webs) if (new SimpleCollisionBox(pos.x, pos.y, pos.z, pos.x + 1, pos.y + 1, pos.z + 1).isIntersected(box)
                            && predicate.test(web, pos.x, pos.y, pos.z)) return true;
                    return false;
                });
                assertEquals(-1, Collisions.collide(player, 0.7, -0.0784, 0, -0.0784, data).getY(), 1e-6);
                for (var pos : List.of(new com.github.retrooper.packetevents.util.Vector3i(0, -1, 0),
                        new com.github.retrooper.packetevents.util.Vector3i(0, 1, 0))) {
                    webs.clear(); webs.add(pos);
                    assertEquals(0, Collisions.collide(player, 0.7, -0.0784, 0, -0.0784, data).getY(), 1e-6);
                }
                webs.clear(); webs.add(new com.github.retrooper.packetevents.util.Vector3i(1, -1, 0));
                assertEquals(-1, Collisions.collide(player, 0.7, -0.0784, 0, -0.0784, data).getY(), 1e-6);
            }
        }
    }

    private static GrimPlayer reversePlayer(ClientVersion version) throws Exception {
        GrimPlayer player = player();
        when(player.getClientVersion()).thenReturn(version);
        when(player.getMaxUpStep()).thenReturn(0.6F);
        set(player, "pointThreeEstimator", mock(ac.grim.grimac.predictionengine.PointThreeEstimator.class));
        when(player.getSetbackTeleportUtil()).thenReturn(mock(ac.grim.grimac.manager.SetbackTeleportUtil.class));
        player.boundingBox = new SimpleCollisionBox(-0.3, 0, -0.3, 0.3, 1.8, 0.3);
        return player;
    }

    private static GrimPlayer player() throws Exception {
        GrimPlayer player = mock(GrimPlayer.class);
        CompensatedEntities entities = mock(CompensatedEntities.class);
        when(entities.getPotionLevelForPlayer(any())).thenReturn(OptionalInt.empty());
        set(player, "compensatedEntities", entities);
        CompensatedWorld world = mock(CompensatedWorld.class);
        when(world.getBlockType(anyDouble(), anyDouble(), anyDouble())).thenReturn(StateTypes.STONE);
        set(player, "compensatedWorld", world);
        when(player.getClientVersion()).thenReturn(ClientVersion.V_1_21_11);
        player.mainSupportingBlockData = MainSupportingBlockData.AIR_ON_GROUND;
        set(player, "packetStateData", new PacketStateData());
        set(player, "uncertaintyHandler", mock(UncertaintyHandler.class));
        player.actualMovement = new Vector3dm(0.1, 0, 0);
        player.boundingBox = new SimpleCollisionBox(-0.3, 1, -0.3, 0.3, 2.8, 0.3);
        player.hasGravity = true;
        player.lastOnGround = player.onGround = true;
        return player;
    }

    private static void set(GrimPlayer player, String name, Object value) throws Exception {
        var field = GrimPlayer.class.getField(name);
        field.setAccessible(true);
        field.set(player, value);
    }

    private static List<VectorData> candidates() {
        VectorData vanilla = new VectorData(new Vector3dm(0.1, -0.0784, 0), VectorData.VectorType.Normal);
        vanilla.input = new Vector3dm(1, 0, 0);
        return new ArrayList<>(List.of(vanilla));
    }
}
