package ac.grim.grimac.platform.bukkit.events;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CrystalSwapGuardTest {
    final CrystalSwapGuard guard = new CrystalSwapGuard();
    final Player player = mock(Player.class);
    final PlayerInventory inventory = mock(PlayerInventory.class);
    final ItemStack main = mock(ItemStack.class), off = mock(ItemStack.class);
    final Plugin plugin = mock(Plugin.class);
    final AtomicInteger tick = new AtomicInteger(100);
    MockedStatic<Bukkit> bukkit;

    @BeforeEach void setup() {
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getItem(EquipmentSlot.HAND)).thenReturn(main);
        when(inventory.getItem(EquipmentSlot.OFF_HAND)).thenReturn(off);
        when(main.getType()).thenReturn(Material.END_CRYSTAL);
        when(off.getType()).thenReturn(Material.AIR);
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getCurrentTick).thenAnswer(call -> tick.get());
        PlayerItemHeldEvent.getHandlerList().register(new RegisteredListener(guard,
                (listener, event) -> guard.onSlotChange((PlayerItemHeldEvent) event), EventPriority.MONITOR, plugin, true));
        EntityPlaceEvent.getHandlerList().register(new RegisteredListener(guard,
                (listener, event) -> guard.onPlace((EntityPlaceEvent) event), EventPriority.HIGHEST, plugin, true));
    }

    @AfterEach void cleanup() { HandlerList.unregisterAll(guard); bukkit.close(); }

    static void dispatch(Event event) throws Exception {
        for (var listener : event.getHandlers().getRegisteredListeners()) listener.callEvent(event);
    }
    EntityPlaceEvent crystal(EquipmentSlot hand) throws Exception {
        var event = new EntityPlaceEvent(mock(EnderCrystal.class), player, mock(Block.class), BlockFace.UP, hand);
        dispatch(event); return event;
    }
    void select(int previous, int next) throws Exception { dispatch(new PlayerItemHeldEvent(player, previous, next)); }

    @Test void crystalAlreadyHeldHasNoRateLimit() throws Exception {
        for (int i = 0; i < 100; i++) assertFalse(crystal(EquipmentSlot.HAND).isCancelled());
        when(off.getType()).thenReturn(Material.END_CRYSTAL);
        for (int i = 0; i < 100; i++) assertFalse(crystal(EquipmentSlot.OFF_HAND).isCancelled());
    }

    @Test void instantSilentSlotSwapIsRejectedUntilAFullServerTickHasElapsed() throws Exception {
        select(0, 3);
        assertTrue(crystal(EquipmentSlot.HAND).isCancelled());
        // Client ticks/movement cannot advance this Bukkit tick.
        for (int i = 0; i < 30; i++) assertTrue(crystal(EquipmentSlot.HAND).isCancelled());
        select(3, 0); select(0, 3);
        assertTrue(crystal(EquipmentSlot.HAND).isCancelled());
        tick.incrementAndGet();
        assertTrue(crystal(EquipmentSlot.HAND).isCancelled()); // Adjacent ticks can be less than 1 ms apart.
        tick.incrementAndGet();
        assertFalse(crystal(EquipmentSlot.HAND).isCancelled());
        select(3, 0);
        assertTrue(crystal(EquipmentSlot.HAND).isCancelled());
    }

    @Test void alternativeInventorySwapDoesNotChangeSelectedSlotOrAddDelay() throws Exception {
        when(main.getType()).thenReturn(Material.NETHERITE_SWORD);
        assertTrue(crystal(EquipmentSlot.HAND).isCancelled());
        // A processed inventory SWAP changes contents, without a PlayerItemHeldEvent.
        when(main.getType()).thenReturn(Material.END_CRYSTAL);
        assertFalse(crystal(EquipmentSlot.HAND).isCancelled());
        when(main.getType()).thenReturn(Material.NETHERITE_SWORD);
        assertTrue(crystal(EquipmentSlot.HAND).isCancelled());
    }

    @Test void offhandUsesItsOwnActualItemAndIgnoresHotbarSwitches() throws Exception {
        select(0, 3);
        assertTrue(crystal(EquipmentSlot.OFF_HAND).isCancelled());
        when(off.getType()).thenReturn(Material.END_CRYSTAL);
        assertFalse(crystal(EquipmentSlot.OFF_HAND).isCancelled());
        when(main.getType()).thenReturn(Material.AIR);
        tick.incrementAndGet();
        assertTrue(crystal(EquipmentSlot.HAND).isCancelled());
        assertFalse(crystal(EquipmentSlot.OFF_HAND).isCancelled());
    }

    @Test void cancelledAndNoOpSlotChangesDoNotDelayPlacement() throws Exception {
        var cancelled = new PlayerItemHeldEvent(player, 0, 3); cancelled.setCancelled(true);
        dispatch(cancelled);
        select(0, 0);
        assertFalse(crystal(EquipmentSlot.HAND).isCancelled());
        var denied = new EntityPlaceEvent(mock(EnderCrystal.class), player, mock(Block.class), BlockFace.UP, EquipmentSlot.HAND);
        denied.setCancelled(true); dispatch(denied);
        assertTrue(denied.isCancelled());
    }

    @Test void nonCrystalsAndNonPlayerPlacementRemainUntouched() throws Exception {
        select(0, 3);
        var armorStand = new EntityPlaceEvent(mock(ArmorStand.class), player, mock(Block.class), BlockFace.UP, EquipmentSlot.HAND);
        dispatch(armorStand); assertFalse(armorStand.isCancelled());
        var automatic = new EntityPlaceEvent(mock(EnderCrystal.class), null, mock(Block.class), BlockFace.UP, EquipmentSlot.HAND);
        dispatch(automatic); assertFalse(automatic.isCancelled());
    }

    @Test void slotCooldownIsPerPlayerAndQuitClearsIt() throws Exception {
        Player other = mock(Player.class); when(other.getUniqueId()).thenReturn(UUID.randomUUID());
        dispatch(new PlayerItemHeldEvent(other, 0, 3));
        assertFalse(crystal(EquipmentSlot.HAND).isCancelled());
        select(0, 3); assertTrue(crystal(EquipmentSlot.HAND).isCancelled());
        guard.onQuit(new PlayerQuitEvent(player, "quit"));
        assertFalse(crystal(EquipmentSlot.HAND).isCancelled());
    }
}
