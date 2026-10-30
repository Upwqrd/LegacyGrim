package ac.grim.grimac.predictionengine.predictions;

import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.predictionengine.PointThreeEstimator;
import ac.grim.grimac.predictionengine.UncertaintyHandler;
import ac.grim.grimac.manager.SetbackTeleportUtil;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.PacketStateData;
import ac.grim.grimac.utils.data.VectorData;
import ac.grim.grimac.utils.latency.CompensatedEntities;
import ac.grim.grimac.utils.math.Vector3dm;
import ac.grim.grimac.utils.nmsutil.Collisions;
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
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StepEdgeTest {
    @BeforeAll static void api() {
        var api = mock(PacketEventsAPI.class);
        var server = mock(ServerManager.class);
        when(server.getVersion()).thenReturn(ServerVersion.V_1_21_11);
        when(api.getServerManager()).thenReturn(server);
        when(api.getSettings()).thenReturn(new PacketEventsSettings());
        PacketEvents.setAPI(api);
    }

    private static void set(GrimPlayer p, String field, Object value) throws Exception {
        var f = GrimPlayer.class.getField(field); f.setAccessible(true); f.set(p, value);
    }

    private static GrimPlayer player(ClientVersion version) throws Exception {
        var p = mock(GrimPlayer.class);
        when(p.getClientVersion()).thenReturn(version);
        when(p.getMaxUpStep()).thenReturn(0.6F);
        when(p.getAllowedStepHeight()).thenReturn(2F);
        when(p.getSetbackTeleportUtil()).thenReturn(mock(SetbackTeleportUtil.class));
        set(p, "pointThreeEstimator", mock(PointThreeEstimator.class));
        set(p, "uncertaintyHandler", mock(UncertaintyHandler.class));
        set(p, "packetStateData", new PacketStateData());
        set(p, "compensatedEntities", mock(CompensatedEntities.class));
        p.boundingBox = new SimpleCollisionBox(-0.3, 0, -0.3, 0.3, 1.8, 0.3);
        p.lastOnGround = p.onGround = p.hasGravity = true;
        return p;
    }

    private static List<ClientVersion> versions() {
        return List.of(ClientVersion.V_1_20_5, ClientVersion.V_1_21_11, ClientVersion.V_26_3);
    }

    private static VectorData data(double x, double y, double z) {
        return new VectorData(new Vector3dm(x, y, z), VectorData.VectorType.Strafe);
    }

    @Test void recordedEdgeStepUsesTheImmediatelyPrecedingRealFloorCollision() throws Exception {
        for (var version : versions()) {
            var p = player(version);
            double x = 209.8565530480787, y = -60, z = -0.6133783083739767;
            p.boundingBox = new SimpleCollisionBox(x - 0.30000001192092896, y, z - 0.30000001192092896,
                    x + 0.30000001192092896, y + 1.7999999523162842, z + 0.30000001192092896);
            var blocks = new ArrayList<>(List.of(new SimpleCollisionBox(210, -61, -1, 211, -60, 0),
                    new SimpleCollisionBox(208, -61, -1, 209, -59, 0)));
            try (var c = mockStatic(Collisions.class, CALLS_REAL_METHODS)) {
                c.when(() -> Collisions.getCollisionBoxes(eq(p), any(), anyList(), eq(false)))
                        .thenAnswer(call -> { ((List<SimpleCollisionBox>) call.getArgument(2)).addAll(blocks); return true; });
                c.when(() -> Collisions.isEmpty(eq(p), any())).thenAnswer(call -> {
                    SimpleCollisionBox box = call.getArgument(1);
                    return blocks.stream().noneMatch(b -> b.isIntersected(box));
                });
                double gravity = -0.0784000015258789;
                var incoming = data(-0.337847492019705, gravity, -0.035507928731333904);
                p.actualMovement = new Vector3dm(incoming.vector.getX(), 0, incoming.vector.getZ());
                var landing = Collisions.collide(p, incoming.vector.getX(), gravity, incoming.vector.getZ(), gravity, incoming);
                assertEquals(p.actualMovement, landing);
                p.boundingBox = p.boundingBox.copy().offset(landing.getX(), landing.getY(), landing.getZ());
                assertTrue(Collisions.isEmpty(p, p.boundingBox.copy().offset(0, -0.03, 0)));
                p.verticalCollision = true;
                p.predictedVelocity = new VectorData(landing, incoming, VectorData.VectorType.BestVelPicked);
                p.actualMovement = new Vector3dm(-0.31189068684824406, 1, -0.024545493637896265);
                var step = data(p.actualMovement.getX(), gravity, p.actualMovement.getZ());
                for (float height : new float[]{1, 2}) {
                    when(p.getAllowedStepHeight()).thenReturn(height);
                    assertEquals(p.actualMovement, Collisions.collide(p, step.vector.getX(), gravity, step.vector.getZ(), gravity, step), version.toString());
                }
                p.actualMovement.setY(3);
                assertTrue(Collisions.collide(p, step.vector.getX(), gravity, step.vector.getZ(), gravity, step).getY() < 1);
                p.actualMovement.setY(1);
                p.lastOnGround = false;
                assertTrue(Collisions.collide(p, step.vector.getX(), gravity, step.vector.getZ(), gravity, step).getY() < 0);
                p.lastOnGround = true;
                // A forged ground bit, ceiling collision, or following airborne tick cannot renew support.
                p.verticalCollision = false;
                assertTrue(Collisions.collide(p, step.vector.getX(), gravity, step.vector.getZ(), gravity, step).getY() < 0);
                p.verticalCollision = true;
                p.predictedVelocity = new VectorData(new Vector3dm(0, 0, 0), data(0, 0.42, 0), VectorData.VectorType.BestVelPicked);
                assertTrue(Collisions.collide(p, step.vector.getX(), gravity, step.vector.getZ(), gravity, step).getY() < 0);
                p.predictedVelocity = new VectorData(landing, incoming, VectorData.VectorType.BestVelPicked);
                blocks.add(new SimpleCollisionBox(207, -57.8, -2, 212, -56.8, 1));
                assertTrue(Collisions.collide(p, step.vector.getX(), gravity, step.vector.getZ(), gravity, step).getY() < 0.6);
            }
        }
    }

}
