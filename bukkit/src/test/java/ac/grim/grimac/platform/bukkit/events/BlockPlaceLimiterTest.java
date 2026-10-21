package ac.grim.grimac.platform.bukkit.events;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BlockPlaceLimiterTest {
    final BlockPlaceLimiter limiter = new BlockPlaceLimiter();
    final Player player = mock(Player.class);
    final Block block = mock(Block.class), against = mock(Block.class);
    final BlockState replaced = mock(BlockState.class);
    final ItemStack item = mock(ItemStack.class);
    final Plugin plugin = mock(Plugin.class);
    final Listener otherPlugin = new Listener() {};
    final AtomicInteger tick = new AtomicInteger(100);
    MockedStatic<Bukkit> bukkit;

    @BeforeAll static void packets() {
        var api = mock(com.github.retrooper.packetevents.PacketEventsAPI.class);
        var server = mock(com.github.retrooper.packetevents.manager.server.ServerManager.class);
        when(api.getServerManager()).thenReturn(server);
        when(server.getVersion()).thenReturn(com.github.retrooper.packetevents.manager.server.ServerVersion.V_1_21_11);
        when(api.getSettings()).thenReturn(new com.github.retrooper.packetevents.settings.PacketEventsSettings());
        when(api.getNettyManager()).thenReturn(new io.github.retrooper.packetevents.impl.netty.NettyManagerImpl());
        when(api.getLogManager()).thenReturn(mock(com.github.retrooper.packetevents.util.LogManager.class));
        com.github.retrooper.packetevents.PacketEvents.setAPI(api);
    }

    @BeforeEach void setup() {
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(replaced.getBlock()).thenReturn(block);
        when(item.getType()).thenReturn(Material.STONE);
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getCurrentTick).thenAnswer(call -> tick.get());
        BlockPlaceEvent.getHandlerList().register(new RegisteredListener(limiter,
                (listener, event) -> limiter.onPlace((BlockPlaceEvent) event), EventPriority.HIGHEST, plugin, false));
        BlockPlaceEvent.getHandlerList().register(new RegisteredListener(limiter,
                (listener, event) -> limiter.onComplete((BlockPlaceEvent) event), EventPriority.MONITOR, plugin, false));
    }

    @AfterEach void cleanup() {
        HandlerList.unregisterAll(limiter);
        HandlerList.unregisterAll(otherPlugin);
        bukkit.close();
    }

    BlockPlaceEvent place(EquipmentSlot hand) {
        return new BlockPlaceEvent(block, replaced, against, item, player, true, hand);
    }

    BlockMultiPlaceEvent multi(EquipmentSlot hand) {
        return new BlockMultiPlaceEvent(List.of(replaced, replaced), against, item, player, true, hand);
    }

    void register(EventPriority priority, Consumer<BlockPlaceEvent> action) {
        BlockPlaceEvent.getHandlerList().register(new RegisteredListener(otherPlugin,
                (listener, event) -> action.accept((BlockPlaceEvent) event), priority, plugin, false));
    }

    static void dispatch(Event event) throws Exception {
        for (RegisteredListener listener : event.getHandlers().getRegisteredListeners()) listener.callEvent(event);
    }

    @Test void printerCanPlaceExactlyTwoEveryServerTickWithNoSavedBudget() throws Exception {
        for (int i = 0; i < 120; i++) {
            tick.incrementAndGet();
            for (int j = 0; j < 4; j++) {
                BlockPlaceEvent event = place(EquipmentSlot.HAND);
                dispatch(event);
                assertEquals(j >= 2, event.isCancelled());
            }
        }
        tick.addAndGet(10_000);
        for (int j = 0; j < 4; j++) {
            BlockPlaceEvent event = place(EquipmentSlot.HAND);
            dispatch(event);
            assertEquals(j >= 2, event.isCancelled());
        }
    }

    @Test void bothHandsAndAirPlacementShareOneBudget() throws Exception {
        BlockPlaceEvent main = place(EquipmentSlot.HAND), off = place(EquipmentSlot.OFF_HAND);
        BlockPlaceEvent air = new BlockPlaceEvent(block, replaced, mock(Block.class), item, player, true, EquipmentSlot.OFF_HAND);
        dispatch(main); dispatch(off); dispatch(air);
        assertFalse(main.isCancelled()); assertFalse(off.isCancelled()); assertTrue(air.isCancelled());
    }

    @Test void crystalsRemainUncancelledBeforeAndAfterExhaustingBlockBudget() throws Exception {
        when(item.getType()).thenReturn(Material.OBSIDIAN);
        ItemStack crystalItem = mock(ItemStack.class); when(crystalItem.getType()).thenReturn(Material.END_CRYSTAL);
        assertNull(com.github.retrooper.packetevents.protocol.item.type.ItemTypes.END_CRYSTAL.getPlacedType());
        for (int blocks = 0; blocks <= 2; blocks++) {
            for (EquipmentSlot hand : List.of(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND)) {
                for (int i = 0; i < 10; i++) {
                    var interaction = new PlayerInteractEvent(player, org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK,
                            crystalItem, against, org.bukkit.block.BlockFace.UP, hand);
                    var crystal = new EntityPlaceEvent(mock(org.bukkit.entity.EnderCrystal.class), player,
                            against, org.bukkit.block.BlockFace.UP, hand);
                    dispatch(interaction); dispatch(crystal);
                    assertFalse(interaction.isCancelled()); assertFalse(crystal.isCancelled());
                    assertNotEquals(Event.Result.DENY, interaction.useItemInHand());
                    assertNotEquals(Event.Result.DENY, interaction.useInteractedBlock());
                    // Even a plugin forwarding a crystal as BlockPlaceEvent cannot reach the cancel path.
                    var forwarded = new BlockPlaceEvent(block, replaced, against, crystalItem, player, true, hand);
                    limiter.onPlace(forwarded); limiter.onComplete(forwarded);
                    assertFalse(forwarded.isCancelled()); assertTrue(forwarded.canBuild());
                }
            }
            if (blocks < 2) {
                var obsidian = place(blocks == 0 ? EquipmentSlot.HAND : EquipmentSlot.OFF_HAND);
                dispatch(obsidian); assertFalse(obsidian.isCancelled());
            }
        }
        BlockPlaceEvent excess = place(EquipmentSlot.HAND);
        dispatch(excess); assertTrue(excess.isCancelled()); // Crystals never reset the exhausted budget either.
        bukkit.verify(Bukkit::getCurrentTick, times(3)); // Only the actual block attempts touch the tick counter.
    }

    @Test void nonPlacementItemChangesDoNotSpendBudgetButPlantingDoes() throws Exception {
        for (Material type : List.of(Material.NETHERITE_AXE, Material.HONEYCOMB, Material.BONE_MEAL)) {
            ItemStack tool = mock(ItemStack.class); when(tool.getType()).thenReturn(type);
            dispatch(new BlockPlaceEvent(block, replaced, against, tool, player, true, EquipmentSlot.HAND));
        }
        ItemStack seeds = mock(ItemStack.class); when(seeds.getType()).thenReturn(Material.WHEAT_SEEDS);
        BlockPlaceEvent plant = new BlockPlaceEvent(block, replaced, against, seeds, player, true, EquipmentSlot.HAND);
        dispatch(plant); assertFalse(plant.isCancelled());
        BlockPlaceEvent second = place(EquipmentSlot.HAND), third = place(EquipmentSlot.OFF_HAND);
        dispatch(second); dispatch(third);
        assertFalse(second.isCancelled()); assertTrue(third.isCancelled());
    }

    @Test void cancellationByEarlierOrLaterHighestListenerDoesNotSpendBudget() throws Exception {
        for (EventPriority priority : List.of(EventPriority.LOW, EventPriority.HIGHEST, EventPriority.MONITOR)) {
            tick.incrementAndGet();
            BlockPlaceEvent cancelled = place(EquipmentSlot.HAND);
            register(priority, event -> { if (event == cancelled) event.setCancelled(true); });
            dispatch(cancelled);
            assertTrue(cancelled.isCancelled());
            for (int i = 0; i < 3; i++) {
                BlockPlaceEvent next = place(EquipmentSlot.HAND); dispatch(next);
                assertEquals(i >= 2, next.isCancelled());
            }
            HandlerList.unregisterAll(otherPlugin);
        }
    }

    @Test void failedCanBuildAndLaterBuildDenialDoNotSpendBudget() throws Exception {
        BlockPlaceEvent failed = place(EquipmentSlot.HAND); failed.setBuild(false); dispatch(failed);
        BlockPlaceEvent denied = place(EquipmentSlot.HAND);
        register(EventPriority.HIGHEST, event -> { if (event == denied) event.setBuild(false); });
        dispatch(denied);
        for (int i = 0; i < 3; i++) {
            BlockPlaceEvent next = place(EquipmentSlot.HAND); dispatch(next);
            assertEquals(i >= 2, next.isCancelled());
        }
    }

    @Test void placementAllowedByALaterPluginStillConsumesItsReservation() throws Exception {
        for (boolean cancelled : List.of(false, true)) {
            tick.incrementAndGet();
            BlockPlaceEvent first = place(EquipmentSlot.HAND);
            if (cancelled) first.setCancelled(true); else first.setBuild(false);
            register(EventPriority.HIGHEST, event -> {
                if (event == first) { event.setCancelled(false); event.setBuild(true); }
            });
            dispatch(first); assertFalse(first.isCancelled()); assertTrue(first.canBuild());
            BlockPlaceEvent second = place(EquipmentSlot.OFF_HAND), third = place(EquipmentSlot.HAND);
            dispatch(second); dispatch(third);
            assertFalse(second.isCancelled()); assertTrue(third.isCancelled());
            HandlerList.unregisterAll(otherPlugin);
        }
    }

    @Test void inFlightReservationsPreventNestedPluginCallsFromAcceptingThree() throws Exception {
        BlockPlaceEvent outer = place(EquipmentSlot.HAND);
        List<BlockPlaceEvent> nested = new ArrayList<>();
        register(EventPriority.HIGHEST, event -> {
            if (event != outer) return;
            event.setCancelled(true); // A pending event may still be changed by the rest of this callback.
            try {
                for (int i = 0; i < 4; i++) {
                    BlockPlaceEvent child = place(EquipmentSlot.OFF_HAND); nested.add(child); dispatch(child);
                }
            } catch (Exception ex) { throw new RuntimeException(ex); }
            event.setCancelled(false);
        });
        dispatch(outer);
        assertFalse(outer.isCancelled());
        assertEquals(1, nested.stream().filter(event -> !event.isCancelled()).count());
        BlockPlaceEvent third = place(EquipmentSlot.HAND); dispatch(third); assertTrue(third.isCancelled());
    }

    @Test void multiBlockConstructionCostsOneAndIsCancelledAsAWhole() throws Exception {
        BlockMultiPlaceEvent door = multi(EquipmentSlot.HAND), bed = multi(EquipmentSlot.OFF_HAND), excess = multi(EquipmentSlot.HAND);
        dispatch(door); dispatch(bed); dispatch(excess);
        assertFalse(door.isCancelled()); assertFalse(bed.isCancelled()); assertTrue(excess.isCancelled());
        verify(replaced, never()).update(anyBoolean(), anyBoolean()); // Server owns the atomic rollback.
        verify(block, never()).setType(any());
    }

    @Test void delayedNetworkBatchGetsTwoForProcessingTickAndNeverQueuesTheRest() throws Exception {
        dispatch(place(EquipmentSlot.HAND)); dispatch(place(EquipmentSlot.HAND));
        tick.addAndGet(40); // The delayed batch is processed now, regardless of client tick markers.
        List<BlockPlaceEvent> batch = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            BlockPlaceEvent event = place(i % 2 == 0 ? EquipmentSlot.HAND : EquipmentSlot.OFF_HAND);
            batch.add(event); dispatch(event);
        }
        assertEquals(2, batch.stream().filter(event -> !event.isCancelled()).count());
        tick.incrementAndGet();
        dispatch(place(EquipmentSlot.HAND)); dispatch(place(EquipmentSlot.OFF_HAND));
        assertEquals(58, batch.stream().filter(BlockPlaceEvent::isCancelled).count());
        bukkit.verify(Bukkit::getGlobalRegionScheduler, never());
        bukkit.verify(Bukkit::getOnlinePlayers, never());
        verifyNoInteractions(block, against);
    }

    @Test void countersAreIndependentAndQuitReleasesReferences() throws Exception {
        dispatch(place(EquipmentSlot.HAND)); dispatch(place(EquipmentSlot.HAND));
        Player second = mock(Player.class); when(second.getUniqueId()).thenReturn(UUID.randomUUID());
        BlockPlaceEvent other = new BlockPlaceEvent(block, replaced, against, item, second, true, EquipmentSlot.HAND);
        dispatch(other); assertFalse(other.isCancelled());
        limiter.onQuit(new PlayerQuitEvent(player, "quit"));
        var field = BlockPlaceLimiter.class.getDeclaredField("budgets"); field.setAccessible(true);
        assertEquals(1, ((Map<?, ?>) field.get(limiter)).size());
    }

    @Test void concurrentAttemptsReserveAtMostTwoSlots() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8), start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) results.add(workers.submit(() -> {
                try (MockedStatic<Bukkit> local = mockStatic(Bukkit.class)) {
                    local.when(Bukkit::getCurrentTick).thenReturn(100);
                    ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS));
                    BlockPlaceEvent event = place(EquipmentSlot.HAND);
                    limiter.onPlace(event); limiter.onComplete(event);
                    return !event.isCancelled();
                }
            }));
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            int accepted = 0;
            for (Future<Boolean> result : results) if (result.get(10, TimeUnit.SECONDS)) accepted++;
            assertEquals(2, accepted);
        } finally { start.countDown(); workers.shutdownNow(); }
    }
}
