package ac.grim.grimac.platform.bukkit.events;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
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

class GlideSwapGuardTest {
    final GlideSwapGuard guard = new GlideSwapGuard();
    final Player player = mock(Player.class);
    final PlayerInventory inventory = mock(PlayerInventory.class);
    final ItemStack elytra = mock(ItemStack.class), chestplate = mock(ItemStack.class);
    final Plugin plugin = mock(Plugin.class);
    final AtomicInteger tick = new AtomicInteger(100);
    MockedStatic<Bukkit> bukkit;

    @BeforeEach void setup() {
        when(player.getUniqueId()).thenReturn(UUID.randomUUID()); when(player.getInventory()).thenReturn(inventory);
        when(elytra.getType()).thenReturn(Material.ELYTRA); when(chestplate.getType()).thenReturn(Material.NETHERITE_CHESTPLATE);
        when(inventory.getChestplate()).thenReturn(elytra);
        bukkit = mockStatic(Bukkit.class); bukkit.when(Bukkit::getCurrentTick).thenAnswer(call -> tick.get());
        EntityToggleGlideEvent.getHandlerList().register(new RegisteredListener(guard,
                (listener, event) -> guard.onGlide((EntityToggleGlideEvent) event), EventPriority.HIGHEST, plugin, true));
    }
    @AfterEach void cleanup() {
        HandlerList.unregisterAll(guard); bukkit.close();
    }
    EntityToggleGlideEvent glide(boolean start) throws Exception {
        var e = new EntityToggleGlideEvent(player, start);
        for (var l : e.getHandlers().getRegisteredListeners()) l.callEvent(e);
        return e;
    }
    void armor(ItemStack old, ItemStack next) {
        when(inventory.getChestplate()).thenReturn(next);
        guard.onArmor(new PlayerArmorChangeEvent(player, PlayerArmorChangeEvent.SlotType.CHEST, old, next));
    }

    @Test void alreadyWornElytraAtJoinAndPluginReloadCanGlideNormally() throws Exception {
        guard.onJoin(new PlayerJoinEvent(player, "join"));
        for (int i = 0; i < 100; i++) assertFalse(glide(true).isCancelled());
        assertFalse(glide(false).isCancelled());
        var reloaded = new GlideSwapGuard(); reloaded.initialize(player);
        var e = new EntityToggleGlideEvent(player, true); reloaded.onGlide(e); assertFalse(e.isCancelled());
    }

    @Test void instantEquipStartUnequipBurstsCannotAgeIntoPermission() throws Exception {
        // FakeFly restores the chestplate before the server's equipment update observes any change.
        for (int i = 0; i < 100; i++) {
            when(inventory.getChestplate()).thenReturn(elytra);
            assertTrue(glide(true).isCancelled());
            when(inventory.getChestplate()).thenReturn(chestplate);
            tick.incrementAndGet();
        }
    }

    @Test void realEquipmentUpdateRequiresAFullTickAndThenAllowsFlight() throws Exception {
        armor(chestplate, elytra);
        assertTrue(glide(true).isCancelled()); tick.incrementAndGet();
        assertTrue(glide(true).isCancelled()); tick.incrementAndGet();
        assertFalse(glide(true).isCancelled());
    }

    @Test void restoringChestplateClearsPriorElytraPermission() throws Exception {
        guard.initialize(player);
        assertFalse(glide(true).isCancelled());
        armor(elytra, chestplate);
        tick.addAndGet(100);
        when(inventory.getChestplate()).thenReturn(elytra); // Transient re-equip; no stable equipment update yet.
        assertTrue(glide(true).isCancelled());
        armor(chestplate, elytra); tick.addAndGet(2);
        assertFalse(glide(true).isCancelled());
    }

    @Test void chestSlotBurstsInvalidateEvenAnAlreadyWornElytraBetweenEquipmentUpdates() throws Exception {
        guard.initialize(player);
        assertFalse(glide(true).isCancelled());
        var click = mock(org.bukkit.event.inventory.InventoryClickEvent.class);
        when(click.getWhoClicked()).thenReturn(player);
        when(click.getSlotType()).thenReturn(org.bukkit.event.inventory.InventoryType.SlotType.ARMOR);
        when(click.getSlot()).thenReturn(38);
        guard.onInventory(click);
        tick.addAndGet(100); // Repeated transient launches cannot reuse the old permission.
        assertTrue(glide(true).isCancelled());
        armor(elytra, elytra); tick.addAndGet(2);
        assertFalse(glide(true).isCancelled());
    }

    @Test void durabilityUpdatesAndOtherArmorSlotsDoNotResetWornTime() throws Exception {
        guard.initialize(player);
        armor(elytra, elytra);
        guard.onArmor(new PlayerArmorChangeEvent(player, PlayerArmorChangeEvent.SlotType.HEAD, elytra, chestplate));
        assertFalse(glide(true).isCancelled());
    }

    @Test void landingOtherGliderItemsAndOtherPluginCancellationsRemainUnchanged() throws Exception {
        assertFalse(glide(false).isCancelled());
        when(inventory.getChestplate()).thenReturn(chestplate); // Custom glider components remain native/Grim-owned.
        assertFalse(glide(true).isCancelled());
        when(inventory.getChestplate()).thenReturn(null); assertFalse(glide(true).isCancelled());
        var e = new EntityToggleGlideEvent(player, true); e.setCancelled(true);
        for (var l : e.getHandlers().getRegisteredListeners()) l.callEvent(e);
        assertTrue(e.isCancelled());
    }

    @Test void quitReleasesWornStateAndRegionTickChangesDoNotLockFlight() throws Exception {
        guard.initialize(player); tick.set(1); assertFalse(glide(true).isCancelled());
        guard.onQuit(new PlayerQuitEvent(player, "quit"));
        assertTrue(glide(true).isCancelled());
    }
}
