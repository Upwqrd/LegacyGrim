package ac.grim.grimac.events.packets;

import ac.grim.grimac.manager.SetbackTeleportUtil;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.PacketStateData;
import ac.grim.grimac.utils.latency.CompensatedEntities;
import ac.grim.grimac.utils.math.Vector3dm;
import ac.grim.grimac.utils.nmsutil.Collisions;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.potion.PotionTypes;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PacketCriticalsTest {
    @BeforeAll static void packets() {
        var api = mock(PacketEventsAPI.class); var server = mock(ServerManager.class);
        when(api.getServerManager()).thenReturn(server);
        when(server.getVersion()).thenReturn(ServerVersion.V_1_21_11);
        when(api.getSettings()).thenReturn(new com.github.retrooper.packetevents.settings.PacketEventsSettings());
        when(api.getNettyManager()).thenReturn(new io.github.retrooper.packetevents.impl.netty.NettyManagerImpl());
        when(api.getLogManager()).thenReturn(mock(com.github.retrooper.packetevents.util.LogManager.class));
        PacketEvents.setAPI(api);
    }

    @Test void exactCriticalPairsLeavePhysicsAnchorAndRawPacketUntouched() throws Exception {
        Fixture f = new Fixture();
        try (var collisions = mockStatic(Collisions.class)) {
            f.floor(collisions);
            for (int i = 0; i < 100; i++) {
                var up = f.packet(64.0625);
                var down = f.packet(64);
                assertTrue(f.accept(up)); assertTrue(f.player.packetStateData.packetCriticalRise);
                assertTrue(f.accept(down)); assertFalse(f.player.packetStateData.packetCriticalRise);
                assertEquals(64.0625, up.getLocation().getY()); assertFalse(up.isOnGround());
                assertEquals(64, f.player.y); assertEquals(0, f.player.x); assertEquals(0, f.player.z);
                assertEquals(-.0784, f.player.clientVelocity.getY());
                assertTrue(f.player.packetStateData.packetPlayerOnGround);
                verify(up, never()).setOnGround(anyBoolean());
            }
        }
    }

    @Test void recordedClientEpsilonPairsAreAcceptedRepeatedlyAtDifferentWorldHeights() throws Exception {
        Fixture f = new Fixture();
        try (var collisions = mockStatic(Collisions.class)) {
            for (double base : new double[]{-64, 0, 64, 319}) {
                f.player.y = base;
                f.player.boundingBox = new SimpleCollisionBox(-.3, base, -.3, .3, base + 1.8, .3);
                collisions.when(() -> Collisions.isEmpty(eq(f.player), any())).thenAnswer(call -> ((SimpleCollisionBox) call.getArgument(1)).maxY > base);
                for (int i = 0; i < 100; i++) {
                    for (double rise : new double[]{.0625, .0625101, .0626}) {
                        assertTrue(f.accept(f.packet(base + rise)));
                        assertTrue(f.accept(f.packet(base)));
                        assertEquals(base, f.player.y);
                    }
                }
            }
        }
    }

    @Test void ordinaryMovementAndOtherHeightsCannotReceiveTimerExemption() throws Exception {
        Fixture f = new Fixture();
        try (var collisions = mockStatic(Collisions.class)) {
            f.floor(collisions);
            for (double y : new double[]{64, 63.9375, 64.0000008, 64.062499, 64.062601, 64.11, 64.42, Double.NaN, Double.POSITIVE_INFINITY})
                assertFalse(f.accept(f.packet(y)));
            var moving = f.packet(64.0625); when(moving.getLocation()).thenReturn(new Location(.01, 64.0625, 0, 0, 0));
            assertFalse(f.accept(moving));
            var rotating = f.packet(64.0625); when(rotating.hasRotationChanged()).thenReturn(true); assertFalse(f.accept(rotating));
            var grounded = f.packet(64.0625); when(grounded.isOnGround()).thenReturn(true); assertFalse(f.accept(grounded));
        }
    }

    @Test void stackedRisesAndInterruptedPairsAreRejected() throws Exception {
        Fixture f = new Fixture();
        try (var collisions = mockStatic(Collisions.class)) {
            f.floor(collisions);
            assertTrue(f.accept(f.packet(64.0625)));
            assertFalse(f.accept(f.packet(64.125)));
            assertFalse(f.player.packetStateData.packetCriticalRise);
            assertFalse(f.accept(f.packet(64)));
            assertTrue(f.accept(f.packet(64.0625)));
            var normal = f.packet(64); when(normal.isOnGround()).thenReturn(true);
            assertFalse(f.accept(normal)); assertFalse(f.player.packetStateData.packetCriticalRise);
            assertFalse(f.accept(f.packet(64)));
        }
    }

    @Test void supportAndHeadClearanceAreRequired() throws Exception {
        Fixture f = new Fixture();
        try (var collisions = mockStatic(Collisions.class)) {
            collisions.when(() -> Collisions.isEmpty(eq(f.player), any())).thenReturn(true);
            assertFalse(f.accept(f.packet(64.0625))); // Air, even if an old state still says grounded.
            collisions.when(() -> Collisions.isEmpty(eq(f.player), any())).thenReturn(false);
            assertFalse(f.accept(f.packet(64.0625))); // Head collision / phase.
            f.floor(collisions);
            assertTrue(f.accept(f.packet(64.0625)));
        }
    }

    @Test void fallingJumpingFluidsVehiclesAndPotionPhysicsRemainChecked() throws Exception {
        Fixture f = new Fixture();
        try (var collisions = mockStatic(Collisions.class)) {
            f.floor(collisions);
            f.player.onGround = false; assertFalse(f.accept(f.packet(64.0625))); f.player.onGround = true;
            f.player.hasGravity = false; assertFalse(f.accept(f.packet(64.0625))); f.player.hasGravity = true;
            for (String name : new String[]{"isFlying", "isGliding", "isClimbing", "isSwimming", "wasTouchingWater", "wasTouchingLava"}) {
                var field = GrimPlayer.class.getField(name); field.setBoolean(f.player, true);
                assertFalse(f.accept(f.packet(64.0625))); field.setBoolean(f.player, false);
            }
            when(f.player.inVehicle()).thenReturn(true); assertFalse(f.accept(f.packet(64.0625))); when(f.player.inVehicle()).thenReturn(false);
            f.player.packetStateData.tryingToRiptide = true; assertFalse(f.accept(f.packet(64.0625))); f.player.packetStateData.tryingToRiptide = false;
            for (var effect : new com.github.retrooper.packetevents.protocol.potion.PotionType[]{PotionTypes.LEVITATION, PotionTypes.SLOW_FALLING}) {
                when(f.entities.hasPotionEffect(effect)).thenReturn(true); assertFalse(f.accept(f.packet(64.0625)));
                when(f.entities.hasPotionEffect(effect)).thenReturn(false);
            }
            f.player.clientVelocity.setY(.42); assertFalse(f.accept(f.packet(64.0625)));
        }
    }

    @Test void teleportsAndPendingSetbacksCannotUseTheCriticalPair() throws Exception {
        Fixture f = new Fixture();
        try (var collisions = mockStatic(Collisions.class)) {
            f.floor(collisions);
            assertTrue(f.accept(f.packet(64.0625)));
            f.player.packetStateData.lastPacketWasTeleport = true;
            assertFalse(f.accept(f.packet(64))); assertFalse(f.player.packetStateData.packetCriticalRise);
            f.player.packetStateData.lastPacketWasTeleport = false;
            when(f.setbacks.shouldBlockMovement()).thenReturn(true);
            assertFalse(f.accept(f.packet(64.0625)));
        }
    }

    static class Fixture {
        final GrimPlayer player = mock(GrimPlayer.class);
        final CompensatedEntities entities = mock(CompensatedEntities.class);
        final SetbackTeleportUtil setbacks = mock(SetbackTeleportUtil.class);
        Fixture() throws Exception {
            for (var entry : java.util.Map.of("packetStateData", new PacketStateData(), "compensatedEntities", entities).entrySet()) {
                var field = GrimPlayer.class.getField(entry.getKey()); field.setAccessible(true); field.set(player, entry.getValue());
            }
            when(player.getSetbackTeleportUtil()).thenReturn(setbacks);
            player.y = 64; player.onGround = true; player.hasGravity = true;
            player.packetStateData.packetPlayerOnGround = true;
            player.clientVelocity = new Vector3dm(0, -.0784, 0);
            player.boundingBox = new SimpleCollisionBox(-.3, 64, -.3, .3, 65.8, .3);
        }
        WrapperPlayClientPlayerFlying packet(double y) {
            var flying = mock(WrapperPlayClientPlayerFlying.class);
            when(flying.hasPositionChanged()).thenReturn(true);
            when(flying.getLocation()).thenReturn(new Location(0, y, 0, 0, 0));
            return flying;
        }
        boolean accept(WrapperPlayClientPlayerFlying packet) throws Exception {
            var method = CheckManagerListener.class.getDeclaredMethod("isPacketCritical", GrimPlayer.class, WrapperPlayClientPlayerFlying.class);
            method.setAccessible(true); return (boolean) method.invoke(null, player, packet);
        }
        void floor(org.mockito.MockedStatic<Collisions> collisions) {
            collisions.when(() -> Collisions.isEmpty(eq(player), any())).thenAnswer(call -> ((SimpleCollisionBox) call.getArgument(1)).maxY > 64);
        }
    }
}
