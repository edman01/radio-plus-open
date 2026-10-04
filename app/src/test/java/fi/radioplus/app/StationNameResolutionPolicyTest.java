package fi.radioplus.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class StationNameResolutionPolicyTest {
    @Test
    public void waitsForTunerToReachRequestedFrequency() {
        assertEquals(
                StationNameResolutionPolicy.Decision.WAIT,
                decide(false, false, 7_999L, 0L, 1)
        );
    }

    @Test
    public void retriesWhenFirstTuneNeverArrives() {
        assertEquals(
                StationNameResolutionPolicy.Decision.RETUNE,
                decide(false, false, 8_000L, 0L, 1)
        );
    }

    @Test
    public void skipsAfterSecondTuneTimeout() {
        assertEquals(
                StationNameResolutionPolicy.Decision.SKIP,
                decide(false, false, 8_000L, 0L, 2)
        );
    }

    @Test
    public void startsSeparateRdsWaitAfterTuneConfirmation() {
        assertEquals(
                StationNameResolutionPolicy.Decision.WAIT,
                decide(true, false, 20_000L, 9_999L, 1)
        );
    }

    @Test
    public void savesNameAsSoonAsRdsPsArrives() {
        assertEquals(
                StationNameResolutionPolicy.Decision.SAVE,
                decide(true, true, 900L, 100L, 1)
        );
    }

    @Test
    public void skipsOnlyAfterFullRdsWindowExpires() {
        assertEquals(
                StationNameResolutionPolicy.Decision.SKIP,
                decide(true, false, 20_500L, 20_000L, 1)
        );
    }

    @Test
    public void keepsWaitingDuringExtendedJunsunRdsWindow() {
        assertEquals(
                StationNameResolutionPolicy.Decision.WAIT,
                decide(true, false, 19_900L, 19_999L, 1)
        );
    }

    private static StationNameResolutionPolicy.Decision decide(
            boolean matchingState,
            boolean hasRdsName,
            long tuneElapsedMs,
            long matchedElapsedMs,
            int tuneAttempt
    ) {
        return StationNameResolutionPolicy.decide(
                matchingState,
                hasRdsName,
                tuneElapsedMs,
                matchedElapsedMs,
                tuneAttempt
        );
    }
}
