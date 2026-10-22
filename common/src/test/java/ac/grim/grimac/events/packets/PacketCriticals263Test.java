package ac.grim.grimac.events.packets;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import org.junit.jupiter.api.BeforeAll;

/** Run the complete committed packet-critical regression suite on the 26.3 server protocol too. */
class PacketCriticals263Test extends PacketCriticalsTest {
    @BeforeAll static void packets() {
        initializePackets(ServerVersion.V_26_3);
    }
}
