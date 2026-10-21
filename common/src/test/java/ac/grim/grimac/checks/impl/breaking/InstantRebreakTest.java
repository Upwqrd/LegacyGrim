package ac.grim.grimac.checks.impl.breaking;

import ac.grim.grimac.checks.GrimProcessor;
import ac.grim.grimac.events.packets.CheckManagerListener;
import ac.grim.grimac.manager.CheckManager;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.BlockBreak;
import ac.grim.grimac.utils.data.BlockPrediction;
import ac.grim.grimac.utils.latency.CompensatedWorld;
import ac.grim.grimac.utils.latency.CompensatedInventory;
import ac.grim.grimac.utils.nmsutil.BlockBreakSpeed;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InstantRebreakTest {
    private static final Vector3i POS = new Vector3i(2, 64, 2), OTHER = new Vector3i(3, 64, 2);

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

    @Test void obsidianRebreakIsCancelledAndResyncedWithoutFlaggingOrPredictingAir() throws Exception {
        Fixture f = new Fixture();
        f.firstBreak(StateTypes.OBSIDIAN);
        clearInvocations(f.player, f.manager, f.world);
        f.state(StateTypes.AIR, StateTypes.OBSIDIAN);
        for (int i = 0; i < 20; i++) {
            PacketReceiveEvent packet = f.send(DiggingAction.FINISHED_DIGGING, POS);
            verify(packet).setCancelled(true);
        }
        verify(f.player, times(20)).resyncPosition(POS, 37);
        verify(f.player, times(20)).onPacketCancel();
        verify(f.manager, never()).onBlockBreak(any());
        verify(f.world, never()).startPredicting();
        assertEquals(0, f.check.violations);
    }

    @Test void fullProgressSilentToolSwapIsRecognizedAtStopForBothHardBlocks() throws Exception {
        for (StateType type : List.of(StateTypes.OBSIDIAN, StateTypes.ENDER_CHEST)) {
            Fixture f = new Fixture();
            f.state(type, type);
            f.damage = .0002; // No pickaxe during START or subsequent movement/animation packets.
            f.send(DiggingAction.START_DIGGING, POS);
            f.check.startBreak = System.currentTimeMillis() - 2_150;
            f.damage = .023333333432674408; // Efficiency V netherite pickaxe swapped immediately before STOP.
            verify(f.send(DiggingAction.FINISHED_DIGGING, POS), never()).setCancelled(true);
            assertEquals(f.damage, f.check.maximumBlockDamage);
            assertTrue(f.check.blockBreakBalance < 25);
            f.state(StateTypes.AIR, type);
            PacketReceiveEvent repeated = f.send(DiggingAction.FINISHED_DIGGING, POS);
            if (type == StateTypes.OBSIDIAN) verify(repeated).setCancelled(true);
            else verify(repeated, never()).setCancelled(true);
        }
    }

    @Test void silentToolSwapStillRequiresFreshMiningTimeAndCannotUsePredictedAir() throws Exception {
        for (StateType type : List.of(StateTypes.OBSIDIAN, StateTypes.ENDER_CHEST)) {
            Fixture f = new Fixture();
            f.state(type, type);
            f.damage = .0002;
            f.send(DiggingAction.START_DIGGING, POS);
            f.check.startBreak = System.currentTimeMillis() - 100;
            f.state(StateTypes.AIR, type); // Server still has the real block behind the prediction.
            f.damage = .023333333432674408;
            verify(f.send(DiggingAction.FINISHED_DIGGING, POS)).setCancelled(true);
            assertTrue(f.check.blockBreakBalance > 1_000);
            verify(f.player).resyncPosition(POS, 37);
        }
    }

    @Test void stopForAnotherPositionDoesNotReplacePrimaryToolDamage() throws Exception {
        Fixture f = new Fixture();
        f.state(StateTypes.OBSIDIAN, StateTypes.OBSIDIAN);
        f.damage = .01;
        f.send(DiggingAction.START_DIGGING, POS);
        f.check.startBreak = System.currentTimeMillis() - 10_000;
        f.damage = 1; // Unrelated instant-break target in a DoubleBreak sequence.
        f.send(DiggingAction.FINISHED_DIGGING, OTHER);
        assertEquals(.01, f.check.maximumBlockDamage);
    }

    @Test void rejectedEarlyStopsDoNotResetFreshMiningTimeOrGrantObsidianRebreak() throws Exception {
        Fixture f = new Fixture();
        f.state(StateTypes.OBSIDIAN, StateTypes.OBSIDIAN);
        f.damage = .0002;
        f.send(DiggingAction.START_DIGGING, POS);
        f.check.startBreak = System.currentTimeMillis() - 100;
        long started = f.check.startBreak;
        f.damage = .023333333432674408;
        for (int i = 0; i < 4; i++) {
            verify(f.send(DiggingAction.FINISHED_DIGGING, POS)).setCancelled(true);
            assertEquals(started, f.check.startBreak);
            assertEquals(0, f.check.lastFinishBreak);
        }
        // Advance the test clock without waiting: the original attempt now has enough mining time.
        f.check.startBreak = System.currentTimeMillis() - 2_150;
        verify(f.send(DiggingAction.FINISHED_DIGGING, POS), never()).setCancelled(true);
        assertTrue(f.check.lastFinishBreak > 0);
        f.state(StateTypes.AIR, StateTypes.OBSIDIAN);
        verify(f.send(DiggingAction.FINISHED_DIGGING, POS)).setCancelled(true);
    }

    @Test void enderChestRebreakRemainsInstantAndDoesNotBuildFastBreakBalance() throws Exception {
        Fixture f = new Fixture();
        f.firstBreak(StateTypes.ENDER_CHEST);
        f.check.maximumBlockDamage = .001;
        f.check.startBreak = System.currentTimeMillis();
        f.check.blockBreakBalance = 0;
        for (int i = 0; i < 50; i++) {
            f.state(i % 2 == 0 ? StateTypes.ENDER_CHEST : StateTypes.AIR, StateTypes.ENDER_CHEST);
            verify(f.send(DiggingAction.FINISHED_DIGGING, POS), never()).setCancelled(true);
        }
        assertEquals(0, f.check.blockBreakBalance);
        assertEquals(0, f.check.violations);
    }

    @Test void enderChestPermissionCannotTransferToObsidianEvenWithPredictedAir() throws Exception {
        for (StateType predicted : List.of(StateTypes.AIR, StateTypes.ENDER_CHEST, StateTypes.OBSIDIAN)) {
            Fixture f = new Fixture();
            f.firstBreak(StateTypes.ENDER_CHEST);
            f.state(predicted, StateTypes.OBSIDIAN);
            verify(f.send(DiggingAction.FINISHED_DIGGING, POS)).setCancelled(true);
            verify(f.player).resyncPosition(POS, 37);
            assertEquals(0, f.check.violations);
        }
        Fixture f = new Fixture();
        f.firstBreak(StateTypes.ENDER_CHEST);
        f.state(StateTypes.OBSIDIAN, StateTypes.AIR); // Placement not acknowledged yet.
        verify(f.send(DiggingAction.FINISHED_DIGGING, POS)).setCancelled(true);
    }

    @Test void hardnessThresholdIncludesEqualHarderAndUnbreakableButAllowsSofterAndZero() throws Exception {
        for (StateType type : List.of(StateTypes.OBSIDIAN, StateTypes.CRYING_OBSIDIAN, StateTypes.REINFORCED_DEEPSLATE,
                StateTypes.BEDROCK, StateTypes.ENDER_CHEST, StateTypes.STONE, StateTypes.TORCH)) {
            Fixture f = new Fixture();
            f.firstBreak(StateTypes.ENDER_CHEST);
            f.state(StateTypes.AIR, type);
            boolean denied = type.getHardness() < 0 || type.getHardness() >= StateTypes.OBSIDIAN.getHardness();
            assertEquals(denied, f.check.isInvalidRebreak(f.breakEvent(DiggingAction.FINISHED_DIGGING, POS, StateTypes.AIR)));
        }
    }

    @Test void freshMiningAndVanillaCancelResumeStillUseExistingChecks() throws Exception {
        Fixture f = new Fixture();
        WrongBreak vanilla = mock(WrongBreak.class, CALLS_REAL_METHODS);
        var owner = GrimProcessor.class.getDeclaredField("player"); owner.setAccessible(true); owner.set(vanilla, f.player);
        doAnswer(call -> {
            BlockBreak b = call.getArgument(0);
            vanilla.onBlockBreak(b);
            f.check.onBlockBreak(b);
            return null;
        }).when(f.manager).onBlockBreak(any());
        f.state(StateTypes.OBSIDIAN, StateTypes.OBSIDIAN);
        verify(f.send(DiggingAction.START_DIGGING, POS), never()).setCancelled(true);
        verify(f.send(DiggingAction.CANCELLED_DIGGING, POS), never()).setCancelled(true);
        f.check.startBreak = System.currentTimeMillis() - 10_000;
        verify(f.send(DiggingAction.FINISHED_DIGGING, POS), never()).setCancelled(true);
        f.state(StateTypes.OBSIDIAN, StateTypes.OBSIDIAN);
        verify(f.send(DiggingAction.START_DIGGING, POS), never()).setCancelled(true);
        f.check.startBreak = System.currentTimeMillis() - 10_000;
        verify(f.send(DiggingAction.FINISHED_DIGGING, POS), never()).setCancelled(true);
        assertEquals(0, f.check.violations);
        assertEquals(0, vanilla.violations);
    }

    @Test void instantBreakVanillaStopExceptionIsPreserved() throws Exception {
        Fixture f = new Fixture();
        WrongBreak vanilla = mock(WrongBreak.class, CALLS_REAL_METHODS);
        var owner = GrimProcessor.class.getDeclaredField("player"); owner.setAccessible(true); owner.set(vanilla, f.player);
        try (var damage = mockStatic(BlockBreakSpeed.class)) {
            damage.when(() -> BlockBreakSpeed.couldInstantlyBreakBlock(eq(f.player), any())).thenReturn(true);
            BlockBreak start = f.breakEvent(DiggingAction.START_DIGGING, POS, StateTypes.TORCH);
            vanilla.onBlockBreak(start); f.check.onAcceptedBreak(start);
            BlockBreak stop = f.breakEvent(DiggingAction.FINISHED_DIGGING, OTHER, StateTypes.OBSIDIAN);
            vanilla.onBlockBreak(stop);
            assertFalse(stop.isCancelled());
            assertEquals(0, vanilla.violations);
            assertFalse(f.check.isInvalidRebreak(stop));
        }
    }

    @Test void airCannotEraseConsumedObsidianProgressButARealEnderChestCanUseIt() throws Exception {
        Fixture f = new Fixture();
        f.firstBreak(StateTypes.OBSIDIAN);
        f.state(StateTypes.AIR, StateTypes.AIR);
        verify(f.send(DiggingAction.FINISHED_DIGGING, POS)).setCancelled(true);
        f.state(StateTypes.AIR, StateTypes.ENDER_CHEST);
        verify(f.send(DiggingAction.FINISHED_DIGGING, POS), never()).setCancelled(true);
    }

    @Test void firstStopsForDoubleBreakAndUnknownVanillaStopsAreNotBlanketRejected() throws Exception {
        Fixture f = new Fixture();
        f.state(StateTypes.OBSIDIAN, StateTypes.OBSIDIAN);
        verify(f.send(DiggingAction.FINISHED_DIGGING, POS), never()).setCancelled(true);
        f.send(DiggingAction.START_DIGGING, POS);
        f.send(DiggingAction.START_DIGGING, OTHER);
        f.check.startBreak = System.currentTimeMillis() - 10_000;
        verify(f.send(DiggingAction.FINISHED_DIGGING, POS), never()).setCancelled(true);
        f.check.startBreak = System.currentTimeMillis() - 10_000;
        verify(f.send(DiggingAction.FINISHED_DIGGING, OTHER), never()).setCancelled(true);
    }

    @Test void cancelledStartAndCancelCannotRearmConsumedHardBlockProgress() throws Exception {
        Fixture f = new Fixture();
        f.firstBreak(StateTypes.OBSIDIAN);
        f.state(StateTypes.AIR, StateTypes.OBSIDIAN);
        f.send(DiggingAction.CANCELLED_DIGGING, POS);
        doAnswer(call -> {
            BlockBreak b = call.getArgument(0);
            if (b.action == DiggingAction.START_DIGGING) b.cancel();
            return null;
        }).when(f.manager).onBlockBreak(any());
        f.send(DiggingAction.START_DIGGING, OTHER);
        verify(f.send(DiggingAction.FINISHED_DIGGING, POS)).setCancelled(true);
        assertEquals(0, f.check.violations);
    }

    @Test void startBeforePlacementOrInAirCannotAuthorizeFutureObsidian() throws Exception {
        Fixture f = new Fixture();
        f.firstBreak(StateTypes.OBSIDIAN);
        f.state(StateTypes.AIR, StateTypes.AIR);
        f.send(DiggingAction.START_DIGGING, POS);
        f.state(StateTypes.AIR, StateTypes.OBSIDIAN);
        verify(f.send(DiggingAction.FINISHED_DIGGING, POS)).setCancelled(true);
        f.state(StateTypes.OBSIDIAN, StateTypes.OBSIDIAN);
        f.send(DiggingAction.START_DIGGING, POS);
        f.state(StateTypes.AIR, StateTypes.AIR);
        f.check.onBlockUpdate(POS, state(StateTypes.OBSIDIAN).getGlobalId());
        f.state(StateTypes.OBSIDIAN, StateTypes.OBSIDIAN);
        verify(f.send(DiggingAction.FINISHED_DIGGING, POS)).setCancelled(true);
        // Starting after that placement restores ordinary mining, not the old progress.
        f.send(DiggingAction.START_DIGGING, POS);
        f.check.startBreak = System.currentTimeMillis() - 10_000;
        verify(f.send(DiggingAction.FINISHED_DIGGING, POS), never()).setCancelled(true);
    }

    @Test void outOfHeightOrUnbreakableStartCannotEraseTrackedProgress() throws Exception {
        Fixture f = new Fixture();
        f.firstBreak(StateTypes.OBSIDIAN);
        f.send(DiggingAction.START_DIGGING, new Vector3i(POS.x, 955, POS.z));
        verify(f.send(DiggingAction.FINISHED_DIGGING, POS)).setCancelled(true);
        f.state(StateTypes.BEDROCK, StateTypes.BEDROCK);
        f.send(DiggingAction.START_DIGGING, OTHER);
        f.state(StateTypes.OBSIDIAN, StateTypes.OBSIDIAN);
        verify(f.send(DiggingAction.FINISHED_DIGGING, POS)).setCancelled(true);
    }

    @Test void serverLookupSeesBlockHiddenByPredictionAndSubsequentAuthoritativeReplacement() throws Exception {
        GrimPlayer player = mock(GrimPlayer.class);
        when(player.getClientVersion()).thenReturn(ClientVersion.V_1_21_11);
        CompensatedWorld world = spy(new CompensatedWorld(player));
        doReturn(state(StateTypes.AIR)).when(world).getBlock(POS);
        var field = CompensatedWorld.class.getDeclaredField("originalServerBlocks");
        field.setAccessible(true);
        @SuppressWarnings("unchecked") var originals = (Long2ObjectOpenHashMap<BlockPrediction>) field.get(world);
        var prediction = new BlockPrediction(new ArrayList<>(), POS, state(StateTypes.ENDER_CHEST).getGlobalId(), null);
        originals.put(POS.getSerializedPosition(), prediction);
        assertEquals(StateTypes.ENDER_CHEST, world.getServerBlock(POS).getType());
        world.updateBlock(POS, state(StateTypes.OBSIDIAN));
        assertEquals(StateTypes.AIR, world.getBlock(POS).getType());
        assertEquals(StateTypes.OBSIDIAN, world.getServerBlock(POS).getType());
        originals.clear();
        assertEquals(StateTypes.AIR, world.getServerBlock(POS).getType());
    }

    @Test void creativeStopSpamCannotPredictCobwebAirButCreativeStartStillCan() throws Exception {
        Fixture f = new Fixture();
        f.player.gamemode = com.github.retrooper.packetevents.protocol.player.GameMode.CREATIVE;
        f.state(StateTypes.COBWEB, StateTypes.COBWEB); f.damage = 1;
        for (int i = 0; i < 100; i++) verify(f.send(DiggingAction.FINISHED_DIGGING, POS), never()).setCancelled(true);
        verify(f.world, never()).startPredicting();
        verify(f.world, never()).updateBlock(anyInt(), anyInt(), anyInt(), anyInt());
        f.send(DiggingAction.START_DIGGING, POS);
        verify(f.world).startPredicting();
        verify(f.world).updateBlock(POS.x, POS.y, POS.z, 0);
    }

    @Test void survivalCobwebStopCannotBorrowInstantBreakExemptionFromGrass() throws Exception {
        Fixture f = new Fixture(); f.enableWrongBreak();
        f.player.gamemode = com.github.retrooper.packetevents.protocol.player.GameMode.SURVIVAL;
        f.state(StateTypes.SHORT_GRASS, StateTypes.SHORT_GRASS); f.damage = 1;
        f.send(DiggingAction.START_DIGGING, OTHER);
        f.state(StateTypes.COBWEB, StateTypes.COBWEB); f.damage = .01;
        clearInvocations(f.world);
        for (int i = 0; i < 100; i++) verify(f.send(DiggingAction.FINISHED_DIGGING, POS)).setCancelled(true);
        verify(f.world, never()).startPredicting();
        verify(f.player, times(100)).resyncPosition(POS, 37);
    }

    @Test void survivalCobwebMiningAndVanillaResumeAfterCancelRemainAccepted() throws Exception {
        Fixture f = new Fixture(); f.enableWrongBreak();
        f.player.gamemode = com.github.retrooper.packetevents.protocol.player.GameMode.SURVIVAL;
        f.state(StateTypes.COBWEB, StateTypes.COBWEB); f.damage = .1;
        f.send(DiggingAction.START_DIGGING, POS);
        f.check.startBreak = System.currentTimeMillis() - 10_000;
        f.send(DiggingAction.CANCELLED_DIGGING, POS);
        verify(f.send(DiggingAction.FINISHED_DIGGING, POS), never()).setCancelled(true);
        verify(f.world).startPredicting();
        verify(f.world).updateBlock(POS.x, POS.y, POS.z, 0);
    }

    @Test void cobwebFixPreservesTheInstantBreakExceptionForOtherBlocks() throws Exception {
        Fixture f = new Fixture(); f.enableWrongBreak();
        f.state(StateTypes.SHORT_GRASS, StateTypes.SHORT_GRASS); f.damage = 1;
        f.send(DiggingAction.START_DIGGING, OTHER);
        f.state(StateTypes.ENDER_CHEST, StateTypes.ENDER_CHEST); f.damage = .01;
        verify(f.send(DiggingAction.FINISHED_DIGGING, POS), never()).setCancelled(true);
    }

    static WrappedBlockState state(StateType type) { return WrappedBlockState.getDefaultState(type); }

    static class Fixture {
        final GrimPlayer player = mock(GrimPlayer.class);
        final CompensatedWorld world = mock(CompensatedWorld.class);
        final CheckManager manager = mock(CheckManager.class);
        final FastBreak check = mock(FastBreak.class, CALLS_REAL_METHODS);
        double damage = .1;
        WrongBreak wrongBreak;
        Fixture() throws Exception {
            when(player.getClientVersion()).thenReturn(ClientVersion.V_1_21_11);
            var owner = GrimProcessor.class.getDeclaredField("player"); owner.setAccessible(true); owner.set(check, player);
            for (var entry : java.util.Map.of("compensatedWorld", world, "checkManager", manager,
                    "queuedBreaks", new ArrayDeque<BlockBreak>(), "inventory", mock(CompensatedInventory.class),
                    "blockHistory", new ac.grim.grimac.utils.change.PlayerBlockHistory()).entrySet()) {
                var field = GrimPlayer.class.getField(entry.getKey()); field.setAccessible(true); field.set(player, entry.getValue());
            }
            when(manager.get(FastBreak.class)).thenReturn(check);
            doReturn(true).when(check).shouldModifyPackets();
            var verbose = ac.grim.grimac.checks.Check.class.getDeclaredField("verbose");
            verbose.setAccessible(true); verbose.set(check, new ac.grim.grimac.api.storage.verbose.VerboseBuf());
            doReturn(true).when(check).flag(any(ac.grim.grimac.api.storage.verbose.Verbose.Writer.class));
            when(world.getMinHeight()).thenReturn(-64);
            when(world.getMaxHeight()).thenReturn(320);
            doAnswer(call -> { check.onBlockBreak(call.getArgument(0)); if (wrongBreak != null) wrongBreak.onBlockBreak(call.getArgument(0)); return null; }).when(manager).onBlockBreak(any());
            state(StateTypes.AIR, StateTypes.AIR);
        }
        void enableWrongBreak() throws Exception {
            wrongBreak = mock(WrongBreak.class, CALLS_REAL_METHODS);
            var owner = GrimProcessor.class.getDeclaredField("player"); owner.setAccessible(true); owner.set(wrongBreak, player);
            var verbose = ac.grim.grimac.checks.Check.class.getDeclaredField("verbose");
            verbose.setAccessible(true); verbose.set(wrongBreak, new ac.grim.grimac.api.storage.verbose.VerboseBuf());
            doReturn(true).when(wrongBreak).shouldModifyPackets();
            doReturn(true).when(wrongBreak).flag(any(ac.grim.grimac.api.storage.verbose.Verbose.Writer.class));
        }
        void state(StateType predicted, StateType server) {
            WrappedBlockState predictedState = InstantRebreakTest.state(predicted), serverState = InstantRebreakTest.state(server);
            when(world.getBlock(any(Vector3i.class))).thenReturn(predictedState);
            when(world.getServerBlock(any())).thenReturn(serverState);
        }
        BlockBreak breakEvent(DiggingAction action, Vector3i pos, StateType type) {
            return new BlockBreak(player, pos, BlockFace.UP, 1, action, 37, InstantRebreakTest.state(type));
        }
        void firstBreak(StateType type) throws Exception {
            state(type, type);
            send(DiggingAction.START_DIGGING, POS);
            check.startBreak = System.currentTimeMillis() - 10_000;
            send(DiggingAction.FINISHED_DIGGING, POS);
        }
        PacketReceiveEvent send(DiggingAction action, Vector3i pos) throws Exception {
            PacketReceiveEvent packet = mock(PacketReceiveEvent.class);
            try (var speed = mockStatic(BlockBreakSpeed.class);
                 var construction = mockConstruction(WrapperPlayClientPlayerDigging.class, (p, context) -> {
                     when(p.getAction()).thenReturn(action); when(p.getBlockPosition()).thenReturn(pos);
                     when(p.getBlockFace()).thenReturn(BlockFace.UP); when(p.getBlockFaceId()).thenReturn(1);
                     when(p.getSequence()).thenReturn(37);
                 })) {
                speed.when(() -> BlockBreakSpeed.getBlockDamage(eq(player), any(WrappedBlockState.class))).thenReturn(damage);
                speed.when(() -> BlockBreakSpeed.couldInstantlyBreakBlock(eq(player), any(StateType.class)))
                        .thenAnswer(call -> call.getArgument(1) == StateTypes.SHORT_GRASS || damage >= 1);
                speed.when(() -> BlockBreakSpeed.getBlockDamage(eq(player), any(), any(StateType.class))).thenAnswer(call -> {
                    StateType type = call.getArgument(2);
                    return type.isAir() ? Double.POSITIVE_INFINITY : damage;
                });
                var method = CheckManagerListener.class.getDeclaredMethod("handleDigging", GrimPlayer.class, PacketReceiveEvent.class);
                method.setAccessible(true); method.invoke(null, player, packet);
            }
            return packet;
        }
    }
}
