package ac.grim.grimac.checks.impl.velocity;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.predictionengine.predictions.PredictionEngine;
import ac.grim.grimac.utils.data.VectorData;
import ac.grim.grimac.utils.data.VelocityData;
import ac.grim.grimac.utils.math.Vector3dm;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VelocityCancellationTest {
    @Test
    void knockbackCancellationNeverRequiresVelocityEvenAfterStrictConfigReload() throws Exception {
        GrimPlayer player = mock(GrimPlayer.class);
        player.firstBreadKB = new VelocityData(1, 1, false, new Vector3dm(0.5, 0.4, 0));
        player.likelyKB = new VelocityData(1, 2, true, new Vector3dm(0.5, 0.4, 0));
        KnockbackHandler handler = mock(KnockbackHandler.class, CALLS_REAL_METHODS);
        setPlayer(handler, player);
        handler.onReload(mock(ConfigManager.class));
        assertFalse(handler.wouldFlag());
        var candidate = new VectorData(new Vector3dm(), VectorData.VectorType.Knockback)
                .returnNewModified(VectorData.VectorType.FirstBreadKnockback);
        assertFalse(handler.shouldIgnoreForPrediction(candidate));
        assertEquals(Integer.MAX_VALUE, player.likelyKB.offset);
        handler.onReload(mock(ConfigManager.class));
        assertFalse(handler.wouldFlag());
    }

    @Test
    void explosionCancellationNeverRequiresVelocityEvenAfterStrictConfigReload() throws Exception {
        GrimPlayer player = mock(GrimPlayer.class);
        player.firstBreadExplosion = new VelocityData(-1, 1, false, new Vector3dm(0.5, 0.4, 0));
        player.likelyExplosions = new VelocityData(-1, 2, true, new Vector3dm(0.5, 0.4, 0));
        ExplosionHandler handler = mock(ExplosionHandler.class, CALLS_REAL_METHODS);
        setPlayer(handler, player);
        handler.onReload(mock(ConfigManager.class));
        assertFalse(handler.wouldFlag());
        var candidate = new VectorData(new Vector3dm(), VectorData.VectorType.Explosion)
                .returnNewModified(VectorData.VectorType.FirstBreadExplosion);
        assertFalse(handler.shouldIgnoreForPrediction(candidate));
        assertEquals(Integer.MAX_VALUE, player.likelyExplosions.offset);
    }

    @Test
    void normalAndFullKnockbackRemainAvailableToPrediction() {
        GrimPlayer player = mock(GrimPlayer.class);
        player.clientVelocity = new Vector3dm(0.1, -0.0784, 0);
        player.likelyKB = new VelocityData(1, 2, false, new Vector3dm(0.5, 0.4, 0));
        VectorData cancelled = new VectorData(player.clientVelocity, VectorData.VectorType.Normal);
        when(player.getPossibleVelocitiesMinusKnockback()).thenReturn(new HashSet<>(Set.of(cancelled)));
        when(player.getPossibleVelocities()).thenCallRealMethod();
        var candidates = player.getPossibleVelocities();
        assertTrue(candidates.contains(cancelled));
        assertTrue(candidates.stream().anyMatch(v -> v.isKnockback() && v.vector.equals(player.likelyKB.vector)));
        assertEquals(2, candidates.size());
    }

    @Test
    void explosionsKeepBothUnchangedAndFullImpulseCandidates() {
        GrimPlayer player = mock(GrimPlayer.class);
        player.likelyExplosions = new VelocityData(-1, 2, false, new Vector3dm(0.5, 0.4, 0));
        VectorData cancelled = new VectorData(new Vector3dm(0.1, -0.0784, 0), VectorData.VectorType.Normal);
        Set<VectorData> candidates = new HashSet<>(Set.of(cancelled));
        new PredictionEngine().addExplosionToPossibilities(player, candidates);
        assertTrue(candidates.contains(cancelled));
        Vector3dm expected = cancelled.vector.clone().add(player.likelyExplosions.vector);
        assertTrue(candidates.stream().anyMatch(v -> v.isExplosion() && v.vector.equals(expected)));
        assertEquals(new Vector3dm(0.1, -0.0784, 0), cancelled.vector);
        assertEquals(2, candidates.size());
    }

    private static void setPlayer(Object check, GrimPlayer player) throws Exception {
        var field = ac.grim.grimac.checks.GrimProcessor.class.getDeclaredField("player");
        field.setAccessible(true);
        field.set(check, player);
    }
}
