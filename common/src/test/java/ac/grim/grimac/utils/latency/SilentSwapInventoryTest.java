package ac.grim.grimac.utils.latency;

import ac.grim.grimac.checks.GrimProcessor;
import ac.grim.grimac.platform.api.player.PlatformInventory;
import ac.grim.grimac.platform.api.player.PlatformPlayer;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.inventory.Inventory;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SilentSwapInventoryTest {
    @BeforeAll static void packets() {
        PacketEventsAPI<?> api = mock(PacketEventsAPI.class);
        ServerManager server = mock(ServerManager.class);
        when(api.getServerManager()).thenReturn(server);
        when(api.getSettings()).thenReturn(new com.github.retrooper.packetevents.settings.PacketEventsSettings());
        when(api.getNettyManager()).thenReturn(new io.github.retrooper.packetevents.impl.netty.NettyManagerImpl());
        when(api.getLogManager()).thenReturn(mock(com.github.retrooper.packetevents.util.LogManager.class));
        when(server.getVersion()).thenReturn(ServerVersion.V_1_21_11);
        PacketEvents.setAPI(api);
    }

    @Test void silentSwapUsesAuthoritativeContentsOfPacketSelectedSlotWhenPacketInventoryIsDisabled() throws Exception {
        GrimPlayer player = mock(GrimPlayer.class);
        PlatformPlayer platform = mock(PlatformPlayer.class);
        PlatformInventory server = mock(PlatformInventory.class);
        CompensatedInventory inventory = mock(CompensatedInventory.class, CALLS_REAL_METHODS);
        Inventory cache = mock(Inventory.class);
        var owner = GrimProcessor.class.getDeclaredField("player"); owner.setAccessible(true); owner.set(inventory, player);
        var platformField = GrimPlayer.class.getDeclaredField("platformPlayer");
        platformField.setAccessible(true); platformField.set(player, platform);
        var cacheField = CompensatedInventory.class.getDeclaredField("inventory");
        cacheField.setAccessible(true); cacheField.set(inventory, cache);
        when(platform.getInventory()).thenReturn(server);
        ItemStack block = ItemStack.builder().type(ItemTypes.ENDER_CHEST).amount(1).build();
        ItemStack pickaxe = ItemStack.builder().type(ItemTypes.NETHERITE_PICKAXE).amount(1).build();
        when(server.getItemInHand()).thenReturn(block); // Folia has not processed the slot-change packet yet.
        when(server.getStack(5, 41)).thenReturn(block);
        when(server.getStack(1, 37)).thenReturn(pickaxe);
        when(cache.getHeldItem()).thenReturn(ItemStack.EMPTY); // Unsupported menu: cached contents cannot be trusted.
        AtomicInteger selected = new AtomicInteger(5);
        when(cache.getSelected()).thenAnswer(call -> selected.get());
        doAnswer(call -> { selected.set(call.getArgument(0)); return null; }).when(cache).setSelected(anyInt());
        inventory.isPacketInventoryActive = false;

        assertSame(block, inventory.getHeldItem());
        select(inventory, 1);
        assertSame(pickaxe, inventory.getHeldItem());
        select(inventory, 5);
        assertSame(block, inventory.getHeldItem());
        verify(cache, never()).getHeldItem();
        verify(server, never()).getItemInHand();

        // Removing the actual pickaxe cannot leave a cached tool usable for breaking.
        when(server.getStack(1, 37)).thenReturn(null);
        select(inventory, 1);
        assertSame(ItemStack.EMPTY, inventory.getHeldItem());
    }

    private static void select(CompensatedInventory inventory, int slot) {
        PacketReceiveEvent event = mock(PacketReceiveEvent.class);
        when(event.getPacketType()).thenReturn(PacketType.Play.Client.HELD_ITEM_CHANGE);
        try (var construction = mockConstruction(WrapperPlayClientHeldItemChange.class,
                (packet, context) -> when(packet.getSlot()).thenReturn(slot))) {
            inventory.onPacketReceive(event);
        }
    }
}
