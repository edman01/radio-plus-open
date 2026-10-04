package fi.radioplus.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PlaybackOwnershipPolicyTest {
    @Test
    public void observationsBeforeAnExplicitRequestCannotYield() {
        PlaybackOwnershipPolicy policy = new PlaybackOwnershipPolicy();

        assertFalse(policy.shouldYield(10_000L, true, false));
        assertFalse(policy.shouldYield(20_000L, true, false));
    }

    @Test
    public void gracePeriodDiscardsRatherThanAccumulatesExternalEvidence() {
        PlaybackOwnershipPolicy policy = new PlaybackOwnershipPolicy();
        policy.reset(100L);

        assertFalse(policy.shouldYield(100L, true, false));
        assertFalse(policy.shouldYield(1000L, true, false));
        assertFalse(policy.shouldYield(2599L, true, false));
        assertFalse(policy.shouldYield(2600L, true, false));
        assertTrue(policy.shouldYield(2601L, true, false));
    }

    @Test
    public void exactGraceBoundaryAcceptsTheFirstOfTwoObservations() {
        PlaybackOwnershipPolicy policy = new PlaybackOwnershipPolicy();
        policy.reset(0L);

        assertFalse(policy.shouldYield(2500L, true, false));
        assertTrue(policy.shouldYield(4000L, true, false));
    }

    @Test
    public void unknownSourceBreaksConsecutiveEvidence() {
        PlaybackOwnershipPolicy policy = new PlaybackOwnershipPolicy();
        policy.reset(0L);

        assertFalse(policy.shouldYield(2500L, true, false));
        assertFalse(policy.shouldYield(4000L, false, false));
        assertFalse(policy.shouldYield(5500L, true, false));
        assertTrue(policy.shouldYield(7000L, true, false));
    }

    @Test
    public void unknownSourceNeverYieldsRegardlessOfOwnershipPlaceholder() {
        PlaybackOwnershipPolicy policy = new PlaybackOwnershipPolicy();
        policy.reset(0L);

        for (int sample = 0; sample < 100; sample++) {
            assertFalse(policy.shouldYield(2500L + sample * 1500L, false,
                    sample % 2 == 0));
        }
        assertFalse(policy.shouldYield(200_000L, true, false));
        assertTrue(policy.shouldYield(201_500L, true, false));
    }

    @Test
    public void radioSourceBreaksConsecutiveEvidence() {
        PlaybackOwnershipPolicy policy = new PlaybackOwnershipPolicy();
        policy.reset(0L);

        assertFalse(policy.shouldYield(2500L, true, false));
        assertFalse(policy.shouldYield(4000L, true, true));
        assertFalse(policy.shouldYield(5500L, true, false));
        assertTrue(policy.shouldYield(7000L, true, false));
    }

    @Test
    public void retainedRadioSourceNeverYields() {
        PlaybackOwnershipPolicy policy = new PlaybackOwnershipPolicy();
        policy.reset(0L);

        for (int sample = 0; sample < 100; sample++) {
            assertFalse(policy.shouldYield(2500L + sample * 1500L, true, true));
        }
    }

    @Test
    public void explicitNewRequestResetsEvidenceAndRestartsGrace() {
        PlaybackOwnershipPolicy policy = new PlaybackOwnershipPolicy();
        policy.reset(0L);
        assertFalse(policy.shouldYield(2500L, true, false));
        assertTrue(policy.shouldYield(4000L, true, false));

        policy.reset(5000L);

        assertFalse(policy.shouldYield(7499L, true, false));
        assertFalse(policy.shouldYield(7500L, true, false));
        assertTrue(policy.shouldYield(9000L, true, false));
    }

    @Test
    public void confirmedExternalSourceRemainsConfirmedWithoutCounterOverflow() {
        PlaybackOwnershipPolicy policy = new PlaybackOwnershipPolicy();
        policy.reset(0L);
        assertFalse(policy.shouldYield(2500L, true, false));

        for (int sample = 0; sample < 100; sample++) {
            assertTrue(policy.shouldYield(4000L + sample * 1500L, true, false));
        }
        assertFalse(policy.shouldYield(200_000L, false, false));
        assertFalse(policy.shouldYield(201_500L, true, false));
        assertTrue(policy.shouldYield(203_000L, true, false));
    }

    @Test
    public void timestampBeforeRequestCannotCountAsExternalEvidence() {
        PlaybackOwnershipPolicy policy = new PlaybackOwnershipPolicy();
        policy.reset(10_000L);

        assertFalse(policy.shouldYield(9999L, true, false));
        assertFalse(policy.shouldYield(12_500L, true, false));
        assertTrue(policy.shouldYield(14_000L, true, false));
    }
}
