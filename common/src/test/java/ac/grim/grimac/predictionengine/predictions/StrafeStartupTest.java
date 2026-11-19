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
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.settings.PacketEventsSettings;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StrafeStartupTest {
    @BeforeAll static void packets() {
        var api = mock(PacketEventsAPI.class);
        var server = mock(ServerManager.class);
        when(api.getServerManager()).thenReturn(server);
        when(api.getSettings()).thenReturn(new PacketEventsSettings());
        when(server.getVersion()).thenReturn(ServerVersion.V_1_21_11);
        PacketEvents.setAPI(api);
    }

    @Test void openZioStartupTakeoffAndAirDecayUseValidatedMovement() throws Exception {
        var p = mock(GrimPlayer.class);
        var entities = mock(CompensatedEntities.class);
        when(entities.getPotionLevelForPlayer(any())).thenReturn(OptionalInt.empty());
        set(p, "compensatedEntities", entities);
        set(p, "uncertaintyHandler", mock(UncertaintyHandler.class));
        set(p, "packetStateData", new PacketStateData());
        p.hasGravity = true;
        p.boundingBox = new SimpleCollisionBox(-0.3, 1, -0.3, 0.3, 2.8, 0.3);
        var allowance = new StrafeAllowance();
        double base = 0.2873;
        double startup = 0.377855;
        double takeoff = startup * 1.535;
        double[] speeds = {base, startup, takeoff, takeoff - 0.6600000262260437 * (takeoff - base)};
        try (var friction = mockStatic(BlockProperties.class); var jump = mockStatic(JumpPower.class);
             var collisions = mockStatic(Collisions.class)) {
            jump.when(() -> JumpPower.getJumpPower(p)).thenReturn(0.42F);
            for (int tick = 0; tick < speeds.length; tick++) {
                p.lastOnGround = tick < 3;
                p.onGround = tick < 2;
                p.actualMovement = new Vector3dm(speeds[tick], tick == 2 ? 0.42 : tick == 3 ? 0.3332 : 0, 0);
                var source = new VectorData(new Vector3dm(0.1, p.actualMovement.getY(), 0), VectorData.VectorType.Normal);
                source.input = new Vector3dm(1, 0, 0);
                var candidates = new ArrayList<>(List.of(source));
                allowance.addCandidates(p, candidates, true);
                assertEquals(2, candidates.size());
                var candidate = candidates.get(1);
                var predicted = allowance.movement(candidate, candidate.vector, p.actualMovement);
                assertEquals(speeds[tick], predicted.getX(), 1e-8, "tick " + tick);
                var excessive = allowance.movement(candidate, candidate.vector, new Vector3dm(100, 100, 100));
                assertTrue(Math.hypot(excessive.getX(), excessive.getZ()) < 0.616);
                assertEquals(candidate.vector.getY(), excessive.getY());
                p.predictedVelocity = new VectorData(predicted, candidate, VectorData.VectorType.BestVelPicked);
                allowance.finish(p);
            }
        }
    }

    private static void set(GrimPlayer p, String name, Object value) throws Exception {
        var field = GrimPlayer.class.getField(name);
        field.setAccessible(true);
        field.set(p, value);
    }
}
