package ac.grim.grimac.events.packets;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClientTickEnd;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PacketCriticalForwardingTest {
    @BeforeAll static void packets() {
        PacketCriticalsTest.initializePackets(ServerVersion.V_26_3);
    }

    @Test void criticalPairCanFollowAndPrecedeOrdinaryPositionInOneClientTick() throws Exception {
        var fixture = new PacketCriticalsTest.Fixture();
        var event = mock(PacketReceiveEvent.class);
        var user = mock(User.class);
        when(event.getServerVersion()).thenReturn(ServerVersion.V_26_3);
        when(event.getUser()).thenReturn(user);
        List<PacketWrapper<?>> forwarded = new ArrayList<>();
        doAnswer(call -> { forwarded.add(call.getArgument(0)); return null; })
                .when(user).receivePacketSilently(any(PacketWrapper.class));
        var up = fixture.packet(64.0625);
        var down = fixture.packet(64);
        CheckManagerListener.forwardPacketCritical(fixture.player, event, up);
        CheckManagerListener.forwardPacketCritical(fixture.player, event, down);
        assertEquals(6, forwarded.size());
        // The exact new vanilla rule: a second position before tick-end is invalid.
        boolean receivedPosition = true; // Ordinary client movement before the pair.
        int positions = 0;
        for (var packet : forwarded) {
            if (packet instanceof WrapperPlayClientClientTickEnd) {
                receivedPosition = false;
            } else if (packet instanceof WrapperPlayClientPlayerFlying flying) {
                assertFalse(receivedPosition, "26.3 would disconnect this second position packet");
                receivedPosition = true;
                assertEquals(positions++ == 0 ? 64.0625 : 64, flying.getLocation().getY());
                assertFalse(flying.isOnGround());
                assertFalse(flying.hasRotationChanged());
            } else fail("Unexpected injected packet");
        }
        assertEquals(2, positions);
        assertFalse(receivedPosition, "The next ordinary movement must still be legal");
        verify(event, times(2)).setCancelled(true);
        verify(fixture.player, never()).onPacketCancel();
        verify(up, never()).setOnGround(anyBoolean());
        verify(down, never()).setOnGround(anyBoolean());
    }

    @Test void olderServersKeepTheOriginalPacketPath() throws Exception {
        for (var version : new ServerVersion[]{ServerVersion.V_1_21_11, ServerVersion.V_26_1, ServerVersion.V_26_2}) {
            var fixture = new PacketCriticalsTest.Fixture();
            var event = mock(PacketReceiveEvent.class);
            when(event.getServerVersion()).thenReturn(version);
            CheckManagerListener.forwardPacketCritical(fixture.player, event, fixture.packet(64.0625));
            verify(event, never()).setCancelled(anyBoolean());
            verify(event, never()).getUser();
            verify(fixture.player, never()).onPacketCancel();
        }
    }

    @Test void rateLimitedPairsNeverReachTheServerOrGainSyntheticTicks() throws Exception {
        var fixture = new PacketCriticalsTest.Fixture();
        var event = mock(PacketReceiveEvent.class);
        fixture.player.packetStateData.packetCriticalDenied = true;
        CheckManagerListener.forwardPacketCritical(fixture.player, event, fixture.packet(64.0625));
        CheckManagerListener.forwardPacketCritical(fixture.player, event, fixture.packet(64));
        verify(event, times(2)).setCancelled(true);
        verify(fixture.player, times(2)).onPacketCancel();
        verify(event, never()).getUser();
    }
}
