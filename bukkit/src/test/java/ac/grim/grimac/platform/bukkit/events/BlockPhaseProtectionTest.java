package ac.grim.grimac.platform.bukkit.events;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.platform.api.PlatformLoader;
import ac.grim.grimac.platform.api.scheduler.EntityScheduler;
import ac.grim.grimac.platform.api.scheduler.PlatformScheduler;
import ac.grim.grimac.platform.api.scheduler.TaskHandle;
import ac.grim.grimac.platform.bukkit.utils.reflection.PaperUtils;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.settings.PacketEventsSettings;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.bukkit.util.VoxelShape;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BlockPhaseProtectionTest {
    World world;
    Player player;
    ConfigManager config;
    EntityScheduler scheduler;
    TaskHandle task;
    Block air;
    final Map<String, Block> blocks = new HashMap<>();
    final AtomicReference<Location> position = new AtomicReference<>();
    final AtomicReference<Runnable> afterTeleport = new AtomicReference<>();
    MockedStatic<Bukkit> bukkit;
    MockedStatic<PaperUtils> paper;
    BlockPhaseProtection protection;

    @BeforeAll
    static void packets() {
        PacketEventsAPI<?> api = mock(PacketEventsAPI.class);
        ServerManager server = mock(ServerManager.class);
        when(server.getVersion()).thenReturn(ServerVersion.V_1_21_11);
        when(api.getServerManager()).thenReturn(server);
        when(api.getSettings()).thenReturn(new PacketEventsSettings());
        PacketEvents.setAPI(api);
    }

    @BeforeEach
    void setup() throws Exception {
        world = mock(World.class);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        air = mock(Block.class);
        when(air.isEmpty()).thenReturn(true);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call ->
                blocks.getOrDefault(call.getArgument(0) + ":" + call.getArgument(1) + ":" + call.getArgument(2), air));
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        position.set(new Location(world, 0, 64, 0));
        when(player.getLocation()).thenAnswer(call -> position.get().clone());
        when(player.getEyeLocation()).thenAnswer(call -> position.get().clone().add(0, 1.62, 0));
        when(player.getBoundingBox()).thenAnswer(call -> {
            Location at = position.get();
            return new BoundingBox(at.getX() - 0.3, at.getY(), at.getZ() - 0.3,
                    at.getX() + 0.3, at.getY() + 1.8, at.getZ() + 0.3);
        });
        config = mock(ConfigManager.class);
        when(config.getBooleanElse(anyString(), anyBoolean())).thenAnswer(call -> call.getArgument(1));
        set(GrimAPI.INSTANCE.getConfigManager(), "config", config);
        PlatformLoader loader = mock(PlatformLoader.class);
        PlatformScheduler platform = mock(PlatformScheduler.class);
        scheduler = mock(EntityScheduler.class);
        task = mock(TaskHandle.class);
        when(loader.getScheduler()).thenReturn(platform);
        when(platform.getEntityScheduler()).thenReturn(scheduler);
        when(scheduler.runAtFixedRate(any(), any(), any(), any(), anyLong(), anyLong())).thenReturn(task);
        doAnswer(call -> { afterTeleport.set(call.getArgument(2)); return null; })
                .when(scheduler).execute(any(), any(), any(), any(), anyLong());
        set(GrimAPI.INSTANCE, "loader", loader);
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(any(World.class), anyInt(), anyInt())).thenReturn(true);
        paper = mockStatic(PaperUtils.class);
        paper.when(() -> PaperUtils.teleportAsync(any(), any())).thenReturn(CompletableFuture.completedFuture(true));
        protection = new BlockPhaseProtection();
        protection.watch(player);
    }

    @AfterEach
    void close() {
        paper.close();
        bukkit.close();
    }

    @Test
    void normalFloorContactAndRealShapeGapsAreNotEmbedded() {
        block(0, 63, 0, Material.STONE, new BoundingBox(0, 0, 0, 1, 1, 1));
        assertEquals(0, BlockPhaseProtection.obstructions(player, position.get()));
        block(0, 64, 0, Material.STONE, new BoundingBox(0, 0, 0, 1, 0.5, 1),
                new BoundingBox(0.5, 0.5, 0, 1, 1, 1));
        position.set(new Location(world, 0.15, 64.55, 0.15));
        when(player.getBoundingBox()).thenReturn(new BoundingBox(0.1, 64.55, 0.1, 0.2, 65.15, 0.2));
        assertEquals(0, BlockPhaseProtection.obstructions(player, position.get()));
        protection.check(player, false);
        paper.verifyNoInteractions();
    }

    @Test
    void actualCollisionShapesIncludeFenceFromTheBlockBelow() {
        position.set(new Location(world, 0.5, 64.2, 0.5));
        block(0, 63, 0, Material.OAK_FENCE, new BoundingBox(0.375, 0, 0.375, 0.625, 1.5, 0.625));
        assertEquals(2, BlockPhaseProtection.obstructions(player, position.get()));
        position.set(new Location(world, 0.5, 64.5, 0.5));
        assertEquals(0, BlockPhaseProtection.obstructions(player, position.get()));
    }

    @Test
    void gravityBlocksAreIgnoredOnlyForPushOutAndCannotHideAStoneIntersection() {
        for (Material material : List.of(Material.SAND, Material.GRAVEL, Material.RED_SAND,
                Material.WHITE_CONCRETE_POWDER, Material.ANVIL)) {
            block(0, 64, 0, material, new BoundingBox(0, 0, 0, 1, 1, 1));
            assertEquals(1, BlockPhaseProtection.obstructions(player, position.get()));
            protection.check(player, false);
        }
        paper.verifyNoInteractions();
        block(0, 65, 0, Material.STONE, new BoundingBox(0, 0, 0, 1, 1, 1));
        assertEquals(2, BlockPhaseProtection.obstructions(player, position.get()));
    }

    @Test
    void chunkAndRegionOwnershipAreCheckedWithoutLoadingOrReadingForeignBlocks() {
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);
        assertEquals(2, BlockPhaseProtection.obstructions(player, position.get()));
        verify(world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(any(World.class), anyInt(), anyInt())).thenReturn(false);
        assertEquals(2, BlockPhaseProtection.obstructions(player, position.get()));
        verify(world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
    }

    @Test
    void embeddedPlayerIsLiftedAtTheArrivalPositionWithoutRollingBack() {
        position.set(new Location(world, -2, 64, 0));
        protection.check(player, false);
        paper.verifyNoInteractions();
        position.set(new Location(world, 0, 64, 0));
        block(0, 64, 0, Material.STONE, new BoundingBox(0, 0, 0, 1, 1, 1));
        protection.check(player, false);
        paper.verify(() -> PaperUtils.teleportAsync(eq(player), argThat(to -> to.getX() == 0 && to.getY() == 65 && to.getZ() == 0)));
    }

    @Test
    void pushToggleSpectatorAndDeadPlayersDoNotTeleport() {
        block(0, 64, 0, Material.STONE, new BoundingBox(0, 0, 0, 1, 1, 1));
        when(config.getBooleanElse("PushOutOfBlocks", true)).thenReturn(false);
        protection.check(player, false);
        when(config.getBooleanElse("PushOutOfBlocks", true)).thenReturn(true);
        when(player.getGameMode()).thenReturn(GameMode.SPECTATOR);
        protection.check(player, false);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.isDead()).thenReturn(true);
        protection.check(player, false);
        paper.verifyNoInteractions();
    }

    @Test
    void pearlsNearWallsAreCancelledAndClearThrowsAndDisabledProtectionAreAllowed() {
        EnderPearl pearl = mock(EnderPearl.class);
        when(pearl.getShooter()).thenReturn(player);
        when(pearl.getVelocity()).thenAnswer(call -> position.get().getDirection().multiply(1.5));
        position.set(new Location(world, 0.5, 64, 0.3, 180, 0));
        block(0, 64, -1, Material.OBSIDIAN, new BoundingBox(0, 0, 0, 1, 1, 1));
        block(0, 65, -1, Material.OBSIDIAN, new BoundingBox(0, 0, 0, 1, 1, 1));
        ProjectileLaunchEvent event = new ProjectileLaunchEvent(pearl);
        protection.onLaunch(event);
        assertTrue(event.isCancelled());
        blocks.clear();
        event = new ProjectileLaunchEvent(pearl);
        protection.onLaunch(event);
        assertFalse(event.isCancelled());
        when(config.getBooleanElse("PearlPhasePrevention", true)).thenReturn(false);
        block(0, 64, 0, Material.STONE, new BoundingBox(0, 0, 0, 1, 1, 1));
        event = new ProjectileLaunchEvent(pearl);
        protection.onLaunch(event);
        assertFalse(event.isCancelled());
    }

    @Test
    void unsafePearlArrivalIsCheckedOnTheEntitySchedulerEvenWhenGeneralPushIsDisabled() {
        when(config.getBooleanElse("PushOutOfBlocks", true)).thenReturn(false);
        Location from = position.get().clone();
        Location to = from.clone().add(10, 0, 0);
        protection.onTeleport(new PlayerTeleportEvent(player, from, to, PlayerTeleportEvent.TeleportCause.ENDER_PEARL));
        paper.verifyNoInteractions();
        assertNotNull(afterTeleport.get());
        position.set(to);
        block(10, 64, 0, Material.STONE, new BoundingBox(0, 0, 0, 1, 1, 1));
        afterTeleport.get().run();
        paper.verify(() -> PaperUtils.teleportAsync(eq(player), argThat(destination -> destination.getX() == to.getX() && destination.getY() == 65)));
    }

    @Test
    void pendingAsyncCorrectionsCannotFloodTeleportsAndTasksAreCancelledOnQuit() {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        paper.when(() -> PaperUtils.teleportAsync(any(), any())).thenReturn(future);
        position.set(new Location(world, -2, 64, 0));
        protection.check(player, false);
        position.set(new Location(world, 0, 64, 0));
        block(0, 64, 0, Material.STONE, new BoundingBox(0, 0, 0, 1, 1, 1));
        for (int tick = 0; tick < 20; tick++) protection.check(player, false);
        paper.verify(() -> PaperUtils.teleportAsync(any(), any()), times(1));
        future.complete(false);
        protection.check(player, false);
        paper.verify(() -> PaperUtils.teleportAsync(any(), any()), times(2));
        protection.onQuit(new PlayerQuitEvent(player, ""));
        verify(task).cancel();
    }

    @Test
    void upToTwoBlockRoofLiftsVerticallyAndThreeOrMoreLeavesThePlayerInPlace() {
        for (int depth = 1; depth <= 5; depth++) {
            blocks.clear();
            for (int y = 64; y < 64 + depth; y++)
                block(0, y, 0, Material.STONE, new BoundingBox(0, 0, 0, 1, 1, 1));
            paper.clearInvocations();
            protection.check(player, true);
            if (depth <= 2) {
                int expectedY = 64 + depth;
                paper.verify(() -> PaperUtils.teleportAsync(eq(player),
                        argThat(to -> to.getX() == 0 && to.getZ() == 0 && to.getY() == expectedY)));
            } else paper.verifyNoInteractions();
        }
    }

    @Test
    void closeWallRightClicksAreDeniedBeforeItemUseForBothAirAndBlockInteractions() {
        var item = mock(org.bukkit.inventory.ItemStack.class);
        when(item.getType()).thenReturn(Material.ENDER_PEARL);
        position.set(new Location(world, 0.5, 64, 0.3, 180, 0));
        block(0, 64, -1, Material.OBSIDIAN, new BoundingBox(0, 0, 0, 1, 1, 1));
        block(0, 65, -1, Material.OBSIDIAN, new BoundingBox(0, 0, 0, 1, 1, 1));
        for (var action : List.of(org.bukkit.event.block.Action.RIGHT_CLICK_AIR, org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK)) {
            var event = mock(org.bukkit.event.player.PlayerInteractEvent.class);
            when(event.getItem()).thenReturn(item);
            when(event.getPlayer()).thenReturn(player);
            when(event.getAction()).thenReturn(action);
            when(event.useItemInHand()).thenReturn(org.bukkit.event.Event.Result.DEFAULT);
            // Even a pre-cancelled air interaction must deny the item explicitly.
            when(event.isCancelled()).thenReturn(true);
            protection.onInteract(event);
            verify(event).setUseItemInHand(org.bukkit.event.Event.Result.DENY);
            verify(event, never()).setCancelled(anyBoolean());
        }
        var disabled = mock(org.bukkit.event.player.PlayerInteractEvent.class);
        when(disabled.getItem()).thenReturn(item);
        when(disabled.getAction()).thenReturn(org.bukkit.event.block.Action.RIGHT_CLICK_AIR);
        when(config.getBooleanElse("PearlPhasePrevention", true)).thenReturn(false);
        protection.onInteract(disabled);
        verify(disabled, never()).setUseItemInHand(any());
    }

    @Test
    void wallContactBlocksDownwardAndSidewaysPearlsEvenWhenTheEyeRayMisses() {
        var item = mock(org.bukkit.inventory.ItemStack.class);
        when(item.getType()).thenReturn(Material.ENDER_PEARL);
        position.set(new Location(world, 0.5, 64, 0.3, 180, 79.2F));
        block(0, 64, -1, Material.OBSIDIAN, new BoundingBox(0, 0, 0, 1, 1, 1));
        assertEquals(0, BlockPhaseProtection.obstructions(player, position.get()));
        var event = mock(org.bukkit.event.player.PlayerInteractEvent.class);
        when(event.getItem()).thenReturn(item);
        when(event.getPlayer()).thenReturn(player);
        when(event.getAction()).thenReturn(org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK);
        protection.onInteract(event);
        verify(event).setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        EnderPearl pearl = mock(EnderPearl.class);
        when(pearl.getShooter()).thenReturn(player);
        when(pearl.getVelocity()).thenAnswer(call -> position.get().getDirection().multiply(1.5));
        when(pearl.getVelocity()).thenReturn(new Vector(0, -1.4759, -0.2691));
        ProjectileLaunchEvent launch = new ProjectileLaunchEvent(pearl);
        protection.onLaunch(launch);
        assertTrue(launch.isCancelled());
        // Contact with a floor alone must still allow ordinary throws.
        blocks.clear();
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
            block(x, 63, z, Material.STONE, new BoundingBox(0, 0, 0, 1, 1, 1));
        var clear = new ProjectileLaunchEvent(pearl);
        protection.onLaunch(clear);
        assertFalse(clear.isCancelled());
        // A nearby ceiling only blocks throws that actually head into it.
        block(0, 66, 0, Material.OBSIDIAN, new BoundingBox(0, 0, 0, 1, 1, 1));
        launch = new ProjectileLaunchEvent(pearl);
        protection.onLaunch(launch);
        assertFalse(launch.isCancelled());
        when(pearl.getVelocity()).thenReturn(new Vector(0, 1.5, 0));
        launch = new ProjectileLaunchEvent(pearl);
        protection.onLaunch(launch);
        assertTrue(launch.isCancelled());
    }

    @Test
    void pearlsAreAllowedOneBlockFromTheWallSurfaceForBothClickAndLaunch() {
        var item = mock(org.bukkit.inventory.ItemStack.class);
        when(item.getType()).thenReturn(Material.ENDER_PEARL);
        block(0, 64, -1, Material.OBSIDIAN, new BoundingBox(0, 0, 0, 1, 1, 1));
        block(0, 65, -1, Material.OBSIDIAN, new BoundingBox(0, 0, 0, 1, 1, 1));
        EnderPearl pearl = mock(EnderPearl.class);
        when(pearl.getShooter()).thenReturn(player);
        when(pearl.getVelocity()).thenAnswer(call -> position.get().getDirection().multiply(1.5));
        for (double distance : new double[]{0.3, 1, 1.3, 2}) {
            for (float pitch : new float[]{0, 79.2F}) {
                position.set(new Location(world, 0.5, 64, distance, 180, pitch));
                when(pearl.getVelocity()).thenReturn(position.get().getDirection().multiply(1.5));
                var click = mock(org.bukkit.event.player.PlayerInteractEvent.class);
                when(click.getItem()).thenReturn(item);
                when(click.getPlayer()).thenReturn(player);
                when(click.getAction()).thenReturn(org.bukkit.event.block.Action.RIGHT_CLICK_AIR);
                protection.onInteract(click);
                var launch = new ProjectileLaunchEvent(pearl);
                protection.onLaunch(launch);
                assertEquals(distance < 1, launch.isCancelled(), "distance=" + distance + " pitch=" + pitch);
                verify(click, times(distance < 1 ? 1 : 0)).setUseItemInHand(org.bukkit.event.Event.Result.DENY);
            }
        }
    }

    @Test
    void cobwebAndGravityBlocksAllowPearlsButDoNotExemptNearbySolidWalls() {
        var item = mock(org.bukkit.inventory.ItemStack.class);
        when(item.getType()).thenReturn(Material.ENDER_PEARL);
        EnderPearl pearl = mock(EnderPearl.class);
        when(pearl.getShooter()).thenReturn(player);
        when(pearl.getVelocity()).thenAnswer(call -> position.get().getDirection().multiply(1.5));
        position.set(new Location(world, 0.5, 64, 0.3, 180, 79.2F));
        for (Material material : List.of(Material.COBWEB, Material.SAND, Material.GRAVEL,
                Material.RED_SAND, Material.WHITE_CONCRETE_POWDER, Material.ANVIL, Material.WATER, Material.LAVA)) {
            blocks.clear();
            // Cobweb slows movement but has no solid collision shape.
            if (material == Material.COBWEB) block(0, 64, 0, material);
            else block(0, 64, 0, material, new BoundingBox(0, 0, 0, 1, 1, 1));
            var click = mock(org.bukkit.event.player.PlayerInteractEvent.class);
            when(click.getItem()).thenReturn(item);
            when(click.getPlayer()).thenReturn(player);
            when(click.getAction()).thenReturn(org.bukkit.event.block.Action.RIGHT_CLICK_AIR);
            protection.onInteract(click);
            verify(click, never()).setUseItemInHand(any());
            var launch = new ProjectileLaunchEvent(pearl);
            protection.onLaunch(launch);
            assertFalse(launch.isCancelled(), material.name());
            protection.check(player, true);
            paper.verifyNoInteractions();
            block(0, 64, -1, Material.OBSIDIAN, new BoundingBox(0, 0, 0, 1, 1, 1));
            launch = new ProjectileLaunchEvent(pearl);
            protection.onLaunch(launch);
            assertTrue(launch.isCancelled(), material.name() + " beside a real wall");
        }
        verify(world, never()).rayTraceBlocks(any(), any(), anyDouble(), any(), anyBoolean());
    }

    @Test
    void wallBehindThePlayerDoesNotBlockSidewaysOrSkyThrowsIncludingInLiquids() {
        var item = mock(org.bukkit.inventory.ItemStack.class);
        when(item.getType()).thenReturn(Material.ENDER_PEARL);
        EnderPearl pearl = mock(EnderPearl.class);
        when(pearl.getShooter()).thenReturn(player);
        when(pearl.getVelocity()).thenAnswer(call -> position.get().getDirection().multiply(1.5));
        for (Material material : List.of(Material.AIR, Material.COBWEB, Material.WATER, Material.LAVA, Material.SAND)) {
            blocks.clear();
            if (material != Material.AIR) block(0, 64, 0, material,
                    material == Material.SAND ? new BoundingBox(0, 0, 0, 1, 1, 1) : new BoundingBox(0, 0, 0, 0, 0, 0));
            block(0, 64, -1, Material.OBSIDIAN, new BoundingBox(0, 0, 0, 1, 1, 1));
            block(0, 65, -1, Material.OBSIDIAN, new BoundingBox(0, 0, 0, 1, 1, 1));
            for (float[] look : new float[][]{{0, 0}, {0, -80}, {-90, 0}, {90, -60}}) {
                position.set(new Location(world, 0.5, 64, 0.3, look[0], look[1]));
                var click = mock(org.bukkit.event.player.PlayerInteractEvent.class);
                when(click.getItem()).thenReturn(item);
                when(click.getPlayer()).thenReturn(player);
                when(click.getAction()).thenReturn(org.bukkit.event.block.Action.RIGHT_CLICK_AIR);
                protection.onInteract(click);
                verify(click, never()).setUseItemInHand(any());
                var launch = new ProjectileLaunchEvent(pearl);
                protection.onLaunch(launch);
                assertFalse(launch.isCancelled(), material.name() + " yaw=" + look[0] + " pitch=" + look[1]);
            }
        }
    }

    @Test
    void quittingDuringWatcherRegistrationCancelsTheReturnedTask() {
        BlockPhaseProtection fresh = new BlockPhaseProtection();
        doAnswer(call -> {
            fresh.onQuit(new org.bukkit.event.player.PlayerQuitEvent(player, "quit"));
            return task;
        }).when(scheduler).runAtFixedRate(any(), any(), any(), any(), anyLong(), anyLong());
        fresh.watch(player);
        verify(task).cancel();
    }

    @Test
    void retiredEntityCannotRetainAWatcherEntry() throws Exception {
        BlockPhaseProtection fresh = new BlockPhaseProtection();
        when(scheduler.runAtFixedRate(any(), any(), any(), any(), anyLong(), anyLong())).thenReturn(null);
        fresh.watch(player);
        var field = BlockPhaseProtection.class.getDeclaredField("states"); field.setAccessible(true);
        assertTrue(((Map<?, ?>) field.get(fresh)).isEmpty());
    }

    void block(int x, int y, int z, Material material, BoundingBox... shapes) {
        Block block = mock(Block.class);
        VoxelShape shape = mock(VoxelShape.class);
        Material type = mock(Material.class);
        when(type.hasGravity()).thenReturn(Set.of(Material.SAND, Material.GRAVEL, Material.RED_SAND,
                Material.WHITE_CONCRETE_POWDER, Material.ANVIL).contains(material));
        when(block.getType()).thenReturn(type);
        when(block.isLiquid()).thenReturn(material == Material.WATER || material == Material.LAVA);
        when(block.getCollisionShape()).thenReturn(shape);
        when(shape.getBoundingBoxes()).thenReturn(List.of(shapes));
        blocks.put(x + ":" + y + ":" + z, block);
    }

    static void set(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
